package ai.agent1c.hitomi;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SolanaWalletClient {
    private static final String PREFS = "hitomi_solana_wallet";
    private static final String K_LAST_SNAPSHOT = "last_snapshot";
    private static final String K_WALLET_NAME = "wallet_name";
    private static final String K_WALLET_ADDRESS = "wallet_address";
    private static final String RPC_DEFAULT = "https://api.mainnet-beta.solana.com";
    private static final String[] RPC_FALLBACKS = new String[] {
        "https://api.mainnet-beta.solana.com",
        "https://solana-rpc.publicnode.com",
        "https://rpc.ankr.com/solana"
    };

    private final SharedPreferences prefs;

    public SolanaWalletClient(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public StoredWallet getStoredWallet() {
        String name = clean(prefs.getString(K_WALLET_NAME, ""));
        String address = clean(prefs.getString(K_WALLET_ADDRESS, ""));
        if (address.isEmpty()) return null;
        return new StoredWallet(name, address);
    }

    public boolean hasStoredWallet() {
        StoredWallet wallet = getStoredWallet();
        return wallet != null && !wallet.address.isEmpty();
    }

    public void saveStoredWallet(String walletName, String walletAddress) {
        String cleanName = clean(walletName);
        String cleanAddress = clean(walletAddress);
        if (cleanAddress.isEmpty()) {
            clearStoredWallet();
            return;
        }
        prefs.edit()
            .putString(K_WALLET_NAME, cleanName)
            .putString(K_WALLET_ADDRESS, cleanAddress)
            .apply();
    }

    public void clearStoredWallet() {
        prefs.edit()
            .remove(K_WALLET_NAME)
            .remove(K_WALLET_ADDRESS)
            .remove(K_LAST_SNAPSHOT)
            .apply();
    }

    public WalletSnapshot getOverviewForStoredWallet() {
        StoredWallet wallet = getStoredWallet();
        return wallet == null ? WalletSnapshot.error("", "No stored Solana wallet") : getOverview(wallet.address);
    }

    public WalletSnapshot refreshStoredWallet() {
        StoredWallet wallet = getStoredWallet();
        return wallet == null ? WalletSnapshot.error("", "No stored Solana wallet") : refresh(wallet.address);
    }

    public WalletSnapshot getOverview(String address) {
        WalletSnapshot cached = getCachedSnapshot(address);
        if (cached != null) return cached;
        return refresh(address);
    }

    public WalletSnapshot refresh(String address) {
        String walletAddress = clean(address);
        if (walletAddress.isEmpty()) {
            return WalletSnapshot.error(walletAddress, "Missing Solana wallet address");
        }
        Exception lastError = null;
        for (String candidate : rpcCandidates(RPC_DEFAULT)) {
            try {
                JSONObject balanceResult = rpcRequest("getBalance", new JSONArray()
                    .put(walletAddress)
                    .put(new JSONObject().put("commitment", "confirmed")), candidate);
                JSONArray signatureResults = rpcRequest("getSignaturesForAddress", new JSONArray()
                    .put(walletAddress)
                    .put(new JSONObject().put("limit", 5)), candidate).optJSONArray("value");

                long lamports = 0L;
                if (balanceResult != null) lamports = balanceResult.optLong("value", 0L);
                JSONArray txs = new JSONArray();
                if (signatureResults != null) {
                    for (int i = 0; i < signatureResults.length() && i < 5; i++) {
                        JSONObject item = signatureResults.optJSONObject(i);
                        if (item == null) continue;
                        String signature = clean(item.optString("signature", ""));
                        if (signature.isEmpty()) continue;
                        JSONObject summary = fetchTransactionSummary(walletAddress, signature, item, candidate);
                        if (summary != null) txs.put(summary);
                    }
                }

                WalletSnapshot snapshot = new WalletSnapshot(
                    walletAddress,
                    "solana",
                    lamports,
                    solFromLamports(lamports),
                    isoNow(),
                    candidate,
                    txs,
                    ""
                );
                cacheSnapshot(snapshot);
                return snapshot;
            } catch (Exception e) {
                lastError = e;
            }
        }
        return WalletSnapshot.error(walletAddress, lastError == null ? "Wallet refresh failed" : safeMessage(lastError));
    }

    private JSONObject fetchTransactionSummary(String walletAddress, String signature, JSONObject fallback, String rpcUrl) {
        try {
            JSONObject tx = rpcRequest("getTransaction", new JSONArray()
                .put(signature)
                .put(new JSONObject()
                    .put("commitment", "confirmed")
                    .put("encoding", "jsonParsed")
                    .put("maxSupportedTransactionVersion", 0)), rpcUrl);
                if (tx != null) return normalizeTransactionSummary(walletAddress, tx, fallback);
        } catch (Exception ignored) {
        }
        JSONObject out = new JSONObject();
        putJson(out, "signature", signature);
        putJson(out, "slot", fallback == null ? 0 : fallback.optLong("slot", 0L));
        putJson(out, "blockTime", fallbackBlockTime(fallback));
        putJson(out, "confirmationStatus", fallback == null ? "confirmed" : clean(fallback.optString("confirmationStatus", "confirmed")));
        putJson(out, "ok", fallback == null || fallback.isNull("err"));
        putJson(out, "err", fallback == null ? JSONObject.NULL : fallback.opt("err"));
        putJson(out, "memo", fallback == null ? "" : clean(fallback.optString("memo", "")));
        putJson(out, "netLamports", 0L);
        putJson(out, "netSol", 0d);
        return out;
    }

    private JSONObject normalizeTransactionSummary(String walletAddress, JSONObject tx, JSONObject fallback) {
        JSONObject meta = tx == null ? null : tx.optJSONObject("meta");
        JSONObject transaction = tx == null ? null : tx.optJSONObject("transaction");
        JSONObject message = transaction == null ? null : transaction.optJSONObject("message");
        JSONArray accountKeys = message == null ? null : message.optJSONArray("accountKeys");
        int walletIndex = findAccountIndex(walletAddress, accountKeys);
        JSONArray preBalances = meta == null ? null : meta.optJSONArray("preBalances");
        JSONArray postBalances = meta == null ? null : meta.optJSONArray("postBalances");
        long pre = walletIndex >= 0 && preBalances != null ? preBalances.optLong(walletIndex, 0L) : 0L;
        long post = walletIndex >= 0 && postBalances != null ? postBalances.optLong(walletIndex, 0L) : 0L;
        long netLamports = post - pre;
        JSONArray sigs = transaction == null ? null : transaction.optJSONArray("signatures");
        String signature = sigs != null ? clean(sigs.optString(0, "")) : clean(tx == null ? "" : tx.optString("signature", ""));
        Object err = meta == null ? null : meta.opt("err");
        String confirmationStatus = clean(tx == null ? "" : tx.optString("confirmationStatus", ""));
        if (confirmationStatus.isEmpty()) confirmationStatus = err == null || err == JSONObject.NULL ? "confirmed" : "failed";
        long blockTime = tx == null ? 0L : tx.optLong("blockTime", 0L);
        if (blockTime <= 0L && fallback != null) blockTime = fallback.optLong("blockTime", 0L);
        JSONObject out = new JSONObject();
        putJson(out, "signature", signature);
        putJson(out, "slot", tx == null ? 0L : tx.optLong("slot", 0L));
        putJson(out, "blockTime", blockTime > 0L ? isoFromEpoch(blockTime) : "");
        putJson(out, "confirmationStatus", confirmationStatus);
        putJson(out, "ok", err == null || err == JSONObject.NULL);
        putJson(out, "err", err == null ? JSONObject.NULL : err);
        putJson(out, "memo", clean(tx == null ? "" : tx.optString("memo", "")));
        putJson(out, "netLamports", netLamports);
        putJson(out, "netSol", solFromLamports(netLamports));
        return out;
    }

    private int findAccountIndex(String address, JSONArray accountKeys) {
        if (accountKeys == null) return -1;
        String needle = clean(address);
        if (needle.isEmpty()) return -1;
        for (int i = 0; i < accountKeys.length(); i++) {
            Object item = accountKeys.opt(i);
            String key = "";
            if (item instanceof JSONObject) {
                key = clean(((JSONObject) item).optString("pubkey", ""));
            } else if (item != null) {
                key = clean(String.valueOf(item));
            }
            if (needle.equals(key)) return i;
        }
        return -1;
    }

    private JSONObject rpcRequest(String method, JSONArray params, String rpcUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(rpcUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        JSONObject body = new JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", String.valueOf(System.currentTimeMillis()))
            .put("method", method)
            .put("params", params == null ? new JSONArray() : params);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        int code = conn.getResponseCode();
        String responseText = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        JSONObject response = responseText.isEmpty() ? new JSONObject() : new JSONObject(responseText);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Solana RPC failed (" + code + ")");
        }
        if (!response.isNull("error")) {
            JSONObject error = response.optJSONObject("error");
            String message = error == null ? String.valueOf(response.opt("error")) : error.optString("message", "");
            throw new IllegalStateException(message == null || message.trim().isEmpty() ? "Unknown Solana RPC error" : message.trim());
        }
        Object result = response.opt("result");
        if (result instanceof JSONObject) return (JSONObject) result;
        if (result instanceof JSONArray) return new JSONObject().put("value", result);
        return new JSONObject();
    }

    private WalletSnapshot getCachedSnapshot(String address) {
        String raw = prefs.getString(K_LAST_SNAPSHOT, "");
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            WalletSnapshot snapshot = WalletSnapshot.fromJson(new JSONObject(raw));
            if (!clean(address).equals(snapshot.address)) return null;
            return snapshot;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void cacheSnapshot(WalletSnapshot snapshot) {
        if (snapshot == null || snapshot.address.isEmpty()) return;
        prefs.edit().putString(K_LAST_SNAPSHOT, snapshot.toJson().toString()).apply();
    }

    private String[] rpcCandidates(String preferred) {
        String first = clean(preferred);
        String[] values = new String[RPC_FALLBACKS.length + (first.isEmpty() ? 0 : 1)];
        int idx = 0;
        if (!first.isEmpty()) values[idx++] = first;
        for (String fallback : RPC_FALLBACKS) {
            String candidate = clean(fallback);
            boolean seen = false;
            for (int i = 0; i < idx; i++) {
                if (candidate.equals(values[i])) {
                    seen = true;
                    break;
                }
            }
            if (!seen && !candidate.isEmpty()) values[idx++] = candidate;
        }
        String[] out = new String[idx];
        System.arraycopy(values, 0, out, 0, idx);
        return out;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public static boolean isProbablySolanaAddress(String value) {
        String trimmed = clean(value);
        if (trimmed.length() < 32 || trimmed.length() > 64) return false;
        return trimmed.matches("^[1-9A-HJ-NP-Za-km-z]+$");
    }

    private static double solFromLamports(long lamports) {
        return lamports / 1_000_000_000d;
    }

    private static String isoNow() {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return fmt.format(new java.util.Date());
    }

    private static String isoFromEpoch(long epochSeconds) {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return fmt.format(new java.util.Date(epochSeconds * 1000L));
    }

    private static String fallbackBlockTime(JSONObject fallback) {
        long blockTime = fallback == null ? 0L : fallback.optLong("blockTime", 0L);
        return blockTime > 0L ? isoFromEpoch(blockTime) : "";
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

    private static String safeMessage(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().trim().isEmpty()) return "unknown error";
        return e.getMessage().trim();
    }

    public static final class WalletSnapshot {
        public final String address;
        public final String chain;
        public final long lamports;
        public final double balanceSol;
        public final String fetchedAt;
        public final String rpcSource;
        public final JSONArray recentTransactions;
        public final String lastError;

        WalletSnapshot(String address, String chain, long lamports, double balanceSol, String fetchedAt, String rpcSource, JSONArray recentTransactions, String lastError) {
            this.address = clean(address);
            this.chain = clean(chain);
            this.lamports = lamports;
            this.balanceSol = balanceSol;
            this.fetchedAt = clean(fetchedAt);
            this.rpcSource = clean(rpcSource);
            this.recentTransactions = recentTransactions == null ? new JSONArray() : recentTransactions;
            this.lastError = clean(lastError);
        }

        static WalletSnapshot error(String address, String lastError) {
            return new WalletSnapshot(address, clean(address).isEmpty() ? "" : "solana", 0L, 0d, "", "", new JSONArray(), clean(lastError));
        }

        JSONObject toJson() {
            JSONObject out = new JSONObject();
            putJson(out, "address", address);
            putJson(out, "chain", chain);
            putJson(out, "lamports", lamports);
            putJson(out, "balanceSol", balanceSol);
            putJson(out, "fetchedAt", fetchedAt);
            putJson(out, "rpcSource", rpcSource);
            putJson(out, "recentTransactions", recentTransactions);
            putJson(out, "lastError", lastError);
            return out;
        }

        static WalletSnapshot fromJson(JSONObject json) {
            if (json == null) return null;
            return new WalletSnapshot(
                json.optString("address", ""),
                json.optString("chain", ""),
                json.optLong("lamports", 0L),
                json.optDouble("balanceSol", 0d),
                json.optString("fetchedAt", ""),
                json.optString("rpcSource", ""),
                json.optJSONArray("recentTransactions"),
                json.optString("lastError", "")
            );
        }
    }

    private static void putJson(JSONObject obj, String key, Object value) {
        try {
            obj.put(key, value);
        } catch (Exception ignored) {
        }
    }

    public static final class StoredWallet {
        public final String name;
        public final String address;

        StoredWallet(String name, String address) {
            this.name = clean(name);
            this.address = clean(address);
        }
    }
}
