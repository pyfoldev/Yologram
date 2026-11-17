package org.telegram.messenger;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** Updates delivered by the Nullgram server: /update/latest.json describes the build, /update/app.apk is the file. */
public class NullgramUpdater {

    public static class Info {
        public long versionCode;
        public String versionName;
        public long size;
        public String sha256;
        public String notes;
    }

    public interface CheckCallback {
        void onResult(Info newer, boolean upToDate, String error);
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onDone(File apk, String error);
    }

    /** The newest build the server described on the last successful check, newer than the installed one or not. */
    public static volatile Info latest;

    public static long installedCode() {
        try {
            final PackageInfo pi = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String installedName() {
        return BuildVars.BUILD_VERSION_STRING;
    }

    private static File apkFile() {
        final File dir = new File(ApplicationLoader.getFilesDirFixed(), "cache");
        dir.mkdirs();
        return new File(dir, "nullgram-update.apk");
    }

    public static void check(final CheckCallback cb) {
        if (NullgramSync.BASE.isEmpty()) {
            cb.onResult(null, false, "No update server configured");
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            Info newer = null;
            boolean upToDate = false;
            String error = null;
            try {
                final HttpURLConnection c = (HttpURLConnection) new URL(NullgramSync.BASE + "/update/latest.json").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                final int code = c.getResponseCode();
                if (code == 404) {
                    upToDate = true;
                } else if (code == 200) {
                    final JSONObject o = new JSONObject(new String(readAll(c.getInputStream()), "UTF-8"));
                    final Info i = new Info();
                    i.versionCode = o.getLong("versionCode");
                    i.versionName = o.optString("versionName", "");
                    i.size = o.optLong("size", 0);
                    i.sha256 = o.optString("sha256", "");
                    i.notes = o.optString("notes", "");
                    latest = i;
                    if (i.versionCode > installedCode()) {
                        newer = i;
                    } else {
                        upToDate = true;
                    }
                } else {
                    error = "HTTP " + code;
                }
                c.disconnect();
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
            final Info fn = newer;
            final boolean fu = upToDate;
            final String fe = error;
            AndroidUtilities.runOnUIThread(() -> cb.onResult(fn, fu, fe));
        });
    }

    public static void download(final Info info, final DownloadCallback cb) {
        Utilities.globalQueue.postRunnable(() -> {
            String error = null;
            final File out = apkFile();
            try {
                if (!(out.exists() && info.sha256.equalsIgnoreCase(sha256(out)))) {
                    final HttpURLConnection c = (HttpURLConnection) new URL(NullgramSync.BASE + "/update/app.apk").openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(30000);
                    if (c.getResponseCode() != 200) {
                        throw new Exception("HTTP " + c.getResponseCode());
                    }
                    final long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : info.size;
                    final File tmp = new File(out.getParentFile(), "nullgram-update.part");
                    try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(tmp)) {
                        final byte[] buf = new byte[128 * 1024];
                        long got = 0;
                        int last = -1;
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            fo.write(buf, 0, n);
                            got += n;
                            final int p = total > 0 ? (int) (got * 100 / total) : 0;
                            if (p != last) {
                                last = p;
                                final int fp = p;
                                AndroidUtilities.runOnUIThread(() -> cb.onProgress(fp));
                            }
                        }
                    }
                    c.disconnect();
                    if (!info.sha256.isEmpty() && !info.sha256.equalsIgnoreCase(sha256(tmp))) {
                        tmp.delete();
                        throw new Exception("Checksum mismatch");
                    }
                    out.delete();
                    if (!tmp.renameTo(out)) {
                        throw new Exception("Cannot save update");
                    }
                }
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
            final String fe = error;
            AndroidUtilities.runOnUIThread(() -> cb.onDone(fe == null ? out : null, fe));
        });
    }

    /** Returns false when the user first has to allow installs from this app (the settings screen is opened). */
    public static boolean install(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
            return false;
        }
        final Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".provider", apk);
        final Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(i);
        return true;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        final java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    private static String sha256(File f) throws Exception {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) {
            final byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                md.update(buf, 0, n);
            }
        }
        final StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
