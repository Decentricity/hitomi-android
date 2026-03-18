package ai.agent1c.hitomi;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

public class HitomiAuthManager {
    private static final String TAG = "HitomiAuth";
    public static final String BACKEND_URL = "https://gkfhxhrleuauhnuewfmw.supabase.co";
    public static final String BACKEND_PUBLIC_KEY = "sb_publishable_r_NH0OEY5Y6rNy9rzPu1NQ_PYGZs5Nj";
    public static final String CHAT_ENDPOINT_URL = BACKEND_URL + "/functions/v1/xai-chat";
    public static final String ANDROID_AUTH_HANDOFF_FUNCTION_URL = BACKEND_URL + "/functions/v1/android-auth-handoff";
    public static final String APP_REDIRECT_URI = "hitomicompanion://auth/callback";
    public static final String OAUTH_REDIRECT_URI = "hitomicompanion://auth/oauth";
    public static final String LEGACY_APP_REDIRECT_URI = "agent1cai://auth/callback";
    public static final String LEGACY_OAUTH_REDIRECT_URI = "agent1cai://auth/oauth";
    public static final String AUTH_ENTRY_URL_PRIMARY = "https://agent1c.ai/";
    public static final String AUTH_ENTRY_URL_FALLBACK = "https://hitomicompanion.github.io/";
    public static final boolean USE_FALLBACK_AUTH_ENTRY = false;

    private static final String PREFS = "agent1c_android_auth";
    private static final String K_ACCESS = "access_token";
    private static final String K_REFRESH = "refresh_token";
    private static final String K_EXPIRES_AT = "expires_at";
    private static final String K_EMAIL = "user_email";
    private static final String K_PROVIDER = "provider";
    private static final String K_DISPLAY = "display_name";
    private static final String K_WALLET_ADDRESS = "wallet_address";
    private static final String K_WALLET_CHAIN = "wallet_chain";
    private static final String K_OAUTH_CODE_VERIFIER = "oauth_code_verifier";
    private static final String K_LAST_HANDOFF_CODE = "last_handoff_code";

    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();

    public HitomiAuthManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String buildOAuthUrl(String provider) {
        String codeVerifier = preparePkceCodeVerifier();
        String codeChallenge = pkceCodeChallenge(codeVerifier);
        Uri.Builder b = Uri.parse(BACKEND_URL + "/auth/v1/authorize").buildUpon();
        b.appendQueryParameter("provider", provider);
        b.appendQueryParameter("redirect_to", OAUTH_REDIRECT_URI);
        b.appendQueryParameter("flow_type", "pkce");
        b.appendQueryParameter("response_type", "code");
        b.appendQueryParameter("code_challenge", codeChallenge);
        b.appendQueryParameter("code_challenge_method", "s256");
        String url = b.build().toString();
        Log.d(TAG, "buildOAuthUrl provider=" + provider + " url=" + url);
        return url;
    }

    public String buildWebAuthLaunchUrl(String provider) {
        Uri.Builder b = Uri.parse(USE_FALLBACK_AUTH_ENTRY ? AUTH_ENTRY_URL_FALLBACK : AUTH_ENTRY_URL_PRIMARY).buildUpon();
        b.appendQueryParameter("android_auth", "1");
        if (provider != null) {
            String p = provider.trim().toLowerCase();
            if (!p.isEmpty()) b.appendQueryParameter("android_provider", p);
        }
        return b.build().toString();
    }

    public String preparePkceCodeVerifier() {
        String codeVerifier = randomToken(48);
        prefs.edit().putString(K_OAUTH_CODE_VERIFIER, codeVerifier).apply();
        return codeVerifier;
    }

    public static String pkceCodeChallenge(String verifier) {
        return sha256Base64Url(verifier);
    }

