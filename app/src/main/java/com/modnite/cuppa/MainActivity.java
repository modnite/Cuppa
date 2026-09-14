package com.modnite.cuppa;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    public UsbPrintManager printManager;
    public JobQueueManager jobQueueManager;
    private AppUpdateManager appUpdateManager;

    private PrintServerService printServerService;
    private SharedPreferences prefs;
    private boolean isServiceBound = false;

    private PrintersTabFragment printersTab;
    private JobsTabFragment jobsTab;
    private AdminTabFragment adminTab;
    private LogTabFragment logTab;

    public final StringBuilder logBuffer = new StringBuilder();

    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (printManager != null) {
                printManager.pollHardwareStatus();
            }
            pollHandler.postDelayed(this, 5000);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            PrintServerService.LocalBinder binder = (PrintServerService.LocalBinder) service;
            printServerService = binder.getService();
            isServiceBound = true;
            // Let the Admin tab handle whether it should be started
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            printServerService = null;
            isServiceBound = false;
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbPrintManager.ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        log("[USB] Permission GRANTED for Rollo. Running hardware check...");
                        if (printManager != null) {
                            printManager.runPrinterDiagnosticsAsync();
                        }
                    } else {
                        log("[USB] Permission DENIED for Rollo.");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                log("[USB] USB Device Attached to Dock/Phone!");
                if (printManager != null) {
                    printManager.runPrinterDiagnosticsAsync();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                log("[USB] USB Device Detached from Dock/Phone!");
                if (printManager != null) {
                    printManager.runPrinterDiagnosticsAsync();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("rollo_prefs", MODE_PRIVATE);
        applyAppTheme(prefs.getInt("PREF_APP_THEME", 0));

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        boolean isNightMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat insetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (insetsController != null) {
            insetsController.setAppearanceLightStatusBars(!isNightMode);
            insetsController.setAppearanceLightNavigationBars(!isNightMode);
        }

        View mainRoot = findViewById(R.id.mainRoot);
        ViewCompat.setOnApplyWindowInsetsListener(mainRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        // Init Tabs
        printersTab = new PrintersTabFragment();
        jobsTab = new JobsTabFragment();
        adminTab = new AdminTabFragment();
        logTab = new LogTabFragment();

        FragmentManager fm = getSupportFragmentManager();
        fm.beginTransaction()
                .add(R.id.fragmentContainer, printersTab, "Printers")
                .add(R.id.fragmentContainer, jobsTab, "Jobs").hide(jobsTab)
                .add(R.id.fragmentContainer, adminTab, "Admin").hide(adminTab)
                .add(R.id.fragmentContainer, logTab, "Log").hide(logTab)
                .commit();

        BottomNavigationView bottomNav = findViewById(R.id.bottomNavigation);
        bottomNav.setOnItemSelectedListener(item -> {
            FragmentTransaction ft = fm.beginTransaction();
            ft.hide(printersTab).hide(jobsTab).hide(adminTab).hide(logTab);

            int itemId = item.getItemId();
            if (itemId == R.id.nav_printers) {
                ft.show(printersTab);
            } else if (itemId == R.id.nav_jobs) {
                jobsTab.setJobQueueManager(jobQueueManager);
                ft.show(jobsTab);
            } else if (itemId == R.id.nav_admin) {
                ft.show(adminTab);
            } else if (itemId == R.id.nav_log) {
                ft.show(logTab);
            }
            ft.commit();
            return true;
        });

        printManager = new UsbPrintManager(this, text -> {
            log(text);
            return null;
        });

        printManager.setOnHardwareStateChanged(states -> {
            runOnUiThread(() -> {
                if (printersTab != null) {
                    printersTab.updateHardwareBadge(states);
                }
            });
            return null;
        });

        jobQueueManager = new JobQueueManager(
                printManager,
                text -> {
                    log(text);
                    return null;
                },
                count -> {
                    // Update any active queue count displays if needed
                    return null;
                }
        );

        Intent intent = new Intent(this, PrintServerService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbPrintManager.ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED);

        String appVersion = "5.2.0";
        try {
            appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {}

        log("Cuppa v" + appVersion + " Loaded.");
        log("OpenPrinting CUPS Print Server Engine Active.");

        pollHandler.postDelayed(pollRunnable, 1000);

        appUpdateManager = new AppUpdateManager(
                this,
                appVersion,
                text -> {
                    log(text);
                    return null;
                },
                (latestTag, releaseNotes, apkUrl) -> {
                    runOnUiThread(() -> showUpdateAvailableDialog(latestTag, releaseNotes, apkUrl));
                    return null;
                }
        );
        appUpdateManager.startPeriodicCheck();
        checkAndRequestAllPermissions();
    }

    public void log(String text) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String logLine = "[" + timestamp + "] " + text + "\n";
        logBuffer.append(logLine);
        
        if (logTab != null && logTab.isAdded()) {
            logTab.log(text);
        }
    }

    public void startIppServer() {
        if (isServiceBound && printServerService != null) return;
        Intent intent = new Intent(this, PrintServerService.class);
        intent.setAction(PrintServerService.ACTION_START);
        try {
            ContextCompat.startForegroundService(this, intent);
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            log("ERROR starting IPP print server: " + e.getMessage());
        }
    }

    public void stopIppServer() {
        if (isServiceBound && printServerService != null) {
            printServerService.stopServer();
            unbindService(serviceConnection);
            isServiceBound = false;
        }
        stopService(new Intent(this, PrintServerService.class));
    }

    public void dumpActivityLogsToEtherpad() {
        String etherpadUrl = prefs.getString("PREF_ETHERPAD_URL", "").trim();
        String apiKey = prefs.getString("PREF_ETHERPAD_API_KEY", "").trim();
        String logContent = logTab != null ? logTab.getLogText() : "";

        if (etherpadUrl.isEmpty()) {
            log("[ETHERPAD] No Pastebin URL configured in Admin settings.");
            return;
        }

        if (logContent.trim().isEmpty()) {
            log("[ETHERPAD] Activity log is empty. Nothing to dump.");
            return;
        }

        log("[ETHERPAD] Dumping activity log to " + etherpadUrl + "...");

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String cleanUrl = etherpadUrl.trim();
                if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                    cleanUrl = "http://" + cleanUrl;
                }

                URI uri = new URI(cleanUrl);
                String host = uri.getHost() != null ? uri.getHost() : "127.0.0.1";
                int port = uri.getPort() != -1 ? uri.getPort() : 9001;
                String scheme = uri.getScheme() != null ? uri.getScheme() : "http";
                String path = uri.getPath() != null ? uri.getPath() : "/p/notepad";
                String padId = path.startsWith("/p/") ? path.substring(3) : "notepad";

                if (!apiKey.isEmpty()) {
                    String apiUrl = scheme + "://" + host + ":" + port + "/api/1.2.14/setText";
                    URL url = new URL(apiUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                    String postData = "apikey=" + URLEncoder.encode(apiKey, "UTF-8") +
                            "&padID=" + URLEncoder.encode(padId, "UTF-8") +
                            "&text=" + URLEncoder.encode(logContent, "UTF-8");

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(postData.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }

                    int responseCode = conn.getResponseCode();
                    if (responseCode == 200) {
                        log("[ETHERPAD] SUCCESS: Activity log dumped to Etherpad via API key (" + padId + ").");
                        return;
                    }
                }

                String importUrl = scheme + "://" + host + ":" + port + "/p/" + padId + "/import";
                postMultipartFileToEtherpad(importUrl, padId, logContent);

            } catch (Exception e) {
                log("[ETHERPAD] Dump notice: " + e.getMessage());
            }
        });
    }

    private void postMultipartFileToEtherpad(String importUrl, String padId, String logContent) {
        try {
            String boundary = "---EtherpadBoundary" + System.currentTimeMillis();
            URL url = new URL(importUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            try (OutputStream os = conn.getOutputStream()) {
                String header = "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"file\"; filename=\"" + padId + ".txt\"\r\n" +
                        "Content-Type: text/plain\r\n\r\n";
                os.write(header.getBytes(StandardCharsets.UTF_8));
                os.write(logContent.getBytes(StandardCharsets.UTF_8));
                String footer = "\r\n--" + boundary + "--\r\n";
                os.write(footer.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200 || responseCode == 302 || responseCode == 303) {
                log("[ETHERPAD] SUCCESS: Activity log dumped to Etherpad pad '/p/" + padId + "'.");
            } else {
                log("[ETHERPAD] WARNING: Etherpad import returned HTTP " + responseCode);
            }
        } catch (Exception e) {
            log("[ETHERPAD] Direct import notice: " + e.getMessage());
        }
    }

    private void checkAndRequestAllPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (!permissionsNeeded.isEmpty()) {
            log("Prompting for initial app permissions...");
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), 200);
        }
    }

    private void applyAppTheme(int themeMode) {
        switch (themeMode) {
            case 1: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES); break;
            case 2: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO); break;
            default: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM); break;
        }
    }

    private void showUpdateAvailableDialog(String latestTag, String releaseNotes, String apkUrl) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Cuppa update available (v" + latestTag + ")")
                .setMessage(releaseNotes)
                .setPositiveButton(R.string.update_now, (dialog, which) -> {
                    log("[UI_EVENT] User accepted update. Downloading v" + latestTag + "...");
                    if (appUpdateManager != null) {
                        appUpdateManager.downloadAndInstallApk(apkUrl, msg -> {
                            log(msg);
                            return null;
                        });
                    }
                })
                .setNegativeButton(R.string.ignore, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (printManager != null) {
            printManager.runPrinterDiagnosticsAsync();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pollHandler.removeCallbacks(pollRunnable);
        if (isServiceBound) {
            unbindService(serviceConnection);
            isServiceBound = false;
        }
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception e) { }
    }
}
