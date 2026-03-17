package ai.agent1c.hitomi;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.animation.ValueAnimator;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.os.Bundle;
import android.util.Log;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class HedgehogOverlayService extends Service {
    private static final String TAG = "HitomiOverlay";
    public static final String ACTION_START = "ai.agent1c.hitomi.START_OVERLAY";
    public static final String ACTION_STOP = "ai.agent1c.hitomi.STOP_OVERLAY";
    private static final String CHANNEL_ID = "hitomi_overlay_channel";
    private static final int NOTIF_ID = 1017;
    private static final int HEDGEHOG_SIZE_DP = 112;
    private static final int HEDGEHOG_TOUCH_BOX_DP = 124;
    private static final int QUICK_ACTION_X_COMPENSATE_DP = 24;
    private static final int BUBBLE_WIDTH_DP = 260;
    private static final int BUBBLE_X_OFFSET_DP = 74;
    private static final int BUBBLE_GAP_DP = 8;
    private static final int EDGE_TAB_WIDTH_DP = 56;
    private static final int EDGE_TAB_HEIGHT_DP = 112;
    private static final int EDGE_TAB_TOUCH_WIDTH_DP = 96;
    private static final int EDGE_TAB_TOUCH_HEIGHT_DP = 148;
    private static final int EDGE_TAB_VISIBLE_SLICE_DP = 10;
    private static final int EDGE_TAB_RESTORE_SWIPE_DP = 18;
    private static final String ANDROID_BROWSER_TOOL_NAME = "android_browser_open";
    private static final String ANDROID_BROWSER_BROWSE_TOOL_NAME = "android_browser_browse";
    private static final String ANDROID_TERMUX_EXEC_TOOL_NAME = "android_termux_exec";
    private static final String ANDROID_SOLANA_OVERVIEW_TOOL_NAME = "android_solana_wallet_overview";
    private static final String ANDROID_SOLANA_REFRESH_TOOL_NAME = "android_solana_wallet_refresh";

    private WindowManager windowManager;
    private View hedgehogView;
    private View bubbleView;
    private View browserView;
    private View solanaView;
    private TextView edgeTabView;
    private ParticleLinkView particleLinkView;
    private WindowManager.LayoutParams hedgehogParams;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams browserParams;
    private WindowManager.LayoutParams solanaParams;
    private WindowManager.LayoutParams edgeTabParams;
    private WindowManager.LayoutParams particleLinkParams;
    private boolean bubbleVisible = false;
    private boolean browserVisible = false;
    private boolean solanaVisible = false;
    private Runnable browserSummonParticlesStop;
    private final ExecutorService chatExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final JSONArray chatHistory = new JSONArray();
    private HitomiCloudChatClient chatClient;
    private TextView bubbleBodyView;
    private TextView browserUrlView;
    private EditText solanaWalletNameInput;
    private EditText solanaWalletAddressInput;
    private TextView solanaStatusView;
    private ScrollView bubbleBodyScrollView;
    private View bubbleTailTopView;
    private View bubbleTailBottomView;
    private EditText bubbleInputView;
    private ImageButton bubbleSendButton;
    private WebView hitomiBrowserWebView;
    private boolean chatInFlight = false;
    private String transcript = "";
    private boolean keyboardLiftActive = false;
    private int keyboardLiftOriginalY = -1;
    private boolean hedgehogDragging = false;
    private ValueAnimator hedgehogHopAnimator;
    private ValueAnimator hedgehogTravelAnimator;
    private boolean bubbleTailOnTop = false;
    private View quickActionsView;
    private ImageButton quickSettingsButton;
    private ImageButton quickMicButton;
    private ImageButton quickCloseButton;
    private ImageButton pinnedMicButton;
    private boolean quickActionsVisible = false;
    private boolean alwaysListeningEnabled = false;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean sttListening = false;
    private boolean sttRestartScheduled = false;
    private boolean sttRecognizerAvailable = false;
    private boolean sttPendingRestartAfterReply = false;
    private boolean sttSuppressUntilNextSession = false;
    private String sttPartialPreview = "";
    private long lastSttIssueToastAt = 0L;
    private String lastSttIssueKey = "";
    private boolean hedgehogHiddenAtEdge = false;
    private boolean hiddenEdgeRight = false;
    private int hiddenRestoreX = -1;
    private int hiddenRestoreY = -1;
    private BrowserReadRequest pendingBrowserReadRequest;
    private TermuxCommandBridge termuxCommandBridge;
    private SolanaWalletClient solanaWalletClient;
    private String pendingWalletPrompt = "";
    private float browserReadParticleProgress = 0f;
    private final Runnable sttRestartRunnable = new Runnable() {
        @Override public void run() {
            sttRestartScheduled = false;
            if (alwaysListeningEnabled && !chatInFlight) startSpeechListeningSession();
        }
    };
    private static volatile boolean overlayRunning = false;

    public static boolean isOverlayRunning() {
        return overlayRunning;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            overlayRunning = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        ensureOverlay();
        overlayRunning = true;
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mainHandler.post(this::refreshOverlayPositionsForViewportChange);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        overlayRunning = false;
        stopSpeechLoop(true);
        chatExecutor.shutdownNow();
        if (windowManager != null) {
            if (hedgehogView != null) {
                try { windowManager.removeView(hedgehogView); } catch (Exception ignored) {}
            }
            if (bubbleView != null) {
                try { windowManager.removeView(bubbleView); } catch (Exception ignored) {}
            }
            if (browserView != null) {
                try { windowManager.removeView(browserView); } catch (Exception ignored) {}
            }
            if (solanaView != null) {
                try { windowManager.removeView(solanaView); } catch (Exception ignored) {}
            }
            if (particleLinkView != null) {
                try { windowManager.removeView(particleLinkView); } catch (Exception ignored) {}
            }
            if (edgeTabView != null) {
                try { windowManager.removeView(edgeTabView); } catch (Exception ignored) {}
            }
        }
        if (hitomiBrowserWebView != null) {
            try {
                hitomiBrowserWebView.stopLoading();
                hitomiBrowserWebView.destroy();
            } catch (Exception ignored) {}
        }
        if (termuxCommandBridge != null) {
            try { termuxCommandBridge.shutdown(); } catch (Exception ignored) {}
            termuxCommandBridge = null;
        }
    }

    private void ensureOverlay() {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        if (hedgehogView != null) return;

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        hedgehogView = LayoutInflater.from(this).inflate(R.layout.overlay_hedgehog, null);
        bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null);
        browserView = LayoutInflater.from(this).inflate(R.layout.overlay_browser, null);
        solanaView = LayoutInflater.from(this).inflate(R.layout.overlay_solana, null);
        edgeTabView = buildEdgeTabView();
        particleLinkView = new ParticleLinkView(this);

        hedgehogParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        hedgehogParams.gravity = Gravity.TOP | Gravity.START;
        hedgehogParams.x = dp(18);
        hedgehogParams.y = dp(220);

        bubbleParams = new WindowManager.LayoutParams(
            dp(BUBBLE_WIDTH_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = dp(82);
        bubbleParams.y = dp(80);
        browserParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        browserParams.gravity = Gravity.TOP | Gravity.START;
        browserParams.x = dp(120);
        browserParams.y = dp(120);
        solanaParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        solanaParams.gravity = Gravity.TOP | Gravity.START;
        solanaParams.x = dp(132);
        solanaParams.y = dp(132);
        edgeTabParams = new WindowManager.LayoutParams(
            dp(EDGE_TAB_TOUCH_WIDTH_DP),
            dp(EDGE_TAB_TOUCH_HEIGHT_DP),
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        edgeTabParams.gravity = Gravity.TOP | Gravity.START;
        edgeTabParams.x = 0;
        edgeTabParams.y = hedgehogParams.y;
        particleLinkParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        );
        particleLinkParams.gravity = Gravity.TOP | Gravity.START;
        particleLinkParams.x = 0;
        particleLinkParams.y = 0;

        setupBubbleUi();
        setupBrowserUi();
        setupSolanaUi();
        setupQuickActionsUi();
        setupDragAndTap();

        windowManager.addView(browserView, browserParams);
        windowManager.addView(solanaView, solanaParams);
        windowManager.addView(particleLinkView, particleLinkParams);
        windowManager.addView(bubbleView, bubbleParams);
        windowManager.addView(hedgehogView, hedgehogParams);
        windowManager.addView(edgeTabView, edgeTabParams);
        particleLinkView.setVisibility(View.GONE);
        browserView.setVisibility(View.GONE);
        browserVisible = false;
        solanaView.setVisibility(View.GONE);
        solanaVisible = false;
        bubbleView.setVisibility(View.GONE);
        edgeTabView.setVisibility(View.GONE);
        chatClient = new HitomiCloudChatClient(this);
        termuxCommandBridge = new TermuxCommandBridge(this);
        solanaWalletClient = new SolanaWalletClient(this);
        initSpeechRecognizer();
        SupabaseAuthManager auth = new SupabaseAuthManager(this);
        if (auth.isSignedIn()) {
            String display = String.valueOf(auth.getDisplayName() == null ? "" : auth.getDisplayName()).trim();
            if (display.startsWith("@") && display.length() > 1) {
                transcript = "Hitomi: I'm a hedgey-hog! Hello " + display;
            } else {
                transcript = "Hitomi: Hello, I'm a hedgey-hog!";
            }
        } else {
            transcript = "Hitomi: Hi! I'm Hitomi, your tiny hedgehog friend. Sign in in the app, then we can chat here.";
        }
        renderTranscript(false);
    }

    private void refreshOverlayPositionsForViewportChange() {
        if (windowManager == null) return;
        if (hedgehogHiddenAtEdge) {
            positionEdgeTabForHiddenState();
            if (edgeTabView != null) {
                edgeTabView.setVisibility(View.VISIBLE);
                safeUpdate(edgeTabView, edgeTabParams);
            }
            return;
        }
        if (hedgehogParams != null && hedgehogView != null) {
            hedgehogParams.x = clamp(hedgehogParams.x, 0, Math.max(0, getScreenWidth() - dp(HEDGEHOG_TOUCH_BOX_DP)));
            hedgehogParams.y = clamp(hedgehogParams.y, 0, Math.max(0, getScreenHeight() - dp(HEDGEHOG_TOUCH_BOX_DP)));
            safeUpdate(hedgehogView, hedgehogParams);
        }
        if (bubbleVisible && bubbleParams != null && bubbleView != null) {
            positionBubbleNearHedgehog();
            safeUpdate(bubbleView, bubbleParams);
        }
        if (browserVisible && browserParams != null && browserView != null) {
            int browserW = (browserView.getWidth() > 0) ? browserView.getWidth() : dp(240);
            int browserH = (browserView.getHeight() > 0) ? browserView.getHeight() : dp(190);
            browserParams.x = clamp(browserParams.x, 0, Math.max(0, getScreenWidth() - browserW));
            browserParams.y = clamp(browserParams.y, 0, Math.max(0, getScreenHeight() - browserH));
            safeUpdate(browserView, browserParams);
        }
    }

    private void setupBrowserUi() {
        if (browserView == null) return;
        TextView title = browserView.findViewById(R.id.hitomiBrowserTitleText);
        browserUrlView = browserView.findViewById(R.id.hitomiBrowserUrl);
        hitomiBrowserWebView = browserView.findViewById(R.id.hitomiBrowserWebView);
        ImageButton close = browserView.findViewById(R.id.hitomiBrowserClose);
        View dragHandle = browserView.findViewById(R.id.hitomiBrowserTitlePill);
        if (title != null) title.setText("Hitomi Browser");
        if (close != null) close.setOnClickListener(v -> {
            browserVisible = false;
            browserView.setVisibility(View.GONE);
        });
        if (dragHandle != null) setupBrowserDrag(dragHandle);
        if (hitomiBrowserWebView != null) {
            WebSettings ws = hitomiBrowserWebView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setLoadsImagesAutomatically(true);
            ws.setBuiltInZoomControls(false);
            hitomiBrowserWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (browserUrlView != null && url != null) browserUrlView.setText(url);
                    maybeResolvePendingBrowserRead(view, url);
                }
            });
            if (browserUrlView != null) browserUrlView.setText("https://example.com");
            hitomiBrowserWebView.loadUrl("https://example.com");
        }
    }

    private void setupSolanaUi() {
        if (solanaView == null) return;
        TextView title = solanaView.findViewById(R.id.hitomiSolanaTitleText);
        solanaWalletNameInput = solanaView.findViewById(R.id.hitomiSolanaWalletName);
        solanaWalletAddressInput = solanaView.findViewById(R.id.hitomiSolanaWalletAddress);
        solanaStatusView = solanaView.findViewById(R.id.hitomiSolanaStatus);
        ImageButton close = solanaView.findViewById(R.id.hitomiSolanaClose);
        View dragHandle = solanaView.findViewById(R.id.hitomiSolanaTitlePill);
        View save = solanaView.findViewById(R.id.hitomiSolanaSave);
        if (title != null) title.setText("Solana");
        populateStoredSolanaWalletIntoWindow();
        if (close != null) close.setOnClickListener(v -> {
            solanaVisible = false;
            solanaView.setVisibility(View.GONE);
        });
        if (dragHandle != null) setupSolanaDrag(dragHandle);
        if (save != null) {
            save.setOnClickListener(v -> saveSolanaWalletFromWindow());
        }
    }

    private void showHitomiBrowserForUrl(String rawUrl) {
        if (browserView == null || browserParams == null || hitomiBrowserWebView == null) return;
        String url = normalizeBrowserUrl(rawUrl);
        if (url == null || url.isEmpty()) return;
        boolean firstShow = !browserVisible || browserView.getVisibility() != View.VISIBLE;
        if (firstShow) {
            positionBrowserNearHedgehog(true);
        }
        browserVisible = true;
        browserView.setVisibility(View.VISIBLE);
        if (firstShow) {
            playBrowserSummonAnimation();
        }
        safeUpdate(browserView, browserParams);
        if (browserUrlView != null) browserUrlView.setText(url);
        hitomiBrowserWebView.loadUrl(url);
    }

    private void showSolanaWindow(boolean randomized) {
        if (solanaView == null || solanaParams == null) return;
        boolean firstShow = !solanaVisible || solanaView.getVisibility() != View.VISIBLE;
        if (firstShow) {
            positionSolanaNearHedgehog(randomized);
        }
        populateStoredSolanaWalletIntoWindow();
        solanaVisible = true;
        solanaView.setVisibility(View.VISIBLE);
        if (firstShow) {
            solanaView.setAlpha(0f);
            solanaView.setScaleX(0.9f);
            solanaView.setScaleY(0.9f);
            solanaView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220L)
                .start();
        }
        safeUpdate(solanaView, solanaParams);
    }

    private void positionSolanaNearHedgehog(boolean randomized) {
        if (hedgehogParams == null || solanaParams == null) return;
        int screenW = getScreenWidth();
        int screenH = getScreenHeight();
        int solanaW = (solanaView != null && solanaView.getWidth() > 0) ? solanaView.getWidth() : dp(250);
        int solanaH = (solanaView != null && solanaView.getHeight() > 0) ? solanaView.getHeight() : dp(220);
        int gap = dp(10);
        int[] xCandidates = new int[] {
            hedgehogParams.x + dp(HEDGEHOG_TOUCH_BOX_DP) + gap,
            hedgehogParams.x - solanaW - gap
        };
        int[] yCandidates = new int[] {
            hedgehogParams.y - dp(16),
            hedgehogParams.y + dp(10),
            hedgehogParams.y - solanaH + dp(64)
        };
        int desiredX = xCandidates[0];
        int desiredY = yCandidates[0];
        if (randomized) {
            desiredX = xCandidates[(int) Math.floor(Math.random() * xCandidates.length)];
            desiredY = yCandidates[(int) Math.floor(Math.random() * yCandidates.length)];
        } else if (desiredX + solanaW > screenW - dp(8)) {
            desiredX = xCandidates[1];
        }
        solanaParams.x = clamp(desiredX, dp(4), Math.max(dp(4), screenW - solanaW - dp(4)));
        solanaParams.y = clamp(desiredY, 0, Math.max(0, screenH - solanaH));
    }

    private void populateStoredSolanaWalletIntoWindow() {
        if (solanaWalletClient == null) solanaWalletClient = new SolanaWalletClient(this);
        SolanaWalletClient.StoredWallet wallet = solanaWalletClient.getStoredWallet();
        if (solanaWalletNameInput != null) {
            solanaWalletNameInput.setText(wallet == null ? "" : wallet.name);
        }
        if (solanaWalletAddressInput != null) {
            solanaWalletAddressInput.setText(wallet == null ? "" : wallet.address);
        }
        if (solanaStatusView != null) {
            solanaStatusView.setText(wallet == null
                ? "Hitomi stores this locally on your phone for read-only balance and transaction checks."
                : "Stored wallet ready. Hitomi can use it for read-only balance and recent transaction checks.");
        }
    }

    private void saveSolanaWalletFromWindow() {
        if (solanaWalletClient == null) solanaWalletClient = new SolanaWalletClient(this);
        String walletName = solanaWalletNameInput == null ? "" : String.valueOf(solanaWalletNameInput.getText()).trim();
        String walletAddress = solanaWalletAddressInput == null ? "" : String.valueOf(solanaWalletAddressInput.getText()).trim();
        if (!SolanaWalletClient.isProbablySolanaAddress(walletAddress)) {
            if (solanaStatusView != null) {
                solanaStatusView.setText("That wallet address does not look like a valid Solana public address yet.");
            }
            Toast.makeText(this, "Enter a valid Solana public address.", Toast.LENGTH_SHORT).show();
            return;
        }
        solanaWalletClient.saveStoredWallet(walletName, walletAddress);
        if (solanaStatusView != null) {
            solanaStatusView.setText("Saved locally. Hitomi can use this wallet for read-only checks now.");
        }
        solanaVisible = false;
        if (solanaView != null) solanaView.setVisibility(View.GONE);
        appendTranscriptLine("Hitomi: I saved your Solana wallet, fren. Ask me about balance or recent transactions any time.");
        renderTranscript(false);
        if (pendingWalletPrompt != null && !pendingWalletPrompt.trim().isEmpty()) {
            String pending = pendingWalletPrompt.trim();
            pendingWalletPrompt = "";
            if (bubbleInputView != null) bubbleInputView.setText(pending);
            Toast.makeText(this, "Wallet saved. Tap send again and I'll check it.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Saved Solana wallet for Hitomi.", Toast.LENGTH_SHORT).show();
        }
    }

    private void positionBrowserNearHedgehog(boolean randomized) {
        if (hedgehogParams == null || browserParams == null) return;
        int screenW = getScreenWidth();
        int screenH = getScreenHeight();
        int browserW = (browserView != null && browserView.getWidth() > 0) ? browserView.getWidth() : dp(240);
        int browserH = (browserView != null && browserView.getHeight() > 0) ? browserView.getHeight() : dp(190);
        int gap = dp(10);
        int[] xCandidates = new int[] {
            hedgehogParams.x + dp(HEDGEHOG_TOUCH_BOX_DP) + gap,
            hedgehogParams.x - browserW - gap
        };
        int[] yCandidates = new int[] {
            hedgehogParams.y - dp(24),
            hedgehogParams.y + dp(8),
            hedgehogParams.y - browserH + dp(64)
        };
        int desiredX = xCandidates[0];
        int desiredY = yCandidates[0];
        if (randomized) {
            int xi = (int) Math.floor(Math.random() * xCandidates.length);
            int yi = (int) Math.floor(Math.random() * yCandidates.length);
            desiredX = xCandidates[Math.max(0, Math.min(xCandidates.length - 1, xi))];
            desiredY = yCandidates[Math.max(0, Math.min(yCandidates.length - 1, yi))];
        } else {
            if (desiredX + browserW > screenW - dp(8)) {
                desiredX = xCandidates[1];
            }
        }
        desiredX = clamp(desiredX, dp(4), Math.max(dp(4), screenW - browserW - dp(4)));
        desiredY = clamp(desiredY, 0, Math.max(0, screenH - browserH));
        browserParams.x = desiredX;
        browserParams.y = desiredY;
    }

    private void playBrowserSummonAnimation() {
        if (browserView == null) return;
        browserView.setAlpha(0f);
        browserView.setScaleX(0.9f);
        browserView.setScaleY(0.9f);
        browserView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(260L)
            .start();
        browserReadParticleProgress = 0.15f;
        if (particleLinkView != null) {
            particleLinkView.setVisibility(View.VISIBLE);
            particleLinkView.setParticleStreamActive(true);
        }
        if (browserSummonParticlesStop != null) {
            mainHandler.removeCallbacks(browserSummonParticlesStop);
        }
        browserSummonParticlesStop = () -> {
            browserSummonParticlesStop = null;
            if (pendingBrowserReadRequest == null && particleLinkView != null) {
                particleLinkView.setParticleStreamActive(false);
                particleLinkView.setVisibility(View.GONE);
            }
        };
        mainHandler.postDelayed(browserSummonParticlesStop, 520L);
    }

    private String normalizeBrowserUrl(String rawUrl) {
        if (rawUrl == null) return null;
        String url = rawUrl.trim();
        if (url.isEmpty()) return null;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*$")) return null;
        return "https://" + url;
    }

    private boolean shouldPromptForStoredWallet(String message) {
        String text = message == null ? "" : message.trim().toLowerCase();
        if (text.isEmpty()) return false;
        boolean walletRelated =
            text.contains("solana")
                || text.contains("wallet")
                || text.contains("balance")
                || text.contains("transaction")
                || text.contains("transactions")
                || text.contains("lamports")
                || text.contains("signature")
                || text.matches(".*\\bsol\\b.*");
        if (!walletRelated) return false;
        if (solanaWalletClient == null) solanaWalletClient = new SolanaWalletClient(this);
        return !solanaWalletClient.hasStoredWallet();
    }

    private void requestBrowserSnapshot(String rawUrl, BrowserReadCallback callback) {
        if (callback == null) return;
        String url = normalizeBrowserUrl(rawUrl);
        if (url == null || url.isEmpty() || hitomiBrowserWebView == null) {
            callback.onSnapshot(null);
            return;
        }
        if (pendingBrowserReadRequest != null && pendingBrowserReadRequest.timeoutRunnable != null) {
            mainHandler.removeCallbacks(pendingBrowserReadRequest.timeoutRunnable);
        }
        Runnable timeout = () -> {
            BrowserReadRequest req = pendingBrowserReadRequest;
            if (req == null) return;
            pendingBrowserReadRequest = null;
            stopBrowserReadParticles();
            req.callback.onSnapshot(null);
        };
        pendingBrowserReadRequest = new BrowserReadRequest(url, callback, timeout);
        startBrowserReadParticles();
        showHitomiBrowserForUrl(url);
        mainHandler.postDelayed(timeout, 18000);
    }

    private void maybeResolvePendingBrowserRead(WebView view, String finishedUrl) {
        BrowserReadRequest req = pendingBrowserReadRequest;
        if (req == null || view == null) return;
        // Resolve on first finished page after request; redirects are fine.
        pendingBrowserReadRequest = null;
        if (req.timeoutRunnable != null) mainHandler.removeCallbacks(req.timeoutRunnable);
        BrowserReadAccumulator acc = new BrowserReadAccumulator(finishedUrl, req.callback);
        mainHandler.postDelayed(() -> runBrowserReadStep(view, 0, acc), 450);
    }

    private void runBrowserReadStep(WebView view, int stepIndex, BrowserReadAccumulator acc) {
        if (view == null || acc == null) {
            if (acc != null && acc.callback != null) acc.callback.onSnapshot(null);
            return;
        }
        String js = "(function(){try{var t=(document.title||'').trim();var u=(location.href||'').trim();"
            + "var b=(document.body&&document.body.innerText?document.body.innerText:'').replace(/\\s+/g,' ').trim();"
            + "if(b.length>4200)b=b.slice(0,4200);"
            + "var y=(window.scrollY||window.pageYOffset||0);"
            + "var h=(window.innerHeight||document.documentElement.clientHeight||0);"
            + "var dh=Math.max(document.body?document.body.scrollHeight:0,document.documentElement?document.documentElement.scrollHeight:0);"
            + "return JSON.stringify({title:t,url:u,text:b,scrollY:y,innerH:h,docH:dh});}"
            + "catch(e){return JSON.stringify({title:'',url:'',text:'',scrollY:0,innerH:0,docH:0});}})();";
        view.evaluateJavascript(js, value -> {
            BrowserStepSnapshot step = parseBrowserStepSnapshotResult(value, acc.fallbackUrl);
            acc.accept(step);
            updateBrowserReadParticleProgress(step.scrollProgressRatio());
            boolean more = step.hasMoreScrollableContent();
            boolean canContinue = stepIndex < 3 && more;
            if (!canContinue) {
                stopBrowserReadParticles();
                acc.callback.onSnapshot(acc.toBrowserSnapshot());
                return;
            }
            int scrollBy = step.recommendedScrollAmountPx();
            String scrollJs = "(function(){try{window.scrollBy({top:" + scrollBy + ",behavior:'smooth'});}catch(e){try{window.scrollBy(0," + scrollBy + ");}catch(_){}}})();";
            view.evaluateJavascript(scrollJs, null);
            mainHandler.postDelayed(() -> runBrowserReadStep(view, stepIndex + 1, acc), 700);
        });
    }

    private void startBrowserReadParticles() {
        browserReadParticleProgress = 0f;
        if (particleLinkView != null) {
            particleLinkView.setVisibility(View.VISIBLE);
            particleLinkView.setParticleStreamActive(true);
        }
    }

    private void stopBrowserReadParticles() {
        if (particleLinkView != null) {
            particleLinkView.setParticleStreamActive(false);
            particleLinkView.setVisibility(View.GONE);
        }
    }

    private void updateBrowserReadParticleProgress(float progress) {
        browserReadParticleProgress = Math.max(0f, Math.min(1f, progress));
        if (particleLinkView != null) particleLinkView.invalidate();
    }

    private BrowserSnapshot parseBrowserSnapshotResult(String jsValue, String fallbackUrl) {
        try {
            // evaluateJavascript returns a JSON-encoded Java string
            String decoded = new JSONArray("[" + (jsValue == null ? "\"\"" : jsValue) + "]").getString(0);
            JSONObject obj = new JSONObject(decoded);
            String title = obj.optString("title", "");
            String url = obj.optString("url", "");
            String text = obj.optString("text", "");
            if ((url == null || url.isEmpty()) && fallbackUrl != null) url = fallbackUrl;
            return new BrowserSnapshot(title, url, text);
        } catch (Exception e) {
            return new BrowserSnapshot("", fallbackUrl == null ? "" : fallbackUrl, "");
        }
    }

    private BrowserStepSnapshot parseBrowserStepSnapshotResult(String jsValue, String fallbackUrl) {
        try {
            String decoded = new JSONArray("[" + (jsValue == null ? "\"\"" : jsValue) + "]").getString(0);
            JSONObject obj = new JSONObject(decoded);
            String title = obj.optString("title", "");
            String url = obj.optString("url", "");
            String text = obj.optString("text", "");
            int scrollY = obj.optInt("scrollY", 0);
            int innerH = obj.optInt("innerH", 0);
            int docH = obj.optInt("docH", 0);
            if ((url == null || url.isEmpty()) && fallbackUrl != null) url = fallbackUrl;
            return new BrowserStepSnapshot(title, url, text, scrollY, innerH, docH);
        } catch (Exception e) {
            return new BrowserStepSnapshot("", fallbackUrl == null ? "" : fallbackUrl, "", 0, 0, 0);
        }
    }

    private void setupBrowserDrag(View dragHandle) {
        final float[] downRaw = new float[2];
        final int[] downPos = new int[2];
        dragHandle.setOnTouchListener((v, event) -> {
            if (browserParams == null || browserView == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRaw[0] = event.getRawX();
                    downRaw[1] = event.getRawY();
                    downPos[0] = browserParams.x;
                    downPos[1] = browserParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - downRaw[0]);
                    int dy = (int) (event.getRawY() - downRaw[1]);
                    browserParams.x = clamp(downPos[0] + dx, 0, Math.max(0, getScreenWidth() - dp(240)));
                    browserParams.y = clamp(downPos[1] + dy, 0, Math.max(0, getScreenHeight() - dp(190)));
                    safeUpdate(browserView, browserParams);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        });
    }

    private void setupSolanaDrag(View dragHandle) {
        final float[] downRaw = new float[2];
        final int[] downPos = new int[2];
        dragHandle.setOnTouchListener((v, event) -> {
            if (solanaParams == null || solanaView == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRaw[0] = event.getRawX();
                    downRaw[1] = event.getRawY();
                    downPos[0] = solanaParams.x;
                    downPos[1] = solanaParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - downRaw[0]);
                    int dy = (int) (event.getRawY() - downRaw[1]);
                    int width = (solanaView.getWidth() > 0) ? solanaView.getWidth() : dp(250);
                    int height = (solanaView.getHeight() > 0) ? solanaView.getHeight() : dp(220);
                    solanaParams.x = clamp(downPos[0] + dx, 0, Math.max(0, getScreenWidth() - width));
                    solanaParams.y = clamp(downPos[1] + dy, 0, Math.max(0, getScreenHeight() - height));
                    safeUpdate(solanaView, solanaParams);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        });
    }

    private TextView buildEdgeTabView() {
        TextView v = new TextView(this);
        v.setText("");
        v.setGravity(Gravity.CENTER);
        v.setBackground(buildEdgeTabBackground(false));
        v.setClickable(true);
        v.setFocusable(false);
        v.setAlpha(0.95f);
        v.setOnClickListener(x -> restoreHedgehogFromEdge());
        final float[] downRaw = new float[2];
        final boolean[] restored = new boolean[1];
        v.setOnTouchListener((view, event) -> {
            if (!hedgehogHiddenAtEdge) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRaw[0] = event.getRawX();
                    downRaw[1] = event.getRawY();
                    restored[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRaw[0];
                    float dy = Math.abs(event.getRawY() - downRaw[1]);
                    boolean inwardSwipe = hiddenEdgeRight
                        ? (dx <= -dp(EDGE_TAB_RESTORE_SWIPE_DP))
                        : (dx >= dp(EDGE_TAB_RESTORE_SWIPE_DP));
                    if (!restored[0] && inwardSwipe && dy <= dp(40)) {
                        restored[0] = true;
                        restoreHedgehogFromEdge();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!restored[0]) {
                        view.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    restored[0] = false;
                    return true;
                default:
                    return false;
            }
        });
        return v;
    }

    private Drawable buildEdgeTabBackground(boolean alignRight) {
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(0xEEF4EFCB);
        oval.setStroke(dp(1), 0x887A775D);
        int horizontalInset = Math.max(0, dp(EDGE_TAB_TOUCH_WIDTH_DP - EDGE_TAB_WIDTH_DP));
        int verticalInset = Math.max(0, (dp(EDGE_TAB_TOUCH_HEIGHT_DP) - dp(EDGE_TAB_HEIGHT_DP)) / 2);
        if (alignRight) {
            return new InsetDrawable(oval, horizontalInset, verticalInset, 0, verticalInset);
        }
        return new InsetDrawable(oval, 0, verticalInset, horizontalInset, verticalInset);
    }

    private void setupBubbleUi() {
        bubbleBodyScrollView = bubbleView.findViewById(R.id.hitomiBubbleBodyScroll);
        bubbleBodyView = bubbleView.findViewById(R.id.hitomiBubbleBody);
        bubbleTailTopView = bubbleView.findViewById(R.id.hitomiBubbleTailTop);
        bubbleTailBottomView = bubbleView.findViewById(R.id.hitomiBubbleTailBottom);
        bubbleInputView = bubbleView.findViewById(R.id.hitomiBubbleInput);
        bubbleSendButton = bubbleView.findViewById(R.id.hitomiBubbleSend);
        ImageButton close = bubbleView.findViewById(R.id.hitomiBubbleClose);
        bubbleSendButton.setOnClickListener(v -> sendChatMessage());
        close.setOnClickListener(v -> toggleBubble(false));
        bubbleView.setOnTouchListener((v, event) -> {
            if (event == null) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                toggleBubble(false);
                return false;
            }
            return false;
        });
        bubbleInputView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                scheduleKeyboardAvoidanceHop();
            } else {
                restoreFromKeyboardHop(true);
            }
        });
        bubbleInputView.setOnClickListener(v -> scheduleKeyboardAvoidanceHop());
        bubbleView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (!bubbleVisible || bubbleInputView == null || !bubbleInputView.hasFocus()) return;
            if (hedgehogDragging) {
                scheduleKeyboardAvoidanceHop();
                return;
            }
            int screenH = getScreenHeight();
            int keyboardTop = getKeyboardTop(screenH);
            if (keyboardTop < screenH - dp(40)) {
                ensureKeyboardAvoidanceHop();
            }
        });
    }

    private void setupQuickActionsUi() {
        quickActionsView = hedgehogView.findViewById(R.id.hedgehogQuickActions);
        quickSettingsButton = hedgehogView.findViewById(R.id.hedgehogActionSettings);
        quickMicButton = hedgehogView.findViewById(R.id.hedgehogActionMic);
        quickCloseButton = hedgehogView.findViewById(R.id.hedgehogActionClose);
        pinnedMicButton = hedgehogView.findViewById(R.id.hedgehogPinnedMic);
        if (quickSettingsButton != null) {
            quickSettingsButton.setOnClickListener(v -> {
                showQuickActions(false);
                Intent open = new Intent(this, MainActivity.class);
                open.putExtra(MainActivity.EXTRA_FORCE_SHOW_MAIN, true);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(open);
            });
        }
        if (quickMicButton != null) {
            quickMicButton.setOnClickListener(v -> {
                toggleAlwaysListeningFromQuickAction();
            });
            updateQuickMicVisual();
        }
        if (pinnedMicButton != null) {
            pinnedMicButton.setOnClickListener(v -> toggleAlwaysListeningFromQuickAction());
        }
        if (quickCloseButton != null) {
            quickCloseButton.setOnClickListener(v -> {
                showQuickActions(false);
                toggleBubble(false);
                hideHedgehogToEdge();
            });
        }
        if (quickActionsView != null) {
            quickActionsView.bringToFront();
        }
        updateQuickMicVisual();
    }

    private void updateQuickMicVisual() {
        if (quickMicButton != null) {
            quickMicButton.setColorFilter(alwaysListeningEnabled ? 0xFF8E44AD : 0xFF333333);
            quickMicButton.setAlpha(alwaysListeningEnabled ? 1f : 0.88f);
        }
        if (pinnedMicButton != null) {
            pinnedMicButton.setColorFilter(alwaysListeningEnabled ? 0xFF8E44AD : 0xFF333333);
            pinnedMicButton.setAlpha(alwaysListeningEnabled ? 1f : 0.92f);
        }
        updatePinnedMicVisibility();
        updateHideActionIcon();
    }

    private void updatePinnedMicVisibility() {
        if (pinnedMicButton == null) return;
        boolean show = alwaysListeningEnabled && !quickActionsVisible && !hedgehogHiddenAtEdge;
        pinnedMicButton.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void updateHideActionIcon() {
        if (quickCloseButton == null || hedgehogParams == null) return;
        boolean right = isHedgehogCloserToRightEdge();
        hiddenEdgeRight = right;
        quickCloseButton.setImageResource(right
            ? android.R.drawable.ic_media_ff
            : android.R.drawable.ic_media_rew);
        quickCloseButton.setContentDescription(right ? "Hide to right edge" : "Hide to left edge");
    }

    private boolean isHedgehogCloserToRightEdge() {
        int centerX = hedgehogParams.x + (dp(HEDGEHOG_TOUCH_BOX_DP) / 2);
        return centerX >= (getScreenWidth() / 2);
    }

    private void toggleAlwaysListeningFromQuickAction() {
        showQuickActions(false);
        if (!alwaysListeningEnabled) {
            if (!ensureMicPermission()) {
                Toast.makeText(this, "Allow microphone permission in Hitomi app first.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (speechRecognizer == null) initSpeechRecognizer();
            if (!sttRecognizerAvailable) {
                Toast.makeText(this, "Speech recognition is unavailable on this device.", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        alwaysListeningEnabled = !alwaysListeningEnabled;
        updateQuickMicVisual();
        if (alwaysListeningEnabled) {
            sttSuppressUntilNextSession = false;
            scheduleSpeechRestart(40);
            Toast.makeText(this, "Always listening enabled", Toast.LENGTH_SHORT).show();
        } else {
            stopSpeechLoop(false);
            Toast.makeText(this, "Always listening disabled", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean ensureMicPermission() {
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
        if (granted) return true;
        Intent open = new Intent(this, MainActivity.class);
        open.putExtra(MainActivity.EXTRA_REQUEST_MIC_PERMISSION, true);
        open.putExtra(MainActivity.EXTRA_FORCE_SHOW_MAIN, true);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(open);
        noteSttIssue("mic_permission_request", "Opening Hitomi settings so Android can ask for mic permission.");
        return false;
    }

    private void initSpeechRecognizer() {
        if (speechRecognizer != null) return;
        try {
            sttRecognizerAvailable = SpeechRecognizer.isRecognitionAvailable(this);
            if (!sttRecognizerAvailable) {
                Log.w(TAG, "SpeechRecognizer.isRecognitionAvailable returned false");
                return;
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            Log.d(TAG, "Speech recognizer created");
            speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            speechIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    Log.d(TAG, "STT onReadyForSpeech");
                    sttListening = true;
                    sttPartialPreview = "";
                    renderTranscript(chatInFlight);
                }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {
                    Log.d(TAG, "STT onEndOfSpeech");
                    sttListening = false;
                }
                @Override public void onError(int error) {
                    Log.w(TAG, "STT onError " + speechErrorName(error) + " (" + error + ")");
                    sttListening = false;
                    sttPartialPreview = "";
                    renderTranscript(chatInFlight);
                    if (!alwaysListeningEnabled) return;
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        alwaysListeningEnabled = false;
                        updateQuickMicVisual();
                        noteSttIssue("mic_permission", "Microphone permission was lost. Re-enable it in the app.");
                        return;
                    }
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        recreateSpeechRecognizer();
                        scheduleSpeechRestart(420);
                        return;
                    }
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        scheduleSpeechRestart(180);
                        return;
                    }
                    if (error == SpeechRecognizer.ERROR_CLIENT
                        || error == SpeechRecognizer.ERROR_SERVER
                        || error == SpeechRecognizer.ERROR_NETWORK
                        || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                        recreateSpeechRecognizer();
                        noteSttIssue("mic_retry", "Mic had a hiccup. Retrying...");
                    }
                    scheduleSpeechRestart(500);
                }
                @Override public void onResults(Bundle results) {
                    Log.d(TAG, "STT onResults");
                    sttListening = false;
                    sttPartialPreview = "";
                    handleSpeechResults(results, true);
                }
                @Override public void onPartialResults(Bundle partialResults) {
                    if (partialResults == null) return;
                    java.util.ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches == null || matches.isEmpty()) return;
                    String partial = String.valueOf(matches.get(0)).trim();
                    if (partial.isEmpty()) return;
                    sttPartialPreview = "Listening: " + partial;
                    renderTranscript(chatInFlight);
                }
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize speech recognizer", e);
            sttRecognizerAvailable = false;
            speechRecognizer = null;
            noteSttIssue("mic_init_failed", "Speech recognition failed to initialize.");
        }
    }

    private void handleSpeechResults(Bundle results, boolean autoSend) {
        if (results == null) {
            if (alwaysListeningEnabled && !chatInFlight) scheduleSpeechRestart(180);
            return;
        }
        java.util.ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) {
            if (alwaysListeningEnabled && !chatInFlight) scheduleSpeechRestart(180);
            return;
        }
        String spoken = String.valueOf(matches.get(0)).trim();
        if (spoken.isEmpty()) {
            if (alwaysListeningEnabled && !chatInFlight) scheduleSpeechRestart(180);
            return;
        }
        if (chatInFlight) {
            sttPendingRestartAfterReply = true;
            scheduleSpeechRestart(420);
            return;
        }
        if (!bubbleVisible) toggleBubble(true);
        if (bubbleInputView != null) bubbleInputView.setText(spoken);
        if (autoSend) {
            sttPendingRestartAfterReply = alwaysListeningEnabled;
            sendChatMessage();
        } else if (alwaysListeningEnabled) {
            scheduleSpeechRestart(220);
        }
    }

    private void startSpeechListeningSession() {
        if (!alwaysListeningEnabled) return;
        if (chatInFlight) return;
        if (!ensureMicPermission()) return;
        if (!sttRecognizerAvailable || speechRecognizer == null || speechIntent == null) {
            if (speechRecognizer == null) initSpeechRecognizer();
            if (!sttRecognizerAvailable || speechRecognizer == null || speechIntent == null) {
                Log.w(TAG, "STT start skipped: recognizer unavailable");
                alwaysListeningEnabled = false;
                updateQuickMicVisual();
                noteSttIssue("mic_unavailable", "Speech recognition is unavailable right now.");
                return;
            }
        }
        if (sttListening) return;
        if (sttSuppressUntilNextSession) {
            sttSuppressUntilNextSession = false;
        }
        try {
            speechRecognizer.cancel();
        } catch (Exception e) {
            Log.w(TAG, "speechRecognizer.cancel failed before restart", e);
        }
        try {
            Log.d(TAG, "Calling speechRecognizer.startListening");
            speechRecognizer.startListening(speechIntent);
        } catch (Exception e) {
            Log.e(TAG, "speechRecognizer.startListening failed", e);
            recreateSpeechRecognizer();
            noteSttIssue("mic_start_failed", "Mic did not start. Retrying...");
            scheduleSpeechRestart(500);
        }
    }

    private void scheduleSpeechRestart(long delayMs) {
        if (!alwaysListeningEnabled) return;
        mainHandler.removeCallbacks(sttRestartRunnable);
        sttRestartScheduled = true;
        mainHandler.postDelayed(sttRestartRunnable, Math.max(40L, delayMs));
    }

    private void stopSpeechLoop(boolean destroyRecognizer) {
        mainHandler.removeCallbacks(sttRestartRunnable);
        sttRestartScheduled = false;
        sttListening = false;
        sttPartialPreview = "";
        try {
            if (speechRecognizer != null) speechRecognizer.cancel();
        } catch (Exception ignored) {}
        if (destroyRecognizer) {
            try {
                if (speechRecognizer != null) speechRecognizer.destroy();
            } catch (Exception ignored) {}
            speechRecognizer = null;
        }
        renderTranscript(chatInFlight);
    }

    private void recreateSpeechRecognizer() {
        try {
            if (speechRecognizer != null) speechRecognizer.destroy();
        } catch (Exception e) {
            Log.w(TAG, "speechRecognizer.destroy failed", e);
        }
        speechRecognizer = null;
        speechIntent = null;
        sttListening = false;
        initSpeechRecognizer();
    }

    private void noteSttIssue(String key, String message) {
        if (message == null || message.trim().isEmpty()) return;
        Log.w(TAG, "STT issue: " + key + " - " + message);
        long now = System.currentTimeMillis();
        boolean sameKey = key != null && key.equals(lastSttIssueKey);
        if (sameKey && (now - lastSttIssueToastAt) < 4000L) return;
        lastSttIssueKey = key == null ? "" : key;
        lastSttIssueToastAt = now;
        mainHandler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private static String speechErrorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "ERROR_AUDIO";
            case SpeechRecognizer.ERROR_CLIENT: return "ERROR_CLIENT";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "ERROR_INSUFFICIENT_PERMISSIONS";
            case SpeechRecognizer.ERROR_NETWORK: return "ERROR_NETWORK";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "ERROR_NETWORK_TIMEOUT";
            case SpeechRecognizer.ERROR_NO_MATCH: return "ERROR_NO_MATCH";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "ERROR_RECOGNIZER_BUSY";
            case SpeechRecognizer.ERROR_SERVER: return "ERROR_SERVER";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "ERROR_SPEECH_TIMEOUT";
            default: return "ERROR_" + error;
        }
    }

    private void showQuickActions(boolean show) {
        if (quickActionsVisible == show) return;
        if (quickActionsView == null) return;
        quickActionsVisible = show;
        int compensate = dp(QUICK_ACTION_X_COMPENSATE_DP);
        if (show) {
            hedgehogParams.x = clamp(
                hedgehogParams.x - compensate,
                0,
                Math.max(0, getScreenWidth() - dp(HEDGEHOG_TOUCH_BOX_DP))
            );
        } else {
            hedgehogParams.x = clamp(
                hedgehogParams.x + compensate,
                0,
                Math.max(0, getScreenWidth() - dp(HEDGEHOG_TOUCH_BOX_DP))
            );
        }
        positionBubbleNearHedgehog();
        safeUpdate(hedgehogView, hedgehogParams);
        if (bubbleVisible) safeUpdate(bubbleView, bubbleParams);
        if (show) {
            updateHideActionIcon();
            quickActionsView.bringToFront();
            quickActionsView.setVisibility(View.VISIBLE);
            View[] buttons = new View[]{ quickSettingsButton, quickMicButton, quickCloseButton };
            for (int i = 0; i < buttons.length; i++) {
                View btn = buttons[i];
                if (btn == null) continue;
                btn.animate().cancel();
                btn.setAlpha(0f);
                btn.setScaleX(0.7f);
                btn.setScaleY(0.7f);
                btn.setTranslationX(dp(8));
                btn.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationX(0f)
                    .setStartDelay(i * 22L)
                    .setDuration(130)
                    .start();
            }
            return;
        }
        View[] buttons = new View[]{ quickSettingsButton, quickMicButton, quickCloseButton };
        for (View btn : buttons) {
            if (btn == null) continue;
            btn.animate().cancel();
            btn.animate()
                .alpha(0f)
                .scaleX(0.75f)
                .scaleY(0.75f)
                .translationX(dp(6))
                .setDuration(90)
                .start();
        }
        quickActionsView.postDelayed(() -> {
            if (!quickActionsVisible && quickActionsView != null) {
                quickActionsView.setVisibility(View.GONE);
                for (View btn : buttons) {
                    if (btn == null) continue;
                    btn.setAlpha(1f);
                    btn.setScaleX(1f);
                    btn.setScaleY(1f);
                    btn.setTranslationX(0f);
                }
            }
        }, 100);
    }

    private void setupDragAndTap() {
        final float[] downRaw = new float[2];
        final int[] downPos = new int[2];
        final boolean[] dragging = new boolean[1];
        final boolean[] longPressed = new boolean[1];
        final Runnable[] longPressTrigger = new Runnable[1];
        View touchTarget = hedgehogView.findViewById(R.id.hedgehogImage);
        if (touchTarget == null) touchTarget = hedgehogView;
        final View finalTouchTarget = touchTarget;

        finalTouchTarget.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!isOpaqueTouchOnHedgehog(v, event)) {
                        return false;
                    }
                    dragging[0] = false;
                    hedgehogDragging = false;
                    longPressed[0] = false;
                    downRaw[0] = event.getRawX();
                    downRaw[1] = event.getRawY();
                    downPos[0] = hedgehogParams.x;
                    downPos[1] = hedgehogParams.y;
                    if (hedgehogHiddenAtEdge) return false;
                    if (longPressTrigger[0] != null) mainHandler.removeCallbacks(longPressTrigger[0]);
                    longPressTrigger[0] = () -> {
                        if (!dragging[0]) {
                            longPressed[0] = true;
                            showQuickActions(!quickActionsVisible);
                        }
                    };
                    mainHandler.postDelayed(longPressTrigger[0], 420);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - downRaw[0]);
                    int dy = (int) (event.getRawY() - downRaw[1]);
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        dragging[0] = true;
                        hedgehogDragging = true;
                        if (longPressTrigger[0] != null) mainHandler.removeCallbacks(longPressTrigger[0]);
                        if (quickActionsVisible) showQuickActions(false);
                    }
                    hedgehogParams.x = clamp(downPos[0] + dx, 0, Math.max(0, getScreenWidth() - dp(HEDGEHOG_TOUCH_BOX_DP)));
                    hedgehogParams.y = clamp(downPos[1] + dy, 0, Math.max(0, getScreenHeight() - dp(HEDGEHOG_TOUCH_BOX_DP)));
                    positionBubbleNearHedgehog();
                    safeUpdate(hedgehogView, hedgehogParams);
                    positionEdgeTabForHiddenState();
                    if (bubbleVisible) safeUpdate(bubbleView, bubbleParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (longPressTrigger[0] != null) mainHandler.removeCallbacks(longPressTrigger[0]);
                    hedgehogDragging = false;
                    if (longPressed[0]) return true;
                    if (!dragging[0]) {
                        if (quickActionsVisible) showQuickActions(false);
                        else toggleBubble(!bubbleVisible);
                    } else if (bubbleVisible && bubbleInputView != null && bubbleInputView.hasFocus()) {
                        scheduleKeyboardAvoidanceHop();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (longPressTrigger[0] != null) mainHandler.removeCallbacks(longPressTrigger[0]);
                    hedgehogDragging = false;
                    return true;
                default:
                    return false;
            }
        });
    }

    private boolean isOpaqueTouchOnHedgehog(View v, MotionEvent event) {
        if (!(v instanceof ImageView)) return true;
        ImageView image = (ImageView) v;
        Drawable drawable = image.getDrawable();
        if (!(drawable instanceof BitmapDrawable)) return true;
        BitmapDrawable bd = (BitmapDrawable) drawable;
        if (bd.getBitmap() == null) return true;
        android.graphics.Bitmap bmp = bd.getBitmap();
        if (bmp.getWidth() <= 0 || bmp.getHeight() <= 0) return true;

        float touchX = event.getX();
        float touchY = event.getY();
        int vw = image.getWidth();
        int vh = image.getHeight();
        int dw = drawable.getIntrinsicWidth();
        int dh = drawable.getIntrinsicHeight();
        if (vw <= 0 || vh <= 0 || dw <= 0 || dh <= 0) return true;

        float scale = Math.min((float) vw / (float) dw, (float) vh / (float) dh);
        float renderedW = dw * scale;
        float renderedH = dh * scale;
        float left = (vw - renderedW) / 2f;
        float top = (vh - renderedH) / 2f;
        if (touchX < left || touchY < top || touchX > left + renderedW || touchY > top + renderedH) {
            return false;
        }
        float normX = (touchX - left) / renderedW;
        float normY = (touchY - top) / renderedH;
        int px = Math.max(0, Math.min(bmp.getWidth() - 1, (int) (normX * bmp.getWidth())));
        int py = Math.max(0, Math.min(bmp.getHeight() - 1, (int) (normY * bmp.getHeight())));
        int alpha = (bmp.getPixel(px, py) >>> 24) & 0xFF;
        return alpha >= 24;
    }

    private void toggleBubble(boolean show) {
        if (hedgehogHiddenAtEdge && show) {
            restoreHedgehogFromEdge();
            return;
        }
        bubbleVisible = show;
        if (bubbleView == null) return;
        if (show) {
            showQuickActions(false);
            positionBubbleNearHedgehog();
            safeUpdate(bubbleView, bubbleParams);
            bubbleView.setVisibility(View.VISIBLE);
            if (bubbleInputView != null) {
                bubbleInputView.requestFocus();
                scheduleKeyboardAvoidanceHop();
            }
            bubbleView.post(this::ensureKeyboardAvoidanceHop);
            bubbleView.postDelayed(this::ensureKeyboardAvoidanceHop, 120);
        } else {
            if (bubbleInputView != null) bubbleInputView.clearFocus();
            bubbleView.setVisibility(View.GONE);
            restoreFromKeyboardHop(true);
        }
        updatePinnedMicVisibility();
    }

    private void positionBubbleNearHedgehog() {
        if (hedgehogHiddenAtEdge) return;
        int bubbleWidth = dp(BUBBLE_WIDTH_DP);
        int bubbleX = hedgehogParams.x - dp(BUBBLE_X_OFFSET_DP);
        int bubbleHeight = getBubbleMeasuredHeight();
        int screenH = getScreenHeight();
        int keyboardTop = getKeyboardTop(screenH);
        int imeBottom = getImeBottomInset();
        int hedgehogCenterY = hedgehogParams.y + (dp(HEDGEHOG_TOUCH_BOX_DP) / 2);
        boolean placeBelow = hedgehogCenterY < Math.round(screenH * 0.4f);
        if (imeBottom > 0 && placeBelow) {
            int belowBottom = hedgehogParams.y + dp(HEDGEHOG_TOUCH_BOX_DP) + dp(BUBBLE_GAP_DP) + bubbleHeight;
            if (belowBottom > keyboardTop - dp(6)) {
                placeBelow = false;
            }
        }
        int bubbleY = placeBelow
            ? hedgehogParams.y + dp(HEDGEHOG_TOUCH_BOX_DP) + dp(BUBBLE_GAP_DP)
            : hedgehogParams.y - bubbleHeight - dp(BUBBLE_GAP_DP);
        bubbleTailOnTop = placeBelow;
        updateBubbleTailPlacement();
        bubbleParams.x = clamp(bubbleX, 0, Math.max(0, getScreenWidth() - bubbleWidth));
        int bottomMax = Math.max(0, screenH - bubbleHeight);
        if (imeBottom > 0 && keyboardTop < screenH - dp(40)) {
            bottomMax = Math.max(0, Math.min(bottomMax, keyboardTop - bubbleHeight - dp(6)));
        }
        bubbleParams.y = clamp(bubbleY, 0, bottomMax);
        positionBubbleTailTowardHedgehog();
    }

    private void positionEdgeTabForHiddenState() {
        if (edgeTabView == null || edgeTabParams == null || hedgehogParams == null) return;
        int tabW = dp(EDGE_TAB_WIDTH_DP);
        int tabH = dp(EDGE_TAB_HEIGHT_DP);
        int touchW = dp(EDGE_TAB_TOUCH_WIDTH_DP);
        int touchH = dp(EDGE_TAB_TOUCH_HEIGHT_DP);
        int visibleSlice = dp(EDGE_TAB_VISIBLE_SLICE_DP);
        int insetW = Math.max(0, touchW - tabW);
        edgeTabParams.y = clamp(
            hedgehogParams.y + (dp(HEDGEHOG_TOUCH_BOX_DP) - touchH) / 2,
            0,
            Math.max(0, getScreenHeight() - touchH)
        );
        edgeTabParams.x = hiddenEdgeRight
            ? (getScreenWidth() - visibleSlice - insetW)
            : -(tabW - visibleSlice);
        edgeTabView.setBackground(buildEdgeTabBackground(hiddenEdgeRight));
    }

    private void updateBubbleTailPlacement() {
        if (bubbleTailTopView == null || bubbleTailBottomView == null) return;
        bubbleTailTopView.setVisibility(bubbleTailOnTop ? View.VISIBLE : View.GONE);
        bubbleTailBottomView.setVisibility(bubbleTailOnTop ? View.GONE : View.VISIBLE);
    }

    private void positionBubbleTailTowardHedgehog() {
        View tail = bubbleTailOnTop ? bubbleTailTopView : bubbleTailBottomView;
        if (tail == null) return;
        int bubbleWidth = dp(BUBBLE_WIDTH_DP);
        int tailWidth = tail.getWidth() > 0 ? tail.getWidth() : dp(18);
        int hedgehogHeadCenterX = hedgehogParams.x + (dp(HEDGEHOG_TOUCH_BOX_DP) / 2);
        int targetCenterWithinBubble = hedgehogHeadCenterX - bubbleParams.x;
        int defaultCenter = bubbleWidth / 2;
        int minCenter = dp(16) + (tailWidth / 2);
        int maxCenter = bubbleWidth - dp(16) - (tailWidth / 2);
        int clampedCenter = clamp(targetCenterWithinBubble, minCenter, maxCenter);
        float dx = clampedCenter - defaultCenter;
        tail.setTranslationX(dx);
    }

    private int getBubbleMeasuredHeight() {
        if (bubbleView == null) return dp(220);
        int widthSpec = View.MeasureSpec.makeMeasureSpec(dp(BUBBLE_WIDTH_DP), View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        try {
            bubbleView.measure(widthSpec, heightSpec);
            int h = bubbleView.getMeasuredHeight();
            if (h > 0) return h;
        } catch (Exception ignored) {
        }
        return dp(220);
    }

    private void safeUpdate(View view, WindowManager.LayoutParams lp) {
        if (windowManager == null || view == null) return;
        try {
            if (view.getWindowToken() != null) windowManager.updateViewLayout(view, lp);
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.putExtra(MainActivity.EXTRA_FORCE_SHOW_MAIN, true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, 0, openIntent, flags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Hitomi overlay running")
            .setContentText("Tap to manage the floating hedgehog.")
            .setContentIntent(pending)
            .setOngoing(true)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Hitomi Overlay",
            NotificationManager.IMPORTANCE_LOW
        );
        nm.createNotificationChannel(channel);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int getScreenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int getScreenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private void sendChatMessage() {
        if (chatInFlight || bubbleInputView == null) return;
        String msg = bubbleInputView.getText().toString().trim();
        if (msg.isEmpty()) return;
        bubbleInputView.setText("");
        if (shouldPromptForStoredWallet(msg)) {
            appendTranscriptLine("You: " + msg);
            appendTranscriptLine("Hitomi: I need your Solana wallet first, fren, so I popped open a purple Solana window for you. Fill in the wallet name and address, then tap OK.");
            pendingWalletPrompt = msg;
            renderTranscript(false);
            showSolanaWindow(true);
            return;
        }
        scheduleKeyboardAvoidanceHop();
        appendTranscriptLine("You: " + msg);
        chatInFlight = true;
        renderTranscript(true);
        if (bubbleSendButton != null) bubbleSendButton.setEnabled(false);

        chatExecutor.execute(() -> {
            String reply;
            String userName = "friend";
            try {
                SupabaseAuthManager auth = new SupabaseAuthManager(this);
                String resolved = auth.getDisplayName();
                if (resolved != null && !resolved.trim().isEmpty()) userName = resolved.trim();
                chatHistory.put(new JSONObject().put("role", "user").put("content", msg));
                reply = chatClient.send(chatHistory, userName);
            } catch (Exception e) {
                reply = "I hit a snag: " + safeMessage(e);
            }
            final ParsedAssistantReply parsedReply = parseAssistantReply(reply);
            try {
                chatHistory.put(new JSONObject().put("role", "assistant").put("content", parsedReply.visibleText));
            } catch (Exception ignored) {
            }
            final String finalUserName = userName;
            mainHandler.post(() -> {
                if (parsedReply.browserUrl != null && !parsedReply.browserUrl.isEmpty()) {
                    showHitomiBrowserForUrl(parsedReply.browserUrl);
                }
                appendTranscriptLine("Hitomi: " + parsedReply.visibleText);
                renderTranscript(true);
            });
            if (parsedReply.browserReadUrl != null && !parsedReply.browserReadUrl.isEmpty()) {
                String followup = runBrowserReadFollowup(finalUserName, parsedReply.browserReadUrl);
                if (followup != null && !followup.trim().isEmpty()) {
                    try {
                        chatHistory.put(new JSONObject().put("role", "assistant").put("content", followup));
                    } catch (Exception ignored) {
                    }
                    final String visibleFollowup = followup.trim();
                    mainHandler.post(() -> appendTranscriptLine("Hitomi: " + visibleFollowup));
                }
            }
            if (parsedReply.termuxCommand != null && !parsedReply.termuxCommand.isEmpty()) {
                String followup = runTermuxCommandFollowup(finalUserName, parsedReply.termuxCommand);
                if (followup != null && !followup.trim().isEmpty()) {
                    try {
                        chatHistory.put(new JSONObject().put("role", "assistant").put("content", followup));
                    } catch (Exception ignored) {
                    }
                    final String visibleFollowup = followup.trim();
                    mainHandler.post(() -> appendTranscriptLine("Hitomi: " + visibleFollowup));
                }
            }
            if (parsedReply.solanaToolName != null && !parsedReply.solanaToolName.isEmpty()) {
                boolean forceRefresh = ANDROID_SOLANA_REFRESH_TOOL_NAME.equals(parsedReply.solanaToolName);
                String followup = runSolanaWalletFollowup(finalUserName, forceRefresh);
                if (followup != null && !followup.trim().isEmpty()) {
                    try {
                        chatHistory.put(new JSONObject().put("role", "assistant").put("content", followup));
                    } catch (Exception ignored) {
                    }
                    final String visibleFollowup = followup.trim();
                    mainHandler.post(() -> appendTranscriptLine("Hitomi: " + visibleFollowup));
                }
            }
            mainHandler.post(() -> {
                chatInFlight = false;
                renderTranscript(false);
                if (bubbleSendButton != null) bubbleSendButton.setEnabled(true);
                if (alwaysListeningEnabled && sttPendingRestartAfterReply) {
                    sttPendingRestartAfterReply = false;
                    scheduleSpeechRestart(200);
                }
            });
        });
    }

    private void appendTranscriptLine(String line) {
        if (transcript == null || transcript.isEmpty()) transcript = line;
        else transcript = transcript + "\n\n" + line;
    }

    private ParsedAssistantReply parseAssistantReply(String raw) {
        String source = raw == null ? "" : raw.trim();
        if (source.isEmpty()) return new ParsedAssistantReply("", null, null, null, null);
        StringBuilder visible = new StringBuilder();
        List<String> browserUrls = new ArrayList<>();
        List<String> browserReadUrls = new ArrayList<>();
        List<String> termuxCommands = new ArrayList<>();
        List<String> solanaActions = new ArrayList<>();
        int idx = 0;
        while (idx < source.length()) {
            int start = source.indexOf("{{tool:", idx);
            if (start < 0) {
                visible.append(source.substring(idx));
                break;
            }
            visible.append(source, idx, start);
            int end = source.indexOf("}}", start);
            if (end < 0) {
                visible.append(source.substring(start));
                break;
            }
            String tokenBody = source.substring(start + 2, end).trim();
            maybeExtractAndroidTool(tokenBody, browserUrls, browserReadUrls, termuxCommands, solanaActions);
            idx = end + 2;
        }
        String cleaned = visible.toString()
            .replaceAll("[ \\t]+\\n", "\n")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
        if (cleaned.isEmpty() && !browserUrls.isEmpty()) {
            cleaned = "Opening the Hitomi Browser so you can watch me browse.";
        }
        if (cleaned.isEmpty() && !browserReadUrls.isEmpty()) {
            cleaned = "Opening the Hitomi Browser and reading the page for you.";
        }
        if (cleaned.isEmpty() && !termuxCommands.isEmpty()) {
            cleaned = "Running a Termux command for you now.";
        }
        if (cleaned.isEmpty() && !solanaActions.isEmpty()) {
            cleaned = ANDROID_SOLANA_REFRESH_TOOL_NAME.equals(solanaActions.get(0))
                ? "Refreshing your connected Solana wallet now."
                : "Checking your connected Solana wallet now.";
        }
        return new ParsedAssistantReply(
            cleaned,
            browserUrls.isEmpty() ? null : browserUrls.get(0),
            browserReadUrls.isEmpty() ? null : browserReadUrls.get(0),
            termuxCommands.isEmpty() ? null : termuxCommands.get(0),
            solanaActions.isEmpty() ? null : solanaActions.get(0)
        );
    }

    private void maybeExtractAndroidTool(String tokenBody, List<String> browserUrls, List<String> browserReadUrls, List<String> termuxCommands, List<String> solanaActions) {
        if (tokenBody == null || !tokenBody.startsWith("tool:")) return;
        String payload = tokenBody.substring("tool:".length());
        String[] parts = payload.split("\\|");
        if (parts.length == 0) return;
        String toolName = parts[0].trim();
        boolean openOnly = ANDROID_BROWSER_TOOL_NAME.equals(toolName);
        boolean browseRead = ANDROID_BROWSER_BROWSE_TOOL_NAME.equals(toolName);
        boolean termuxExec = ANDROID_TERMUX_EXEC_TOOL_NAME.equals(toolName);
        boolean solanaOverview = ANDROID_SOLANA_OVERVIEW_TOOL_NAME.equals(toolName);
        boolean solanaRefresh = ANDROID_SOLANA_REFRESH_TOOL_NAME.equals(toolName);
        if (!openOnly && !browseRead && !termuxExec && !solanaOverview && !solanaRefresh) return;
        if (solanaOverview || solanaRefresh) {
            solanaActions.add(toolName);
            return;
        }
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).trim();
            String val = part.substring(eq + 1).trim();
            if (termuxExec) {
                if (!"cmd".equalsIgnoreCase(key)) continue;
                String cmd = sanitizeTermuxCommand(val);
                if (cmd != null && !cmd.isEmpty()) termuxCommands.add(cmd);
                return;
            } else {
                if (!"url".equalsIgnoreCase(key)) continue;
                String normalized = normalizeBrowserUrl(val);
                if (normalized != null && !normalized.isEmpty()) {
                    if (browseRead) browserReadUrls.add(normalized);
                    else browserUrls.add(normalized);
                }
                return;
            }
        }
    }

    private String runBrowserReadFollowup(String userName, String browserReadUrl) {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            final BrowserSnapshot[] holder = new BrowserSnapshot[1];
            mainHandler.post(() -> requestBrowserSnapshot(browserReadUrl, snapshot -> {
                holder[0] = snapshot;
                latch.countDown();
            }));
            latch.await(15, TimeUnit.SECONDS);
            BrowserSnapshot snapshot = holder[0];
            if (snapshot == null) {
                return "I opened the Hitomi Browser, but I couldn't read the page yet. Please try again.";
            }
            String toolResult = buildBrowserSnapshotToolResult(snapshot);
            try {
                chatHistory.put(new JSONObject().put("role", "user").put("content", toolResult));
            } catch (Exception ignored) {
            }
            return chatClient.send(chatHistory, userName);
        } catch (Exception e) {
            return "I opened the Hitomi Browser, but I hit a snag reading the page: " + safeMessage(e);
        }
    }

    private String runTermuxCommandFollowup(String userName, String command) {
        try {
            String trimmed = command == null ? "" : command.trim();
            if (trimmed.isEmpty()) {
                return "I tried to run a Termux command, but the command was empty.";
            }
            String blockedReason = getBlockedTermuxCommandReason(trimmed);
            if (blockedReason != null) {
                String toolResult = "[ANDROID_TERMUX_SHELL]\n"
                    + "Hitomi tried to use a Termux shell command, but Android safety rules blocked it.\n"
                    + "Command: " + trimmed + "\n"
                    + "Reason: " + blockedReason + "\n"
                    + "[/ANDROID_TERMUX_SHELL]\n"
                    + "Tell the user briefly what was blocked and suggest a safer command or ask them to run it manually in Termux.";
                try {
                    chatHistory.put(new JSONObject().put("role", "user").put("content", toolResult));
                } catch (Exception ignored) {
                }
                return chatClient.send(chatHistory, userName);
            }
            if (termuxCommandBridge == null) {
                termuxCommandBridge = new TermuxCommandBridge(this);
            }
            if (termuxCommandBridge == null || !termuxCommandBridge.isTermuxInstalled()) {
                return "I can use Termux shell tools here, but Termux is not installed yet. Please install Termux and Termux:API first, then tap Enable Termux Shell Tools.";
            }
            CountDownLatch latch = new CountDownLatch(1);
            final TermuxCommandBridge.Result[] holder = new TermuxCommandBridge.Result[1];
            termuxCommandBridge.runCommand(
                "/data/data/com.termux/files/usr/bin/sh",
                new String[]{"-lc", trimmed},
                null,
                result -> {
                    holder[0] = result;
                    latch.countDown();
                }
            );
            latch.await(18, TimeUnit.SECONDS);
            TermuxCommandBridge.Result result = holder[0];
            if (result == null) {
                return "I tried a Termux command, but I did not get a result back in time.";
            }
            String setupFallback = buildTermuxSetupFallbackIfNeeded(result);
            if (setupFallback != null) {
                return setupFallback;
            }
            String toolResult = buildTermuxToolResult(trimmed, result);
            try {
                chatHistory.put(new JSONObject().put("role", "user").put("content", toolResult));
            } catch (Exception ignored) {
            }
            return chatClient.send(chatHistory, userName);
        } catch (Exception e) {
            return "I hit a snag running a Termux command: " + safeMessage(e);
        }
    }

    private String buildTermuxSetupFallbackIfNeeded(TermuxCommandBridge.Result result) {
        if (result == null) return null;
        String errMsg = result.errorMessage == null ? "" : result.errorMessage;
        String stderr = result.stderr == null ? "" : result.stderr;
        String combined = (errMsg + "\n" + stderr).toLowerCase();
        boolean needsExternalAppsSetup =
            combined.contains("allow-external-apps")
                || combined.contains("termux.properties")
                || combined.contains("runcommandservice requires");
        if (!needsExternalAppsSetup) return null;
        return "I can run Termux commands here, fren, but Termux still needs one setup step. "
            + "In the app, tap Enable Termux Shell Tools, tap Open Termux, run the setup command shown in the code box, "
            + "then fully close and reopen Termux and tap Test Termux Command again.";
    }

    private String buildTermuxToolResult(String command, TermuxCommandBridge.Result result) {
        String stdout = result.stdout == null ? "" : result.stdout.trim();
        String stderr = result.stderr == null ? "" : result.stderr.trim();
        String errMsg = result.errorMessage == null ? "" : result.errorMessage.trim();
        if (stdout.length() > 2400) stdout = stdout.substring(0, 2400);
        if (stderr.length() > 1200) stderr = stderr.substring(0, 1200);
        if (errMsg.length() > 400) errMsg = errMsg.substring(0, 400);
        return "[ANDROID_TERMUX_SHELL]\n"
            + "Hitomi used Termux to run a Linux-like command inside her Android app.\n"
            + "Command: " + command + "\n"
            + "Exit code: " + result.exitCode + "\n"
            + (result.timedOut ? "Timed out: true\n" : "")
            + (errMsg.isEmpty() ? "" : "Error message: " + errMsg + "\n")
            + "STDOUT:\n" + (stdout.isEmpty() ? "(empty)" : stdout) + "\n"
            + "STDERR:\n" + (stderr.isEmpty() ? "(empty)" : stderr) + "\n"
            + "[/ANDROID_TERMUX_SHELL]\n"
            + "Use the shell result to answer the user briefly and honestly.";
    }

    private String runSolanaWalletFollowup(String userName, boolean forceRefresh) {
        try {
            if (solanaWalletClient == null) {
                solanaWalletClient = new SolanaWalletClient(this);
            }
            SolanaWalletClient.StoredWallet wallet = solanaWalletClient.getStoredWallet();
            if (wallet == null || wallet.address.isEmpty()) {
                mainHandler.post(() -> showSolanaWindow(true));
                return "I don't have a stored Solana wallet yet, so I opened the purple Solana window for you.";
            }
            SolanaWalletClient.WalletSnapshot snapshot = forceRefresh
                ? solanaWalletClient.refresh(wallet.address)
                : solanaWalletClient.getOverview(wallet.address);
            String toolResult = buildSolanaWalletToolResult(snapshot, forceRefresh);
            try {
                chatHistory.put(new JSONObject().put("role", "user").put("content", toolResult));
            } catch (Exception ignored) {
            }
            return chatClient.send(chatHistory, userName);
        } catch (Exception e) {
            return "I hit a snag checking your Solana wallet: " + safeMessage(e);
        }
    }

    private String buildSolanaWalletToolResult(SolanaWalletClient.WalletSnapshot snapshot, boolean refreshed) {
        String address = snapshot == null ? "" : safe(snapshot.address);
        String fetchedAt = snapshot == null ? "" : safe(snapshot.fetchedAt);
        String rpcSource = snapshot == null ? "" : safe(snapshot.rpcSource);
        String lastError = snapshot == null ? "" : safe(snapshot.lastError);
        long lamports = snapshot == null ? 0L : snapshot.lamports;
        double balanceSol = snapshot == null ? 0d : snapshot.balanceSol;
        JSONArray txs = snapshot == null ? null : snapshot.recentTransactions;
        StringBuilder out = new StringBuilder();
        out.append("[ANDROID_SOLANA_WALLET]\n");
        out.append("Address: ").append(address.isEmpty() ? "(none)" : address).append("\n");
        out.append("Chain: solana\n");
        out.append("Balance SOL: ").append(balanceSol).append("\n");
        out.append("Lamports: ").append(lamports).append("\n");
        out.append("Fetched at: ").append(fetchedAt.isEmpty() ? "(not fetched)" : fetchedAt).append("\n");
        out.append("RPC source: ").append(rpcSource.isEmpty() ? "(none)" : rpcSource).append("\n");
        if (refreshed) out.append("Refresh: true\n");
        if (!lastError.isEmpty()) out.append("Error: ").append(lastError).append("\n");
        if (txs == null || txs.length() == 0) {
            out.append("Recent transactions: none\n");
        } else {
            out.append("Recent transactions:\n");
            for (int i = 0; i < txs.length() && i < 5; i++) {
                JSONObject tx = txs.optJSONObject(i);
                if (tx == null) continue;
                out.append("- signature=").append(safe(tx.optString("signature", ""))).append("\n");
                out.append("  status=").append(safe(tx.optString("confirmationStatus", ""))).append("\n");
                out.append("  block_time=").append(safe(tx.optString("blockTime", ""))).append("\n");
                out.append("  net_sol_change=").append(tx.optDouble("netSol", 0d)).append("\n");
            }
        }
        out.append("[/ANDROID_SOLANA_WALLET]\n");
        out.append("Use this wallet result to answer the user briefly and honestly. Do not emit another tool call unless the user explicitly asks to refresh again.");
        return out.toString();
    }

    private String getBlockedTermuxCommandReason(String command) {
        String c = command == null ? "" : command.trim().toLowerCase();
        if (c.isEmpty()) return "empty command";
        if (c.contains("rm -rf") || c.contains("rm -fr")) return "destructive deletion is blocked";
        if (c.startsWith("su") || c.startsWith("sudo") || c.contains(" sudo ")) return "privilege escalation is blocked";
        if (c.startsWith("reboot") || c.startsWith("shutdown")) return "device power commands are blocked";
        if (c.startsWith("kill ") || c.startsWith("pkill ") || c.contains(" killall ")) return "process-kill commands are blocked";
        if (c.contains("apt install") || c.contains("pkg install") || c.contains("apt upgrade") || c.contains("pkg upgrade"))
            return "package installs/upgrades require explicit user action";
        if (c.contains("> /dev/block") || c.contains("dd if=") || c.contains("mkfs")) return "disk/device modification commands are blocked";
        return null;
    }

    private String sanitizeTermuxCommand(String raw) {
        if (raw == null) return null;
        String cmd = raw.trim();
        if (cmd.isEmpty()) return null;
        if (cmd.length() > 240) cmd = cmd.substring(0, 240);
        return cmd;
    }

    private String buildBrowserSnapshotToolResult(BrowserSnapshot snapshot) {
        String title = snapshot.title == null ? "" : snapshot.title.trim();
        String url = snapshot.url == null ? "" : snapshot.url.trim();
        String text = snapshot.text == null ? "" : snapshot.text.trim();
        if (text.length() > 3500) text = text.substring(0, 3500);
        return "[ANDROID_BROWSER_PAGE]\n"
            + "Hitomi Browser is visible and loaded.\n"
            + "URL: " + url + "\n"
            + "Title: " + title + "\n"
            + "Visible text excerpt:\n" + text + "\n"
            + "[/ANDROID_BROWSER_PAGE]\n"
            + "Use this page excerpt to answer the user. If the excerpt is insufficient, say so briefly.";
    }

    private static final class ParsedAssistantReply {
        final String visibleText;
        final String browserUrl;
        final String browserReadUrl;
        final String termuxCommand;
        final String solanaToolName;
        ParsedAssistantReply(String visibleText, String browserUrl, String browserReadUrl, String termuxCommand, String solanaToolName) {
            this.visibleText = (visibleText == null || visibleText.trim().isEmpty())
                ? "Okay."
                : visibleText.trim();
            this.browserUrl = browserUrl;
            this.browserReadUrl = browserReadUrl;
            this.termuxCommand = termuxCommand;
            this.solanaToolName = solanaToolName;
        }
    }

    private interface BrowserReadCallback {
        void onSnapshot(BrowserSnapshot snapshot);
    }

    private static final class BrowserReadRequest {
        final String requestedUrl;
        final BrowserReadCallback callback;
        final Runnable timeoutRunnable;
        BrowserReadRequest(String requestedUrl, BrowserReadCallback callback, Runnable timeoutRunnable) {
            this.requestedUrl = requestedUrl;
            this.callback = callback;
            this.timeoutRunnable = timeoutRunnable;
        }
    }

    private static final class BrowserSnapshot {
        final String title;
        final String url;
        final String text;
        BrowserSnapshot(String title, String url, String text) {
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
            this.text = text == null ? "" : text;
        }
    }

    private static final class BrowserStepSnapshot {
        final String title;
        final String url;
        final String text;
        final int scrollY;
        final int innerH;
        final int docH;

        BrowserStepSnapshot(String title, String url, String text, int scrollY, int innerH, int docH) {
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
            this.text = text == null ? "" : text;
            this.scrollY = Math.max(0, scrollY);
            this.innerH = Math.max(0, innerH);
            this.docH = Math.max(0, docH);
        }

        boolean hasMoreScrollableContent() {
            if (innerH <= 0 || docH <= 0) return false;
            return (scrollY + innerH + 24) < docH;
        }

        int recommendedScrollAmountPx() {
            if (innerH <= 0) return 480;
            return Math.max(180, (int) (innerH * 0.78f));
        }

        float scrollProgressRatio() {
            int denom = Math.max(1, docH - innerH);
            return Math.max(0f, Math.min(1f, scrollY / (float) denom));
        }
    }

    private static final class BrowserReadAccumulator {
        final String fallbackUrl;
        final BrowserReadCallback callback;
        String title = "";
        String url = "";
        final StringBuilder text = new StringBuilder();

        BrowserReadAccumulator(String fallbackUrl, BrowserReadCallback callback) {
            this.fallbackUrl = fallbackUrl == null ? "" : fallbackUrl;
            this.callback = callback;
        }

        void accept(BrowserStepSnapshot step) {
            if (step == null) return;
            if (!step.title.isEmpty()) this.title = step.title;
            if (!step.url.isEmpty()) this.url = step.url;
            appendChunk(step.text);
        }

        private void appendChunk(String chunk) {
            if (chunk == null) return;
            String c = chunk.trim();
            if (c.isEmpty()) return;
            String current = text.toString();
            if (current.contains(c)) return;
            if (!current.isEmpty()) text.append("\n\n");
            text.append(c);
            if (text.length() > 9000) {
                text.setLength(9000);
            }
        }

        BrowserSnapshot toBrowserSnapshot() {
            String finalUrl = (url == null || url.isEmpty()) ? fallbackUrl : url;
            return new BrowserSnapshot(title, finalUrl, text.toString());
        }
    }

    private final class ParticleLinkView extends View {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean streamActive = false;
        private float phase = 0f;
        private final Runnable ticker = new Runnable() {
            @Override public void run() {
                if (!streamActive) return;
                phase += 0.11f;
                invalidate();
                mainHandler.postDelayed(this, 33);
            }
        };

        ParticleLinkView(Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeWidth(dp(2));
            linePaint.setColor(0x88DCCBFF);
            dotPaint.setStyle(Paint.Style.FILL);
        }

        void setParticleStreamActive(boolean active) {
            if (streamActive == active) return;
            streamActive = active;
            mainHandler.removeCallbacks(ticker);
            if (active) {
                phase = 0f;
                invalidate();
                mainHandler.post(ticker);
            } else {
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!streamActive || hedgehogParams == null || browserParams == null || !browserVisible) return;

            int box = dp(HEDGEHOG_TOUCH_BOX_DP);
            float hx = hedgehogParams.x + box * 0.22f;
            float hy = hedgehogParams.y + box * 0.46f;

            int bw = (browserView != null && browserView.getWidth() > 0) ? browserView.getWidth() : dp(240);
            int bh = (browserView != null && browserView.getHeight() > 0) ? browserView.getHeight() : dp(190);
            float bx = browserParams.x + bw * 0.08f;
            float topY = browserParams.y + dp(24);
            float bottomY = browserParams.y + bh - dp(20);
            float by = bottomY - (bottomY - topY) * browserReadParticleProgress;

            canvas.drawLine(hx, hy, bx, by, linePaint);

            final int dotCount = 13;
            for (int i = 0; i < dotCount; i++) {
                float t = (i / (float) (dotCount - 1) + phase) % 1f;
                float x = hx + (bx - hx) * t;
                float y = hy + (by - hy) * t;
                float centerWeight = 1f - Math.abs(0.5f - t) * 2f;
                float alpha = 0.35f + 0.65f * centerWeight;
                int a = Math.max(0, Math.min(255, (int) (alpha * 255f)));
                float warmMix = 1f - t;
                int rC = (int) (0xD9 * (1f - warmMix) + 0xFF * warmMix);
                int gC = (int) (0xF7 * (1f - warmMix) + 0xE7 * warmMix);
                int bC = (int) (0xFF * (1f - warmMix) + 0xA8 * warmMix);
                dotPaint.setColor((a << 24) | (rC << 16) | (gC << 8) | bC);
                float pulse = 0.5f + 0.5f * (float) Math.sin((t * 6.28318f) + (phase * 2.7f));
                float r = dp(2) + dp(2) * pulse;
                canvas.drawCircle(x, y, r, dotPaint);
            }
        }
    }

    private void renderTranscript(boolean thinking) {
        if (bubbleBodyView == null) return;
        String text = transcript == null ? "" : transcript;
        if (alwaysListeningEnabled) {
            text = text + (text.isEmpty() ? "" : "\n\n") + "Listening...";
            if (sttPartialPreview != null && !sttPartialPreview.isEmpty()) {
                text = text + "\n" + sttPartialPreview;
            }
        } else if (sttPartialPreview != null && !sttPartialPreview.isEmpty()) {
            text = text + (text.isEmpty() ? "" : "\n\n") + sttPartialPreview;
        }
        if (thinking) text = text + "\n\nThinking...";
        bubbleBodyView.setText(text);
        if (bubbleBodyScrollView != null) {
            bubbleBodyScrollView.post(() -> bubbleBodyScrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void scheduleKeyboardAvoidanceHop() {
        mainHandler.postDelayed(this::ensureKeyboardAvoidanceHop, 80);
        mainHandler.postDelayed(this::ensureKeyboardAvoidanceHop, 220);
    }

    private void ensureKeyboardAvoidanceHop() {
        if (hedgehogView == null || hedgehogParams == null) return;
        if (hedgehogDragging) {
            mainHandler.postDelayed(this::ensureKeyboardAvoidanceHop, 120);
            return;
        }
        int screenH = getScreenHeight();
        int keyboardTopEstimate = getKeyboardTop(screenH);
        if (keyboardTopEstimate >= screenH - dp(40)) return;
        if (bubbleVisible) {
            // Re-evaluate placement first (can flip below->above when keyboard is open).
            positionBubbleNearHedgehog();
            safeUpdate(bubbleView, bubbleParams);
        }
        int hedgehogBottom = hedgehogParams.y + dp(HEDGEHOG_TOUCH_BOX_DP);
        int overlayBottom = hedgehogBottom;
        if (bubbleVisible && bubbleView != null) {
            int bubbleHeight = getBubbleMeasuredHeight();
            int bubbleBottom = bubbleParams.y + bubbleHeight;
            overlayBottom = Math.max(overlayBottom, bubbleBottom);
        }
        if (overlayBottom <= keyboardTopEstimate) return;
        int liftPx = overlayBottom - keyboardTopEstimate + dp(14);
        if (bubbleVisible && bubbleTailOnTop) {
            liftPx += Math.round(screenH * 0.06f);
        }
        int targetY = clamp(hedgehogParams.y - liftPx, 0, Math.max(0, screenH - dp(HEDGEHOG_TOUCH_BOX_DP)));
        if (!keyboardLiftActive) {
            keyboardLiftOriginalY = hedgehogParams.y;
            keyboardLiftActive = true;
        }
        animateHedgehogHopTo(targetY);
    }

    private int getKeyboardTop(int screenH) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                int imeBottom = getImeBottomInset();
                if (imeBottom > 0) return screenH - imeBottom;
            } catch (Exception ignored) {
            }
        }
        try {
            View frameSource = bubbleView != null ? bubbleView : hedgehogView;
            if (frameSource != null) {
                Rect r = new Rect();
                frameSource.getWindowVisibleDisplayFrame(r);
                if (r.bottom > 0 && r.bottom < screenH) {
                    return r.bottom;
                }
            }
        } catch (Exception ignored) {
        }
        return screenH - dp(270);
    }

    private int getImeBottomInset() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0;
        try {
            View insetSource = bubbleView != null ? bubbleView : hedgehogView;
            if (insetSource == null) return 0;
            WindowInsets insets = insetSource.getRootWindowInsets();
            if (insets == null) return 0;
            return insets.getInsets(WindowInsets.Type.ime()).bottom;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void restoreFromKeyboardHop(boolean animated) {
        if (!keyboardLiftActive) return;
        int targetY = keyboardLiftOriginalY >= 0 ? keyboardLiftOriginalY : hedgehogParams.y;
        keyboardLiftActive = false;
        keyboardLiftOriginalY = -1;
        if (animated) animateHedgehogHopTo(targetY);
        else {
            hedgehogParams.y = targetY;
            positionBubbleNearHedgehog();
            safeUpdate(hedgehogView, hedgehogParams);
            if (bubbleVisible) safeUpdate(bubbleView, bubbleParams);
        }
    }

    private void animateHedgehogHopTo(int targetY) {
        if (hedgehogView == null || hedgehogParams == null) return;
        int startY = hedgehogParams.y;
        if (startY == targetY) return;
        if (hedgehogHopAnimator != null) hedgehogHopAnimator.cancel();
        final int delta = targetY - startY;
        hedgehogHopAnimator = ValueAnimator.ofFloat(0f, 1f);
        hedgehogHopAnimator.setDuration(260L);
        hedgehogHopAnimator.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            float arc = (float) Math.sin(Math.PI * t) * dp(22);
            int y = Math.round(startY + (delta * t) - arc);
            hedgehogParams.y = clamp(y, 0, Math.max(0, getScreenHeight() - dp(HEDGEHOG_TOUCH_BOX_DP)));
            positionBubbleNearHedgehog();
            safeUpdate(hedgehogView, hedgehogParams);
            if (bubbleVisible) safeUpdate(bubbleView, bubbleParams);
        });
        hedgehogHopAnimator.start();
    }

    private void animateHedgehogTravelTo(int targetX, int targetY, @Nullable Runnable onEnd) {
        animateHedgehogTravelTo(targetX, targetY, false, onEnd);
    }

    private void animateHedgehogTravelTo(int targetX, int targetY, boolean allowOffscreenX, @Nullable Runnable onEnd) {
        if (hedgehogView == null || hedgehogParams == null) {
            if (onEnd != null) onEnd.run();
            return;
        }
        if (hedgehogTravelAnimator != null) hedgehogTravelAnimator.cancel();
        int startX = hedgehogParams.x;
        int startY = hedgehogParams.y;
        if (startX == targetX && startY == targetY) {
            if (onEnd != null) onEnd.run();
            return;
        }
        final int dx = targetX - startX;
        final int dy = targetY - startY;
        final float distance = (float) Math.hypot(dx, dy);
        final float arcAmp = dp(Math.max(18, Math.min(42, Math.round(distance / 10f))));
        hedgehogTravelAnimator = ValueAnimator.ofFloat(0f, 1f);
        hedgehogTravelAnimator.setDuration(Math.max(180L, Math.min(420L, 170L + (long) (distance * 0.7f))));
        hedgehogTravelAnimator.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            float arc = (float) Math.sin(Math.PI * t) * arcAmp;
            int nextX = Math.round(startX + (dx * t));
            hedgehogParams.x = allowOffscreenX
                ? nextX
                : clamp(nextX, 0, Math.max(0, getScreenWidth() - dp(HEDGEHOG_TOUCH_BOX_DP)));
            hedgehogParams.y = clamp(Math.round(startY + (dy * t) - arc), 0, Math.max(0, getScreenHeight() - dp(HEDGEHOG_TOUCH_BOX_DP)));
            positionBubbleNearHedgehog();
            safeUpdate(hedgehogView, hedgehogParams);
            if (bubbleVisible) safeUpdate(bubbleView, bubbleParams);
        });
        hedgehogTravelAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled = false;
            @Override public void onAnimationCancel(android.animation.Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (!cancelled && onEnd != null) onEnd.run();
            }
        });
        hedgehogTravelAnimator.start();
    }

    private void hideHedgehogToEdge() {
        if (hedgehogHiddenAtEdge || hedgehogView == null) return;
        hiddenRestoreX = hedgehogParams.x;
        hiddenRestoreY = hedgehogParams.y;
        hiddenEdgeRight = isHedgehogCloserToRightEdge();
        updateHideActionIcon();
        int offscreenX = hiddenEdgeRight ? getScreenWidth() + dp(6) : -(dp(HEDGEHOG_TOUCH_BOX_DP) + dp(6));
        int targetY = hedgehogParams.y;
        animateHedgehogTravelTo(offscreenX, targetY, true, () -> {
            hedgehogHiddenAtEdge = true;
            if (bubbleVisible) toggleBubble(false);
            if (quickActionsVisible) showQuickActions(false);
            if (hedgehogView != null) hedgehogView.setVisibility(View.GONE);
            positionEdgeTabForHiddenState();
            if (edgeTabView != null) {
                edgeTabView.setVisibility(View.VISIBLE);
                safeUpdate(edgeTabView, edgeTabParams);
            }
            updatePinnedMicVisibility();
        });
    }

    private void restoreHedgehogFromEdge() {
        if (!hedgehogHiddenAtEdge || hedgehogView == null) return;
        if (edgeTabView != null) edgeTabView.setVisibility(View.GONE);
        int restoreX = hiddenRestoreX >= 0 ? hiddenRestoreX : dp(18);
        int restoreY = hiddenRestoreY >= 0 ? hiddenRestoreY : dp(220);
        int offscreenX = hiddenEdgeRight ? getScreenWidth() + dp(6) : -(dp(HEDGEHOG_TOUCH_BOX_DP) + dp(6));
        hedgehogParams.x = offscreenX;
        hedgehogParams.y = clamp(restoreY, 0, Math.max(0, getScreenHeight() - dp(HEDGEHOG_TOUCH_BOX_DP)));
        hedgehogHiddenAtEdge = false;
        hedgehogView.setVisibility(View.VISIBLE);
        safeUpdate(hedgehogView, hedgehogParams);
        animateHedgehogTravelTo(
            clamp(restoreX, 0, Math.max(0, getScreenWidth() - dp(HEDGEHOG_TOUCH_BOX_DP))),
            clamp(restoreY, 0, Math.max(0, getScreenHeight() - dp(HEDGEHOG_TOUCH_BOX_DP))),
            true,
            this::updatePinnedMicVisibility
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return e.getClass().getSimpleName();
        return m;
    }
}