    public void completePkceCodeExchange(String code) throws Exception {
        JSONObject exchanged = exchangePkceCode(code);
        String accessToken = exchanged.optString("access_token", "");
        String refreshToken = exchanged.optString("refresh_token", "");
        long expiresIn = exchanged.optLong("expires_in", 3600L);
        if (accessToken.isEmpty()) throw new IllegalStateException("PKCE code exchange returned no access token");
        storeSession(accessToken, refreshToken, expiresIn);
        prefs.edit().remove(K_OAUTH_CODE_VERIFIER).apply();
        refreshUserProfile();
    }

    public void sendMagicLink(String email) throws Exception {
        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("create_user", true);
        body.put("email_redirect_to", APP_REDIRECT_URI);
        body.put("redirect_to", APP_REDIRECT_URI);
        JSONObject options = new JSONObject();
        options.put("email_redirect_to", APP_REDIRECT_URI);
        options.put("redirect_to", APP_REDIRECT_URI);
        body.put("options", options);
        requestJson("POST", BACKEND_URL + "/auth/v1/otp", body.toString(), null);
    }

    public boolean handleAuthCallbackUri(Uri uri) throws Exception {
        if (uri == null) return false;
        String dataString = uri.toString();
        Log.d(TAG, "handleAuthCallbackUri data=" + dataString);
        if (!isSupportedCallbackUri(dataString)) return false;
        String query = uri.getEncodedQuery();
        String fragment = "";
        int hash = dataString.indexOf('#');
        if (hash >= 0 && hash + 1 < dataString.length()) fragment = dataString.substring(hash + 1);
        JSONObject values = parseQueryLike(fragment);
        if (values.length() == 0 && query != null) values = parseQueryLike(query);
        String callbackState = values.optString("state", "");
        if (!callbackState.isEmpty()) {
            Log.d(TAG, "handleAuthCallbackUri callback state present (provider-managed)");
        }

        String handoffCode = values.optString("handoff_code", "");
        if (!handoffCode.isEmpty()) {
            Log.d(TAG, "handleAuthCallbackUri using android handoff exchange");
            String lastHandoff = prefs.getString(K_LAST_HANDOFF_CODE, "");
            if (handoffCode.equals(lastHandoff)) {
                Log.d(TAG, "handleAuthCallbackUri duplicate handoff code ignored");
                return isSignedIn();
            }
            try {
                exchangeAndroidHandoffCode(handoffCode);
                prefs.edit().putString(K_LAST_HANDOFF_CODE, handoffCode).apply();
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("handoff_not_found") && isSignedIn()) {
                    Log.d(TAG, "handleAuthCallbackUri handoff_not_found after sign-in; treating as duplicate success");
                    prefs.edit().putString(K_LAST_HANDOFF_CODE, handoffCode).apply();
                    return true;
                }
                throw e;
            }
            return true;
        }

        String accessToken = values.optString("access_token", "");
        String refreshToken = values.optString("refresh_token", "");
        if (accessToken.isEmpty()) {
            String code = values.optString("code", "");
            String err = values.optString("error_description", values.optString("error", ""));
            if (!code.isEmpty()) {
                Log.d(TAG, "handleAuthCallbackUri using PKCE code exchange");
                completePkceCodeExchange(code);
                return true;
            }
            if (!err.isEmpty()) throw new IllegalStateException(err);
            return false;
        }

