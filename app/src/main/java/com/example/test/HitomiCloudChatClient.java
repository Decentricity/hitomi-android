package ai.agent1c.hitomi;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class HitomiCloudChatClient {
    private static final String MODEL = "grok-4-latest";
    private static final String DIRECT_MODEL = "grok-4";
    private static final String DIRECT_XAI_CHAT_URL = "https://api.x.ai/v1/chat/completions";
    private static final double TEMPERATURE = 0.4;

    private final Context appContext;
    private final HitomiAuthManager authManager;
    private final SolanaWalletClient solanaWalletClient;
    private final String soulTemplate;
    private final String toolsText;

    public HitomiCloudChatClient(Context context) {
        this.appContext = context.getApplicationContext();
        this.authManager = new HitomiAuthManager(appContext);
        this.solanaWalletClient = new SolanaWalletClient(appContext);
        this.soulTemplate = readRawText(appContext, R.raw.soul_md);
        this.toolsText = readRawText(appContext, R.raw.tools_md);
    }

    public String send(JSONArray historyMessages, String userName) throws Exception {
        String systemPrompt = buildSystemPrompt(userName);
        JSONArray payloadMessages = new JSONArray();
        payloadMessages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        for (int i = 0; i < historyMessages.length(); i++) {
            payloadMessages.put(historyMessages.getJSONObject(i));
        }

        JSONObject body = new JSONObject();
        body.put("model", BuildConfig.IS_OPEN_VARIANT ? DIRECT_MODEL : MODEL);
        body.put("temperature", TEMPERATURE);
        body.put("messages", payloadMessages);

        if (BuildConfig.IS_OPEN_VARIANT) {
            String apiKey = authManager.getDirectApiKey();
            if (apiKey.isEmpty()) {
                throw new IllegalStateException("Please enter a Grok API key first.");
            }
            return sendDirectXai(body, apiKey, userName);
        }

        String accessToken = authManager.ensureValidAccessToken();
        if (accessToken == null || accessToken.isEmpty()) {
            throw new IllegalStateException("Please sign in first.");
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(HitomiAuthManager.CHAT_ENDPOINT_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("apikey", HitomiAuthManager.BACKEND_PUBLIC_KEY);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setDoOutput(true);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int code = conn.getResponseCode();
        String respText = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        JSONObject resp = respText.isEmpty() ? new JSONObject() : new JSONObject(respText);
        if (code < 200 || code >= 300) {
            String errMsg = "";
            JSONObject err = resp.optJSONObject("error");
            if (err != null) {
                String ecode = err.optString("code", "");
                String emsg = err.optString("message", "");
                errMsg = (ecode.isEmpty() ? "" : " code=" + ecode) + (emsg.isEmpty() ? "" : ": " + emsg);
            }
            if (code == 429) {
                return "I ran out of tokens, " + (userName == null || userName.isEmpty() ? "friend" : userName) + ". :(🐷 Let's talk again tomorrow. :)🦔";
            }
            throw new IllegalStateException("Cloud provider call failed (" + code + ")" + errMsg);
        }
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IllegalStateException("Cloud provider returned no message.");
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        String content = msg != null ? msg.optString("content", "") : "";
        if (content == null || content.trim().isEmpty()) throw new IllegalStateException("Cloud provider returned no message.");
        return content.trim();
    }

    private String sendDirectXai(JSONObject body, String apiKey, String userName) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(DIRECT_XAI_CHAT_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int code = conn.getResponseCode();
        String respText = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        JSONObject resp = respText.isEmpty() ? new JSONObject() : new JSONObject(respText);
        if (code < 200 || code >= 300) {
            String errMsg = "";
            JSONObject err = resp.optJSONObject("error");
            if (err != null) {
                String ecode = err.optString("code", "");
                String emsg = err.optString("message", "");
                errMsg = (ecode.isEmpty() ? "" : " code=" + ecode) + (emsg.isEmpty() ? "" : ": " + emsg);
            }
            if (code == 401) {
                throw new IllegalStateException("Grok API key rejected" + errMsg);
            }
            if (code == 429) {
                return "I ran out of tokens, " + (userName == null || userName.isEmpty() ? "friend" : userName) + ". :(🐷 Let's talk again tomorrow. :)🦔";
            }
            throw new IllegalStateException("Grok API call failed (" + code + ")" + errMsg);
        }
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new IllegalStateException("Grok returned no message.");
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        String content = msg != null ? msg.optString("content", "") : "";
        if (content == null || content.trim().isEmpty()) throw new IllegalStateException("Grok returned no message.");
        return content.trim();
    }

    private String buildSystemPrompt(String userName) {
        String safeName = (userName == null || userName.trim().isEmpty()) ? "friend" : userName.trim();
        StringBuilder prompt = new StringBuilder();
        prompt.append(soulTemplate.replace("{user_name}", safeName))
            .append("\n\n")
            .append(toolsText);
        SolanaWalletClient.StoredWallet storedWallet = solanaWalletClient.getStoredWallet();
        String walletAddress = storedWallet == null ? "" : storedWallet.address;
        if (walletAddress != null && !walletAddress.trim().isEmpty()) {
            prompt.append("\n\n")
                .append("Runtime note: the user is connected with Solana wallet \"")
                .append(walletAddress.trim())
                .append("\". You may inspect balance and recent transactions when needed.");
        }
        return prompt.toString();
    }

    private static String readRawText(Context context, int resId) {
        try (InputStream is = context.getResources().openRawResource(resId);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (!first) sb.append('\n');
                sb.append(line);
                first = false;
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
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
}
