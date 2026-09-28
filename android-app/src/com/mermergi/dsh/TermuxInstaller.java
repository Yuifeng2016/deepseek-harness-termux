package com.mermergi.dsh;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Installs the Termux APK bundled in our own assets, so a fresh phone never needs to
 * download it. The APK is extracted to cache and handed to the system package installer
 * through {@link ApkProvider} (a tiny content provider of our own — this project has no
 * androidx, so FileProvider is not an option).
 *
 * Source of the bundled file: f-droid.org com.termux_1002.apk (Termux 0.118.3),
 * downloaded at build time from a mirror; see android-app/tools/build.sh.
 */
final class TermuxInstaller {

    static final String ASSET_NAME = "termux.apk";
    static final String AUTHORITY = "com.mermergi.dsh.apk";
    static final String TERMUX_VERSION = "0.118.3";

    /** Mirror first: f-droid.org itself drops long transfers on flaky networks (measured). */
    static final String[] FALLBACK_URLS = {
            "https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo/com.termux_1002.apk",
            "https://f-droid.org/repo/com.termux_1002.apk",
    };

    private TermuxInstaller() {
    }

    static File cachedApk(Context context) {
        return new File(context.getCacheDir(), "termux-" + TERMUX_VERSION + ".apk");
    }

    /**
     * Copies the bundled APK out of assets. Writes to a temp file and renames, so a
     * half-copied file can never be mistaken for a complete one.
     *
     * @return the extracted APK, or null on any failure.
     */
    static File extract(Context context) {
        InputStream in = null;
        OutputStream os = null;
        try {
            File out = cachedApk(context);
            File tmp = new File(context.getCacheDir(), out.getName() + ".tmp");
            in = context.getAssets().open(ASSET_NAME);
            os = new FileOutputStream(tmp);
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
            os = null;
            if (!tmp.renameTo(out)) {
                // Leftover target from an earlier run would block the rename.
                out.delete();
                if (!tmp.renameTo(out)) return null;
            }
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (os != null) os.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * Extracts and hands the bundled APK to the system installer. Must be called on the
     * UI thread for the startActivity calls; the heavy part runs on the app's io executor
     * via {@code runInBackground}.
     *
     * @param runInBackground   executor hook (MainActivity's io queue) for the extraction.
     * @param onUi              marshals back to the UI thread.
     * @param onExtractionFailed called when the asset could not be extracted; the caller
     *                          should offer the browser fallback.
     */
    static void installBundled(final Activity activity,
                               java.util.concurrent.Executor runInBackground,
                               android.os.Handler onUi,
                               final Runnable onExtractionFailed) {
        // API 26+: sideloading requires the per-app "install unknown apps" toggle. The
        // settings screen is where the user flips it; they come back and tap again.
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            askForInstallPermission(activity);
            return;
        }
        runInBackground.execute(new Runnable() {
            @Override
            public void run() {
                final File apk = extract(activity);
                onUi.post(new Runnable() {
                    @Override
                    public void run() {
                        if (apk == null || !apk.isFile() || apk.length() == 0) {
                            onExtractionFailed.run();
                            return;
                        }
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setDataAndType(
                                    Uri.parse("content://" + AUTHORITY + "/" + apk.getName()),
                                    "application/vnd.android.package-archive");
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            activity.startActivity(intent);
                        } catch (Throwable t) {
                            onExtractionFailed.run();
                        }
                    }
                });
            }
        });
    }

    static void askForInstallPermission(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Throwable t) {
            // Some vendors bury the toggle; the browser fallback still works.
            openFallbackInBrowser(activity, 0);
        }
    }

    static void openFallbackInBrowser(Activity activity, int urlIndex) {
        try {
            String url = FALLBACK_URLS[Math.max(0, Math.min(urlIndex, FALLBACK_URLS.length - 1))];
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignored) {
        }
    }
}