        long expiresIn = safeLong(values.optString("expires_in", "3600"), 3600L);
        storeSession(accessToken, refreshToken, expiresIn);
        prefs.edit().remove(K_OAUTH_CODE_VERIFIER).apply();
        refreshUserProfile();
        Log.d(TAG, "handleAuthCallbackUri implicit/token callback handled");
        return true;
    }

    private boolean isSupportedCallbackUri(String dataString) {
        if (dataString == null || dataString.isEmpty()) return false;
        return dataString.startsWith(APP_REDIRECT_URI)
            || dataString.startsWith(OAUTH_REDIRECT_URI)
            || dataString.startsWith(LEGACY_APP_REDIRECT_URI)
            || dataString.startsWith(LEGACY_OAUTH_REDIRECT_URI);
    }

    public boolean isSignedIn() {
        return !getAccessTokenRaw().isEmpty() && !isExpired();
    }

    public String getAccessTokenRaw() {
        return prefs.getString(K_ACCESS, "");
    }

    public String getDisplayName() {
        String display = prefs.getString(K_DISPLAY, "").trim();
        if (!display.isEmpty()) return display;
        String email = getEmail();
        if (email.contains("@")) return email.substring(0, email.indexOf('@'));
        return "friend";
    }

    public String getEmail() {
        return prefs.getString(K_EMAIL, "");
    }

    public String getProvider() {
        return prefs.getString(K_PROVIDER, "");
    }

    public String getWalletAddress() {
        return prefs.getString(K_WALLET_ADDRESS, "");
    }

    public String getWalletChain() {
        return prefs.getString(K_WALLET_CHAIN, "");
    }

    public boolean hasConnectedSolanaWallet() {
        return "solana".equalsIgnoreCase(getWalletChain()) && !getWalletAddress().trim().isEmpty();
    }

    public synchronized String ensureValidAccessToken() throws Exception {
        String token = getAccessTokenRaw();
        if (token.isEmpty()) return "";
        long expiresAt = prefs.getLong(K_EXPIRES_AT, 0L);
        long now = System.currentTimeMillis() / 1000L;
        if (expiresAt > now + 120) return token;
        String refresh = prefs.getString(K_REFRESH, "");
        if (refresh.isEmpty()) return token;

        JSONObject body = new JSONObject();
        body.put("refresh_token", refresh);
        JSONObject json = requestJson("POST", BACKEND_URL + "/auth/v1/token?grant_type=refresh_token", body.toString(), null);
        String newAccess = json.optString("access_token", token);
        String newRefresh = json.optString("refresh_token", refresh);
        long expiresIn = json.optLong("expires_in", 3600L);
        prefs.edit()
            .putString(K_ACCESS, newAccess)
            .putString(K_REFRESH, newRefresh)
            .putLong(K_EXPIRES_AT, (System.currentTimeMillis() / 1000L) + expiresIn)
            .apply();
        try { refreshUserProfile(); } catch (Exception ignored) {}
        return newAccess;
    }

    public void signOut() {
        prefs.edit()
            .remove(K_ACCESS)
            .remove(K_REFRESH)
            .remove(K_EXPIRES_AT)
            .remove(K_EMAIL)
            .remove(K_PROVIDER)
            .remove(K_DISPLAY)
            .remove(K_WALLET_ADDRESS)
            .remove(K_WALLET_CHAIN)
            .remove(K_LAST_HANDOFF_CODE)
            .apply();
    }

    public void refreshUserProfile() throws Exception {
        String token = getAccessTokenRaw();
        if (token.isEmpty()) return;
        JSONObject user = requestJson("GET", BACKEND_URL + "/auth/v1/user", null, token);
        String email = user.optString("email", "");
        String provider = "";
        String display = "";
        String walletAddress = "";
        String walletChain = "";

        JSONObject appMeta = user.optJSONObject("app_metadata");
        JSONObject userMeta = user.optJSONObject("user_metadata");
        if (appMeta != null) provider = appMeta.optString("provider", "");

        JSONObject customClaims = userMeta == null ? null : userMeta.optJSONObject("custom_claims");

        JSONArray identities = user.optJSONArray("identities");
        String providerRawBase = provider.trim().toLowerCase();
        String chainHint = firstNonBlank(
            findNestedString(customClaims, "chain", "network"),
            findNestedString(userMeta, "chain", "network"),
            findNestedString(appMeta, "chain", "network")
        ).toLowerCase();
        if (("wallet".equals(providerRawBase) || "web3".equals(providerRawBase)) && "solana".equals(chainHint)) {
            provider = "solana";
        } else if ("twitter".equals(providerRawBase)) {
            provider = "x";
        }
        if (identities != null) {
            for (int i = 0; i < identities.length(); i++) {
                JSONObject ident = identities.optJSONObject(i);
                if (ident == null) continue;
                String p = ident.optString("provider", "").trim().toLowerCase();
                JSONObject idData = ident.optJSONObject("identity_data");
                String identChain = firstNonBlank(
                    findNestedString(idData, "chain", "network"),
                    chainHint
                ).toLowerCase();
                if (("wallet".equals(p) || "web3".equals(p)) && "solana".equals(identChain)) {
                    p = "solana";
                } else if ("twitter".equals(p)) {
                    p = "x";
                }
                if (provider.isEmpty() && !p.isEmpty()) provider = p;
                if (idData == null) continue;
                if ("solana".equals(p) && walletAddress.isEmpty()) {
                    walletAddress = findFirstWalletAddress(idData, customClaims, userMeta, appMeta, ident);
                    walletChain = walletAddress.isEmpty() ? "" : "solana";
                    if (display.isEmpty()) display = shortenAddress(walletAddress);
                } else if ("x".equals(p) || "twitter".equals(p)) {
                    String uname = idData.optString("user_name", "").trim();
                    if (!uname.isEmpty()) display = "@" + uname;
                } else if ("google".equals(p) && display.isEmpty()) {
                    display = idData.optString("email", "");
                }
            }
        }
        if (walletAddress.isEmpty()) {
            walletAddress = findFirstWalletAddress(customClaims, userMeta, appMeta);
            walletChain = walletAddress.isEmpty() ? "" : inferWalletChain(provider, walletAddress);
        }
        if (display.isEmpty() && email.contains("@")) display = email.substring(0, email.indexOf('@'));
        if (display.isEmpty() && !walletAddress.isEmpty()) display = shortenAddress(walletAddress);

        prefs.edit()
            .putString(K_EMAIL, email)
            .putString(K_PROVIDER, provider)
            .putString(K_DISPLAY, display)
            .putString(K_WALLET_ADDRESS, walletAddress)
            .putString(K_WALLET_CHAIN, walletChain)
            .apply();
    }

    public void exchangeAndroidHandoffCode(String handoffCode) throws Exception {
        JSONObject body = new JSONObject();
        body.put("action", "exchange");
        body.put("handoff_code", handoffCode);
        JSONObject json = requestJson("POST", ANDROID_AUTH_HANDOFF_FUNCTION_URL, body.toString(), null);
        JSONObject session = json.optJSONObject("session");
        if (session == null) throw new IllegalStateException("Android handoff exchange returned no session");
        String accessToken = session.optString("access_token", "");
        String refreshToken = session.optString("refresh_token", "");
        long expiresIn = session.optLong("expires_in", 3600L);
        if (accessToken.isEmpty() || refreshToken.isEmpty()) {
            throw new IllegalStateException("Android handoff session missing tokens");
        }
        storeSession(accessToken, refreshToken, expiresIn);

        JSONObject identity = json.optJSONObject("identity");
        if (identity != null) {
            String email = identity.optString("email", "");
            String provider = identity.optString("provider", "");
            String handle = identity.optString("handle", "");
            String walletAddress = findFirstWalletAddress(identity);
            String walletChain = inferWalletChain(provider, walletAddress);
            String display = (!handle.isEmpty())
                ? (handle.startsWith("@") ? handle : "@" + handle)
                : (!email.isEmpty() ? email : shortenAddress(walletAddress));
            prefs.edit()
                .putString(K_EMAIL, email)
                .putString(K_PROVIDER, provider)
                .putString(K_DISPLAY, display)
                .putString(K_WALLET_ADDRESS, walletAddress)
                .putString(K_WALLET_CHAIN, walletChain)
                .apply();
        }
        try { refreshUserProfile(); } catch (Exception ignored) {}
    }

    private boolean isExpired() {
        long expiresAt = prefs.getLong(K_EXPIRES_AT, 0L);
        if (expiresAt <= 0L) return false;
        return (System.currentTimeMillis() / 1000L) >= expiresAt;
    }

    private JSONObject parseQueryLike(String raw) throws Exception {
        JSONObject out = new JSONObject();
        if (raw == null || raw.isEmpty()) return out;
        String[] parts = raw.split("&");
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            String[] kv = part.split("=", 2);
            String k = Uri.decode(kv[0]);
            String v = kv.length > 1 ? Uri.decode(kv[1]) : "";
            out.put(k, v);
        }
        return out;
    }

    private JSONObject requestJson(String method, String url, String jsonBody, String bearerToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("apikey", BACKEND_PUBLIC_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        if (bearerToken != null && !bearerToken.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        }
        if (jsonBody != null) {
            conn.setDoOutput(true);
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
        }

        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(stream);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Hosted auth failed (" + code + "): " + body);
        }
        return body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
    }

    private JSONObject exchangePkceCode(String code) throws Exception {
        String verifier = prefs.getString(K_OAUTH_CODE_VERIFIER, "");
        if (verifier.isEmpty()) throw new IllegalStateException("Missing PKCE code verifier");
        Log.d(TAG, "exchangePkceCode start");
        JSONObject body = new JSONObject();
        body.put("auth_code", code);
        body.put("code_verifier", verifier);
        return requestJson("POST", BACKEND_URL + "/auth/v1/token?grant_type=pkce", body.toString(), null);
    }

    private void storeSession(String accessToken, String refreshToken, long expiresIn) {
        long expiresAt = (System.currentTimeMillis() / 1000L) + Math.max(60L, expiresIn);
        prefs.edit()
            .putString(K_ACCESS, accessToken == null ? "" : accessToken)
            .putString(K_REFRESH, refreshToken == null ? "" : refreshToken)
            .putLong(K_EXPIRES_AT, expiresAt)
            .apply();
    }

    public String getDirectApiKey() {
        return "";
    }

    public void signInWithDirectApiKey(String apiKey) {
        throw new IllegalStateException("Direct API keys are only available in Open Hitomi");
    }

    private String randomToken(int byteCount) {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String sha256Base64Url(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static long safeLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String inferWalletChain(String provider, String walletAddress) {
        String p = provider == null ? "" : provider.trim().toLowerCase();
        if ("solana".equals(p)) return walletAddress == null || walletAddress.trim().isEmpty() ? "" : "solana";
        return isLikelySolanaAddress(walletAddress) ? "solana" : "";
    }

    private static String findFirstWalletAddress(JSONObject... sources) {
        if (sources == null) return "";
        for (JSONObject source : sources) {
            if (source == null) continue;
            String direct = firstNonBlank(
                source.optString("wallet_address", ""),
                source.optString("address", ""),
                source.optString("public_key", ""),
                source.optString("publicKey", ""),
                source.optString("account", ""),
                source.optString("account_address", ""),
                source.optString("provider_id", ""),
                source.optString("id", "")
            );
            if (isLikelySolanaAddress(direct)) return direct.trim();
            String sub = source.optString("sub", "");
            if (isLikelySolanaAddress(sub)) return sub.trim();
            if (sub.contains(":")) {
                String tail = sub.substring(sub.lastIndexOf(':') + 1).trim();
                if (isLikelySolanaAddress(tail)) return tail;
            }
        }
        return "";
    }

    private static String findNestedString(JSONObject obj, String... keys) {
        if (obj == null || keys == null) return "";
        for (String key : keys) {
            if (key == null || key.trim().isEmpty()) continue;
            String value = obj.optString(key, "");
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static boolean isLikelySolanaAddress(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        if (trimmed.length() < 32 || trimmed.length() > 64) return false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean digit = c >= '1' && c <= '9';
            boolean upper = c >= 'A' && c <= 'Z';
            boolean lower = c >= 'a' && c <= 'z';
            if (!(digit || upper || lower)) return false;
            if (c == '0' || c == 'O' || c == 'I' || c == 'l') return false;
        }
        return true;
    }

    private static String shortenAddress(String address) {
        if (address == null) return "";
        String trimmed = address.trim();
        if (trimmed.length() <= 10) return trimmed;
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }
}
