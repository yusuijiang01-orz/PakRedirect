package com.example.pakredirect;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;

/** A standalone debug entry point for one controlled BlackBox hook diagnostic. */
public final class BlackBoxHookDiagnosticActivity extends Activity {
    private static final String TAG = "BlackBoxHookDiag";
    private static final String GAME_PACKAGE = "com.tepaylink.tamgioiphantranhmobile";
    private static final String APK_NAME = "TamGioiPhanTranhMobile-587.apk";
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        status = new TextView(this);
        status.setTextSize(16);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        status.setPadding(padding, padding, padding, padding);
        ScrollView screen = new ScrollView(this);
        screen.addView(status);
        setContentView(screen);

        report("BlackBox hook diagnostic started in " + getPackageName());
        if (!getPackageName().endsWith(".hookdiag")) {
            report("Stopped: this activity requires the isolated .hookdiag application ID.");
            return;
        }

        RyluxApplication application = (RyluxApplication) getApplication();
        if (!application.isBlackBoxReady()) {
            report("Stopped: BlackBox startup failed: " + application.getBlackBoxError());
            return;
        }

        report("BlackBox is ready.");
        new Thread(this::importAndLaunch, "BlackBoxHookDiag").start();
    }

    private void importAndLaunch() {
        try {
            BlackBoxCore core = BlackBoxCore.get();
            boolean installed = core.isInstalled(GAME_PACKAGE, 0);
            ApplicationInfo guest = core.getBPackageManager().getApplicationInfo(GAME_PACKAGE, 0, 0);
            Intent launchIntent = core.getBPackageManager().getLaunchIntentForPackage(GAME_PACKAGE, 0);
            if (installed && (guest == null || launchIntent == null)) {
                report("Virtual install metadata is incomplete; resetting isolated diagnostic install.");
                core.uninstallPackageAsUser(GAME_PACKAGE, 0);
                installed = false;
            }

            if (installed) {
                report("Game is installed for virtual user 0.");
            } else {
                File externalFiles = getExternalFilesDir(null);
                if (externalFiles == null) {
                    report("Stopped: app external files directory is unavailable.");
                    return;
                }
                File apk = new File(new File(externalFiles, "blackbox-hookdiag"), APK_NAME);
                report("Checking staged APK: " + apk.getAbsolutePath());
                if (!apk.isFile() || apk.length() == 0) {
                    report("Stopped: staged APK is missing or empty.");
                    return;
                }
                PackageInfo archive = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                if (archive == null || !GAME_PACKAGE.equals(archive.packageName)) {
                    report("Stopped: staged APK package does not match " + GAME_PACKAGE + ".");
                    return;
                }
                report("Importing game for virtual user 0...");
                InstallResult result = core.installPackageAsUser(apk, 0);
                if (result == null || !result.success || !core.isInstalled(GAME_PACKAGE, 0)) {
                    report("Stopped: import failed: " + (result == null ? "no result" : result.msg));
                    return;
                }
                report("Game import completed.");
            }

            guest = core.getBPackageManager().getApplicationInfo(GAME_PACKAGE, 0, 0);
            launchIntent = core.getBPackageManager().getLaunchIntentForPackage(GAME_PACKAGE, 0);
            report("Guest source: " + (guest == null ? "unavailable" : guest.sourceDir));
            report("Resolved launcher: " + (launchIntent == null ? "none" : launchIntent.getComponent()));
            if (guest == null || launchIntent == null) {
                report("Stopped: virtual package import did not produce a launchable app.");
                return;
            }
            report("Launching " + GAME_PACKAGE + " for virtual user 0...");
            boolean launched = core.launchApk(GAME_PACKAGE, 0);
            report(launched ? "Launch request accepted." : "Launch request failed.");
            if (launched) {
                // Let the BlackBox proxy activity become foreground. Keeping this
                // diagnostic screen resumed can mask the guest activity transition.
                runOnUiThread(() -> status.postDelayed(this::finish, 1200));
            }
        } catch (Throwable error) {
            Log.i(TAG, "Diagnostic failed", error);
            report("Stopped: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private void report(String message) {
        Log.i(TAG, message);
        runOnUiThread(() -> status.append(message + "\n"));
    }
}
