package ai.agent1c.hitomi;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public class HitomiAuthManager {
    public static final String BACKEND_PUBLIC_KEY = "";
    public static final String CHAT_ENDPOINT_URL = "";

    private static final String PREFS = "agent1c_android_auth";
    private static final String K_DIRECT_API_KEY = "direct_api_key";
    private static final String K_LOCAL_ENDPOINT = "local_endpoint";

    private final SharedPreferences prefs;

    public HitomiAuthManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isSignedIn() {
        return !getDirectApiKey().isEmpty() || !getLocalEndpoint().isEmpty();
    }

    public String getDisplayName() {
        return "friend";
    }

    public String getEmail() {
        return "";
    }

    public String getProvider() {
        return isUsingLocalEndpoint() ? "local endpoint" : "BYOK";
    }

    public String getWalletAddress() {
        return "";
    }

    public String getWalletChain() {
        return "";
    }

    public boolean hasConnectedSolanaWallet() {
        return false;
    }

    public synchronized String ensureValidAccessToken() {
        return getDirectApiKey();
    }

    public String getDirectApiKey() {
        return prefs.getString(K_DIRECT_API_KEY, "").trim();
    }

    public String getLocalEndpoint() {
        return prefs.getString(K_LOCAL_ENDPOINT, "").trim();
    }

    public boolean isUsingLocalEndpoint() {
        return !getLocalEndpoint().isEmpty();
    }

    public void signInWithConnectionInput(String input) {
        String value = input == null ? "" : input.trim();
        SharedPreferences.Editor editor = prefs.edit();
        if (looksLikeEndpoint(value)) {
            editor.putString(K_LOCAL_ENDPOINT, normalizeEndpoint(value));
            editor.remove(K_DIRECT_API_KEY);
        } else {
            editor.putString(K_DIRECT_API_KEY, value);
            editor.remove(K_LOCAL_ENDPOINT);
        }
        editor.apply();
    }

    public void signOut() {
        prefs.edit().remove(K_DIRECT_API_KEY).remove(K_LOCAL_ENDPOINT).apply();
    }

    public String buildWebAuthLaunchUrl(String provider) {
        return "";
    }

    public boolean handleAuthCallbackUri(Uri uri) {
        return false;
    }

    public static boolean looksLikeEndpoint(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return false;
        return trimmed.startsWith("http://") || trimmed.startsWith("https://");
    }

    private static String normalizeEndpoint(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
