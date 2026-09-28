package com.mermergi.dsh;

import android.content.Context;
import android.content.pm.PackageInfo;

/** What we can tell about the Termux app from outside it. */
final class TermuxEnv {

    static final String PACKAGE = "com.termux";

    /** RUN_COMMAND through RunCommandService needs 0.118 or newer. */
    static final int MIN_MAJOR = 0;
    static final int MIN_MINOR = 118;

    private TermuxEnv() {
    }

    static boolean isInstalled(Context context) {
        return versionName(context) != null;
    }

    /** @return versionName, or null when Termux is not installed / not visible to us. */
    static String versionName(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return pi.versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return whether the installed Termux supports the RUN_COMMAND service (0.118+). */
    static boolean isRecentEnough(Context context) {
        String v = versionName(context);
        if (v == null) return false;
        try {
            // Strip anything after the numeric prefix, e.g. "0.118.3-beta" → "0.118.3".
            String[] parts = v.replaceAll("^[^0-9]*", "").split("[^0-9]+");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > MIN_MAJOR || (major == MIN_MAJOR && minor >= MIN_MINOR);
        } catch (Throwable t) {
            // Unparseable version → assume the Play-Store era build and show guidance.
            return false;
        }
    }
}
