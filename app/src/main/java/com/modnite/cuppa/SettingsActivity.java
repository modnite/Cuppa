package com.modnite.cuppa;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("rollo_prefs", MODE_PRIVATE);

        // Apply saved theme
        int savedTheme = prefs.getInt("PREF_APP_THEME", 0);
        applyAppTheme(savedTheme);

        // Enable edge-to-edge drawing so layout responds to status bar / cutouts
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_settings);

        // Invert status bar & navigation bar icons in Light Mode
        boolean isNightMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat insetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (insetsController != null) {
            insetsController.setAppearanceLightStatusBars(!isNightMode);
            insetsController.setAppearanceLightNavigationBars(!isNightMode);
        }

        View rootView = findViewById(R.id.settingsRoot);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        MaterialToolbar toolbar = findViewById(R.id.toolbarSettings);
        toolbar.setNavigationOnClickListener(v -> finish());

        // Theme options
        RadioGroup rgAppTheme = findViewById(R.id.rgAppTheme);
        RadioButton rbThemeSystem = findViewById(R.id.rbThemeSystem);
        RadioButton rbThemeDark = findViewById(R.id.rbThemeDark);
        RadioButton rbThemeLight = findViewById(R.id.rbThemeLight);

        if (savedTheme == 1) {
            rbThemeDark.setChecked(true);
        } else if (savedTheme == 2) {
            rbThemeLight.setChecked(true);
        } else {
            rbThemeSystem.setChecked(true);
        }

        rgAppTheme.setOnCheckedChangeListener((group, checkedId) -> {
            int newTheme = 0;
            if (checkedId == R.id.rbThemeDark) {
                newTheme = 1;
            } else if (checkedId == R.id.rbThemeLight) {
                newTheme = 2;
            }
            prefs.edit().putInt("PREF_APP_THEME", newTheme).apply();
            applyAppTheme(newTheme);
        });

        // Switches
        MaterialSwitch switchLocal = findViewById(R.id.switchLocalPreview);
        MaterialSwitch switchNetwork = findViewById(R.id.switchNetworkPreview);
        MaterialSwitch switchHoldNetworkJobs = findViewById(R.id.switchHoldNetworkJobs);
        MaterialSwitch switchRawPort9100 = findViewById(R.id.switchRawPort9100);

        boolean showLocal = prefs.getBoolean("PREF_LOCAL_PREVIEW", true);
        boolean showNetwork = prefs.getBoolean("PREF_NETWORK_PREVIEW", false);
        boolean holdNetwork = prefs.getBoolean("PREF_HOLD_NETWORK_JOBS", false);
        boolean rawPort9100 = prefs.getBoolean("PREF_RAW_PORT_9100", true);

        switchLocal.setChecked(showLocal);
        switchNetwork.setChecked(showNetwork);
        switchHoldNetworkJobs.setChecked(holdNetwork);
        switchRawPort9100.setChecked(rawPort9100);

        switchLocal.setOnCheckedChangeListener((b, isChecked) ->
                prefs.edit().putBoolean("PREF_LOCAL_PREVIEW", isChecked).apply());

        switchNetwork.setOnCheckedChangeListener((b, isChecked) ->
                prefs.edit().putBoolean("PREF_NETWORK_PREVIEW", isChecked).apply());

        switchHoldNetworkJobs.setOnCheckedChangeListener((b, isChecked) ->
                prefs.edit().putBoolean("PREF_HOLD_NETWORK_JOBS", isChecked).apply());

        switchRawPort9100.setOnCheckedChangeListener((b, isChecked) ->
                prefs.edit().putBoolean("PREF_RAW_PORT_9100", isChecked).apply());

        // Pastebin inputs
        TextInputEditText etEtherpadUrl = findViewById(R.id.etEtherpadUrl);
        TextInputEditText etEtherpadApiKey = findViewById(R.id.etEtherpadApiKey);

        etEtherpadUrl.setText(prefs.getString("PREF_ETHERPAD_URL", ""));
        etEtherpadApiKey.setText(prefs.getString("PREF_ETHERPAD_API_KEY", ""));

        // Diagnostics, Export Cache & Update Buttons
        Button btnDiagnostics = findViewById(R.id.btnDiagnostics);
        Button btnExportCache = findViewById(R.id.btnExportCache);
        Button btnCheckUpdates = findViewById(R.id.btnCheckUpdates);

        if (btnDiagnostics != null) {
            btnDiagnostics.setOnClickListener(v -> {
                Toast.makeText(this, "Running USB hardware scan...", Toast.LENGTH_SHORT).show();
                finish(); // Return to MainActivity where full USB Bus Scan is output to tvLog
            });
        }

        if (btnExportCache != null) {
            int cacheCount = PrintHistoryCacheManager.getCachedFileCount(this);
            btnExportCache.setText("Print History Cache (" + cacheCount + " screenshots)");

            btnExportCache.setOnClickListener(v -> {
                PrintCacheGalleryDialogFragment galleryDialog = PrintCacheGalleryDialogFragment.Companion.newInstance();
                galleryDialog.show(getSupportFragmentManager(), "PrintCacheGallery");
            });
        }

        if (btnCheckUpdates != null) {
            btnCheckUpdates.setOnClickListener(v -> {
                String currentVer = "4.0.0";
                try {
                    currentVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                } catch (Exception e) {}

                AppUpdateManager updateManager = new AppUpdateManager(
                        this,
                        currentVer,
                        msg -> {
                            runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
                            return null;
                        },
                        (latestTag, releaseNotes, apkUrl) -> {
                            runOnUiThread(() -> showUpdateAvailableDialog(latestTag, releaseNotes, apkUrl));
                            return null;
                        }
                );
                updateManager.checkForUpdates(false);
            });
        }
    }

    private void showUpdateAvailableDialog(String latestTag, String releaseNotes, String apkUrl) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("RolloPrint update available (v" + latestTag + ")")
                .setMessage(releaseNotes)
                .setPositiveButton(R.string.update_now, (dialog, which) -> {
                    String currentVer = "4.0.0";
                    try { currentVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) {}
                    AppUpdateManager updateManager = new AppUpdateManager(this, currentVer, s -> null, (t, n, u) -> null);
                    updateManager.downloadAndInstallApk(apkUrl, msg -> {
                        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
                        return null;
                    });
                })
                .setNegativeButton(R.string.ignore, null)
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        TextInputEditText etEtherpadUrl = findViewById(R.id.etEtherpadUrl);
        TextInputEditText etEtherpadApiKey = findViewById(R.id.etEtherpadApiKey);

        if (etEtherpadUrl != null && etEtherpadUrl.getText() != null) {
            prefs.edit().putString("PREF_ETHERPAD_URL", etEtherpadUrl.getText().toString().trim()).apply();
        }
        if (etEtherpadApiKey != null && etEtherpadApiKey.getText() != null) {
            prefs.edit().putString("PREF_ETHERPAD_API_KEY", etEtherpadApiKey.getText().toString().trim()).apply();
        }
    }

    private void applyAppTheme(int themeMode) {
        switch (themeMode) {
            case 1:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case 2:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }
}
