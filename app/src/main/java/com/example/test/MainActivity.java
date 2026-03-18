package ai.agent1c.hitomi;

import android.content.Intent;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_REQUEST_MIC_PERMISSION = "request_mic_permission";
    public static final String EXTRA_FORCE_SHOW_MAIN = "force_show_main";
    private static final int REQ_RECORD_AUDIO = 4201;
    private static final int REQ_TERMUX_RUN_COMMAND = 4202;
    private static final String TERMUX_EXTERNAL_APPS_CMD =
        "mkdir -p ~/.termux && grep -qx 'allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps=true' >> ~/.termux/termux.properties";
    private TextView statusText;
    private TextView authStatusText;
    private TextView authTitleText;
    private TextView authHintText;
    private HitomiAuthManager authManager;
    private TermuxCommandBridge termuxBridge;
    private Button authActionButton;
    private View apiKeyEntryRow;
    private EditText apiKeyInput;
    private Button apiKeySubmitButton;
    private Button signOutButton;
    private Button startOverlayButton;
    private Button stopOverlayButton;
    private TextView termuxStatusText;
    private Button termuxInstallButton;
    private Button termuxEnableButton;
    private Button termuxOpenButton;
    private Button termuxTestButton;
    private View termuxSetupPanel;
    private TextView termuxSetupHelpText;
    private TextView termuxSetupCommandText;
    private Button termuxSetupCopyButton;
    private boolean forceShowMain;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        setContentView(R.layout.activity_main);

        authManager = new HitomiAuthManager(this);
        forceShowMain = getIntent() != null && getIntent().getBooleanExtra(EXTRA_FORCE_SHOW_MAIN, false);
        termuxBridge = new TermuxCommandBridge(this);
        statusText = findViewById(R.id.statusText);
        authStatusText = findViewById(R.id.authStatusText);
        authTitleText = findViewById(R.id.authTitleText);
        authHintText = findViewById(R.id.authHintText);
        authActionButton = findViewById(R.id.authActionButton);
        apiKeyEntryRow = findViewById(R.id.apiKeyEntryRow);
        apiKeyInput = findViewById(R.id.apiKeyInput);
        apiKeySubmitButton = findViewById(R.id.apiKeySubmitButton);
        signOutButton = findViewById(R.id.signOutButton);
        Button overlayPermissionButton = findViewById(R.id.overlayPermissionButton);
        startOverlayButton = findViewById(R.id.startOverlayButton);
        stopOverlayButton = findViewById(R.id.stopOverlayButton);
        termuxStatusText = findViewById(R.id.termuxStatusText);
        termuxInstallButton = findViewById(R.id.termuxInstallButton);
        termuxEnableButton = findViewById(R.id.termuxEnableButton);
        termuxOpenButton = findViewById(R.id.termuxOpenButton);
        termuxTestButton = findViewById(R.id.termuxTestButton);
        termuxSetupPanel = findViewById(R.id.termuxSetupPanel);
        termuxSetupHelpText = findViewById(R.id.termuxSetupHelpText);
        termuxSetupCommandText = findViewById(R.id.termuxSetupCommandText);
        termuxSetupCopyButton = findViewById(R.id.termuxSetupCopyButton);

        handleAuthIntent(getIntent());
        maybeHandlePermissionIntent(getIntent());

        authActionButton.setOnClickListener(v -> startAuthFlow());
        if (apiKeySubmitButton != null) {
            apiKeySubmitButton.setOnClickListener(v -> submitDirectApiKey());
        }
        signOutButton.setOnClickListener(v -> {
            authManager.signOut();
            if (apiKeyInput != null) apiKeyInput.setText("");
            refreshAuthStatus();
            statusText.setText("Status: signed out");
            refreshControlVisibility();
        });

        overlayPermissionButton.setOnClickListener(v -> requestOverlayPermission());
        startOverlayButton.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                statusText.setText("Status: overlay permission required");
                requestOverlayPermission();
                return;
            }
            if (!authManager.isSignedIn()) {
                if (BuildConfig.IS_OPEN_VARIANT) {
                    statusText.setText("Status: enter API key first");
                    authStatusText.setText("Auth: Grok API key required before chatting");
                } else {
                    statusText.setText("Status: authentication required");
                    authStatusText.setText("Auth: authentication required before chatting");
                }
                return;
            }
            Intent intent = new Intent(this, HedgehogOverlayService.class);
            intent.setAction(HedgehogOverlayService.ACTION_START);
            ContextCompat.startForegroundService(this, intent);
            statusText.setText("Status: starting overlay...");
            refreshControlVisibility();
        });
        stopOverlayButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, HedgehogOverlayService.class);
            intent.setAction(HedgehogOverlayService.ACTION_STOP);
            startService(intent);
            statusText.setText("Status: stopping overlay...");
            refreshControlVisibility();
        });
        termuxInstallButton.setOnClickListener(v -> openTermuxInstallPage());
        if (termuxEnableButton != null) termuxEnableButton.setOnClickListener(v -> enableTermuxShellTools());
        termuxOpenButton.setOnClickListener(v -> openTermuxApp());
        termuxTestButton.setOnClickListener(v -> runTermuxTestCommand());
        if (termuxSetupCopyButton != null) {
            termuxSetupCopyButton.setOnClickListener(v -> copyTermuxSetupCommand());
        }

        configureFlavorUi();
        refreshAuthStatus();
        refreshControlVisibility();
        refreshTermuxStatus();
        maybeAutoLaunchOverlayAndHideMain();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        forceShowMain = intent != null && intent.getBooleanExtra(EXTRA_FORCE_SHOW_MAIN, false);
        handleAuthIntent(intent);
        maybeHandlePermissionIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean allowed = Settings.canDrawOverlays(this);
        statusText.setText(allowed ? "Status: overlay permission granted" : "Status: overlay permission not granted");
        refreshAuthStatus();
        refreshControlVisibility();
        refreshTermuxStatus();
        maybeAutoLaunchOverlayAndHideMain();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (termuxBridge != null) termuxBridge.shutdown();
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        }
    }

    private void maybeHandlePermissionIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC_PERMISSION, false)) {
            intent.removeExtra(EXTRA_REQUEST_MIC_PERMISSION);
            requestMicPermission();
        }
    }

    private void requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            statusText.setText("Status: microphone permission granted");
            return;
        }
        ActivityCompat.requestPermissions(this, new String[]{ android.Manifest.permission.RECORD_AUDIO }, REQ_RECORD_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            boolean granted = grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                statusText.setText("Status: microphone permission granted");
                Toast.makeText(this, "Microphone enabled for Hitomi.", Toast.LENGTH_SHORT).show();
            } else {
                statusText.setText("Status: microphone permission denied");
                Toast.makeText(this, "Microphone permission is required for always listening.", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode == REQ_TERMUX_RUN_COMMAND) {
            boolean granted = grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                statusText.setText("Status: Termux command permission granted. Next: enable external apps in Termux.");
                Toast.makeText(this, "Termux permission granted. Now enable external apps in Termux settings/file.", Toast.LENGTH_LONG).show();
            } else {
                statusText.setText("Status: Termux command permission denied");
                Toast.makeText(this, "Grant Termux RUN_COMMAND permission to use local shell tools.", Toast.LENGTH_LONG).show();
            }
            refreshTermuxStatus();
        }
    }

    private void startAuthFlow() {
        if (BuildConfig.IS_OPEN_VARIANT) {
            submitDirectApiKey();
            return;
        }
        try {
            String url = authManager.buildWebAuthLaunchUrl(null);
            if (!launchAuthInBrowser(Uri.parse(url), null)) {
                throw new IllegalStateException("No browser found for sign-in");
            }
            authStatusText.setText("Auth: waiting for web sign-in...");
        } catch (Exception e) {
            authStatusText.setText("Auth error: " + safeMessage(e));
        }
    }

    private boolean launchAuthInBrowser(Uri uri, String provider) {
        try {
            String browserPkg = pickBrowserPackageForOAuth(uri, provider);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            if (browserPkg != null && !browserPkg.isEmpty()) {
                intent.setPackage(browserPkg);
            }
            startActivity(intent);
            if ("x".equals(provider)) {
                authStatusText.setText("Auth: waiting for x sign-in... If X app home opens, use Google/Magic Link or disable 'Open supported links' for X.");
            }
            return true;
        } catch (Exception ignored) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, uri);
                fallback.addCategory(Intent.CATEGORY_BROWSABLE);
                startActivity(fallback);
                return true;
            } catch (Exception ignoredAgain) {
                return false;
            }
        }
    }

    private String pickBrowserPackageForOAuth(Uri uri, String provider) {
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_VIEW, uri);
        probe.addCategory(Intent.CATEGORY_BROWSABLE);
        List<ResolveInfo> resolved = pm.queryIntentActivities(probe, 0);
        if ((resolved == null || resolved.isEmpty()) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            resolved = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL);
        }
        if (resolved == null || resolved.isEmpty()) return null;

        List<String> blockedPkgs = Arrays.asList(
            "com.twitter.android",
            "com.x.android"
        );
        List<String> preferredPkgs;
        if ("x".equals(provider)) {
            // Chrome frequently hands x.com links off to the X app; prefer browsers that are less aggressive first.
            preferredPkgs = Arrays.asList(
                "org.mozilla.firefox",
                "org.mozilla.fenix",
                "com.brave.browser",
                "com.microsoft.emmx",
                "com.opera.browser",
                "com.opera.gx",
                "com.android.chrome",
                "com.chrome.beta",
                "com.chrome.dev"
            );
        } else {
            preferredPkgs = Arrays.asList(
                "com.android.chrome",
                "com.chrome.beta",
                "com.chrome.dev",
                "org.mozilla.firefox",
                "org.mozilla.fenix",
                "com.microsoft.emmx",
                "com.brave.browser",
                "com.opera.browser",
                "com.opera.gx"
            );
        }

        List<String> browserPkgs = new ArrayList<>();
        for (ResolveInfo ri : resolved) {
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.isEmpty()) continue;
            if (blockedPkgs.contains(pkg)) continue;
            if (pkg.equals(getPackageName())) continue;
            if (!browserPkgs.contains(pkg)) browserPkgs.add(pkg);
        }
        if (browserPkgs.isEmpty()) return null;

        for (String preferred : preferredPkgs) {
            if (browserPkgs.contains(preferred)) return preferred;
        }
        return browserPkgs.get(0);
    }

    private void handleAuthIntent(Intent intent) {
        if (BuildConfig.IS_OPEN_VARIANT) return;
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;
        authStatusText.setText("Auth: processing sign-in...");
        new Thread(() -> {
            try {
                boolean handled = authManager.handleAuthCallbackUri(data);
                runOnUiThread(() -> {
                    if (handled) {
                        Intent current = getIntent();
                        if (current != null) current.setData(null);
                        refreshAuthStatus();
                        refreshControlVisibility();
                        Toast.makeText(this, "Signed in. You can start Hitomi now.", Toast.LENGTH_SHORT).show();
                        maybeAutoLaunchOverlayAndHideMain();
                    } else {
                        authStatusText.setText("Auth: callback received but no token found");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> authStatusText.setText("Auth error: " + safeMessage(e)));
            }
        }).start();
    }

    private void refreshAuthStatus() {
        if (BuildConfig.IS_OPEN_VARIANT) {
            if (!authManager.isSignedIn()) {
                authStatusText.setText("Auth: Grok API key required");
                refreshControlVisibility();
                return;
            }
            authStatusText.setText("Auth: Grok API key saved locally");
            refreshControlVisibility();
            return;
        }
        if (!authManager.isSignedIn()) {
            authStatusText.setText("Auth: signed out");
            refreshControlVisibility();
            return;
        }
        String provider = authManager.getProvider();
        String display = authManager.getDisplayName();
        String email = authManager.getEmail();
        StringBuilder sb = new StringBuilder("Auth: signed in");
        if (!provider.isEmpty()) sb.append(" via ").append(provider);
        if (!display.isEmpty()) sb.append(" as ").append(display);
        if (!email.isEmpty() && (display.isEmpty() || !display.equals(email))) sb.append(" (").append(email).append(")");
        authStatusText.setText(sb.toString());
        refreshControlVisibility();
    }

    private void refreshControlVisibility() {
        boolean signedIn = authManager != null && authManager.isSignedIn();
        boolean overlayRunning = HedgehogOverlayService.isOverlayRunning();
        if (authActionButton != null) {
            authActionButton.setVisibility(BuildConfig.IS_OPEN_VARIANT || signedIn ? android.view.View.GONE : android.view.View.VISIBLE);
        }
        if (apiKeyEntryRow != null) {
            apiKeyEntryRow.setVisibility(BuildConfig.IS_OPEN_VARIANT && !signedIn ? View.VISIBLE : View.GONE);
        }
        if (authHintText != null) authHintText.setVisibility(signedIn ? android.view.View.GONE : android.view.View.VISIBLE);
        if (signOutButton != null) signOutButton.setVisibility(signedIn ? android.view.View.VISIBLE : android.view.View.GONE);
        if (startOverlayButton != null) startOverlayButton.setVisibility(overlayRunning ? android.view.View.GONE : android.view.View.VISIBLE);
        if (stopOverlayButton != null) stopOverlayButton.setVisibility(overlayRunning ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void maybeAutoLaunchOverlayAndHideMain() {
        if (forceShowMain) return;
        if (authManager == null || !authManager.isSignedIn()) return;
        if (!Settings.canDrawOverlays(this)) return;
        if (!HedgehogOverlayService.isOverlayRunning()) {
            Intent intent = new Intent(this, HedgehogOverlayService.class);
            intent.setAction(HedgehogOverlayService.ACTION_START);
            ContextCompat.startForegroundService(this, intent);
            statusText.setText("Status: starting overlay...");
            refreshControlVisibility();
        }
        moveTaskToBack(true);
        finish();
    }

    private void refreshTermuxStatus() {
        if (termuxBridge == null || termuxStatusText == null) return;
        boolean installed = termuxBridge.isTermuxInstalled();
        boolean service = termuxBridge.isRunCommandServiceAvailable();
        if (!installed) {
            termuxStatusText.setText("Termux: not installed");
            hideTermuxSetupPanel();
        } else if (!service) {
            termuxStatusText.setText("Termux: installed, RunCommand service unavailable");
        } else if (!hasTermuxRunCommandPermission()) {
            termuxStatusText.setText("Termux: installed and bridge available (grant Termux command permission)");
        } else {
            termuxStatusText.setText("Termux: installed and command bridge available");
        }
        if (termuxInstallButton != null) termuxInstallButton.setVisibility(installed ? View.GONE : View.VISIBLE);
        if (termuxEnableButton != null) termuxEnableButton.setVisibility(installed ? View.VISIBLE : View.GONE);
        if (termuxOpenButton != null) termuxOpenButton.setVisibility(installed ? View.VISIBLE : View.GONE);
        if (termuxTestButton != null) termuxTestButton.setEnabled(installed && service && hasTermuxRunCommandPermission());
    }

    private boolean hasTermuxRunCommandPermission() {
        return ContextCompat.checkSelfPermission(this, "com.termux.permission.RUN_COMMAND")
            == PackageManager.PERMISSION_GRANTED;
    }

    private void enableTermuxShellTools() {
        if (termuxBridge == null || !termuxBridge.isTermuxInstalled()) {
            statusText.setText("Status: install Termux first (F-Droid)");
            openTermuxInstallPage();
            return;
        }
        if (!termuxBridge.isRunCommandServiceAvailable()) {
            statusText.setText("Status: Termux RunCommand service unavailable");
            Toast.makeText(this, "Termux command service not available on this Termux build.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasTermuxRunCommandPermission()) {
            ActivityCompat.requestPermissions(this, new String[]{"com.termux.permission.RUN_COMMAND"}, REQ_TERMUX_RUN_COMMAND);
            return;
        }
        showTermuxExternalAppsInstructions();
    }

    private void openTermuxInstallPage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")));
        } catch (Exception e) {
            statusText.setText("Status: couldn't open F-Droid page (" + safeMessage(e) + ")");
        }
    }

    private void openTermuxApp() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(TermuxCommandBridge.TERMUX_PACKAGE);
            if (launch == null) {
                statusText.setText("Status: Termux app not found");
                refreshTermuxStatus();
                return;
            }
            startActivity(launch);
            statusText.setText("Status: opened Termux. If command tests fail, allow external app commands in Termux.");
        } catch (Exception e) {
            statusText.setText("Status: failed to open Termux (" + safeMessage(e) + ")");
        }
    }

    private void showTermuxExternalAppsInstructions() {
        statusText.setText(
            "Status: Configure Termux external app commands, restart Termux, then tap Test Termux Command."
        );
        showTermuxSetupPanel(
            "In Termux, run setup command, then fully close and reopen Termux, then tap Test Termux Command.",
            TERMUX_EXTERNAL_APPS_CMD
        );
        Toast.makeText(this, "Open Termux, run the setup command shown, restart Termux, then test again.", Toast.LENGTH_LONG).show();
    }

    private void showTermuxSetupPanel(String help, String cmd) {
        if (termuxSetupHelpText != null) termuxSetupHelpText.setText(help);
        if (termuxSetupCommandText != null) termuxSetupCommandText.setText(cmd == null ? "" : cmd);
        if (termuxSetupPanel != null) termuxSetupPanel.setVisibility(View.VISIBLE);
    }

    private void hideTermuxSetupPanel() {
        if (termuxSetupPanel != null) termuxSetupPanel.setVisibility(View.GONE);
    }

    private void copyTermuxSetupCommand() {
        String text = termuxSetupCommandText == null ? "" : String.valueOf(termuxSetupCommandText.getText());
        if (text.trim().isEmpty()) {
            Toast.makeText(this, "No setup command to copy yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Termux setup command", text));
            Toast.makeText(this, "Copied Termux setup command.", Toast.LENGTH_SHORT).show();
        }
    }

    private void runTermuxTestCommand() {
        if (termuxBridge == null) return;
        refreshTermuxStatus();
        if (!termuxBridge.isTermuxInstalled()) {
            statusText.setText("Status: install Termux first (F-Droid)");
            return;
        }
        if (!hasTermuxRunCommandPermission()) {
            statusText.setText("Status: grant Termux command permission first");
            enableTermuxShellTools();
            return;
        }
        statusText.setText("Status: running Termux test command...");
        hideTermuxSetupPanel();
        termuxBridge.runTestCommand(new TermuxCommandBridge.Callback() {
            @Override
            public void onResult(TermuxCommandBridge.Result result) {
                runOnUiThread(() -> {
                    if (result == null) {
                        statusText.setText("Status: Termux test failed (no result)");
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("Status: Termux test exit=").append(result.exitCode);
                    if (result.timedOut) sb.append(" (timeout)");
                    if (result.errorMessage != null && !result.errorMessage.isEmpty()) {
                        sb.append(" err=").append(result.errorMessage);
                    }
                    if (result.stdout != null && !result.stdout.trim().isEmpty()) {
                        String one = result.stdout.trim().replace('\n', ' ');
                        if (one.length() > 90) one = one.substring(0, 90) + "...";
                        sb.append(" | ").append(one);
                    } else if (result.stderr != null && !result.stderr.trim().isEmpty()) {
                        String one = result.stderr.trim().replace('\n', ' ');
                        if (one.length() > 90) one = one.substring(0, 90) + "...";
                        sb.append(" | stderr: ").append(one);
                    }
                    statusText.setText(sb.toString());
                    if (result.exitCode == 0 && !result.timedOut) {
                        Toast.makeText(MainActivity.this, "Termux command bridge works.", Toast.LENGTH_SHORT).show();
                        termuxStatusText.setText("Termux: installed, command bridge ready");
                        hideTermuxSetupPanel();
                        return;
                    }
                    String allErr = ((result.errorMessage == null ? "" : result.errorMessage) + "\n" +
                        (result.stderr == null ? "" : result.stderr));
                    if (allErr.contains("allow-external-apps") || allErr.contains("termux.properties")) {
                        showTermuxExternalAppsInstructions();
                    }
                });
            }
        });
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return e.getClass().getSimpleName();
        return m;
    }

    private void configureFlavorUi() {
        if (!BuildConfig.IS_OPEN_VARIANT) return;
        if (authTitleText != null) authTitleText.setText("Enter your Grok API key here:");
        if (authHintText != null) {
            authHintText.setText("Your Grok API key stays on this device.");
        }
        if (apiKeyInput != null) {
            String savedKey = authManager.getDirectApiKey();
            if (!savedKey.isEmpty()) apiKeyInput.setText(savedKey);
        }
    }

    private void submitDirectApiKey() {
        if (!BuildConfig.IS_OPEN_VARIANT) return;
        String apiKey = apiKeyInput == null ? "" : String.valueOf(apiKeyInput.getText()).trim();
        if (apiKey.isEmpty()) {
            authStatusText.setText("Auth: enter a Grok API key first");
            Toast.makeText(this, "Enter a Grok API key.", Toast.LENGTH_SHORT).show();
            return;
        }
        authManager.signInWithDirectApiKey(apiKey);
        refreshAuthStatus();
        statusText.setText("Status: API key saved");
        refreshControlVisibility();
        Toast.makeText(this, "Grok API key saved locally.", Toast.LENGTH_SHORT).show();
        maybeAutoLaunchOverlayAndHideMain();
    }
}
