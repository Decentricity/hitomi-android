package ai.agent1c.hitomi;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public class HitomiAuthManager {
    public static final String BACKEND_PUBLIC_KEY = "";
    public static final String CHAT_ENDPOINT_URL = "";

    private static final String PREFS = "agent1c_android_auth";
    private static final String K_DIRECT_API_KEY = "direct_api_key";

    private final SharedPreferences prefs;

    public HitomiAuthManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isSignedIn() {
        return !getDirectApiKey().isEmpty();
    }

    public String getDisplayName() {
        return "friend";
    }

    public String getEmail() {
        return "";
    }

    public String getProvider() {
        return "grok";
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

    public void signInWithDirectApiKey(String apiKey) {
        prefs.edit().putString(K_DIRECT_API_KEY, apiKey == null ? "" : apiKey.trim()).apply();
    }

    public void signOut() {
        prefs.edit().remove(K_DIRECT_API_KEY).apply();
    }

    public String buildWebAuthLaunchUrl(String provider) {
        return "";
    }

    public boolean handleAuthCallbackUri(Uri uri) {
        return false;
    }
}
