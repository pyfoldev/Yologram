package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.UUID;

/**
 * Anonymous crash reports and usage statistics, sent to the Nullgram server.
 * Never includes Telegram ids, phone numbers, usernames or any chat content: the report carries a random
 * install id, app/device versions, enabled feature flags and a stack trace with digits and @names scrubbed.
 */
public class Diagnostics {

    private static final int MAX_FILES = 20;
    private static final int MAX_PER_SESSION = 10;
    private static volatile boolean installed;
    private static int sessionErrors;
    private static boolean flushed;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nullgram_diag", 0);
    }

    public static boolean enabled() {
        return NullgramConfig.diagnostics() && !NullgramSync.BASE.isEmpty();
    }

    private static File dir() {
        final File d = new File(ApplicationLoader.getFilesDirFixed(), "diag");
        d.mkdirs();
        return d;
    }

    public static String installId() {
        String id = prefs().getString("install", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs().edit().putString("install", id).apply();
        }
        return id;
    }

    public static void install() {
        if (installed || ApplicationLoader.applicationContext == null) {
            return;
        }
        installed = true;
        final Thread.UncaughtExceptionHandler past = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                if (enabled()) {
                    store("crash", e, thread.getName());
                }
            } catch (Throwable ignore) {
            }
            if (past != null) {
                past.uncaughtException(thread, e);
            }
        });
    }

    /** Called from FileLog.e: remembers a distinct non-fatal error so it can be sent on the next start. */
    public static void nonFatal(Throwable e) {
        try {
            if (!installed || !enabled() || sessionErrors >= MAX_PER_SESSION) {
                return;
            }
            sessionErrors++;
            store("error", e, Thread.currentThread().getName());
        } catch (Throwable ignore) {
        }
    }

    static String scrub(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\d{5,}", "#").replaceAll("@[A-Za-z0-9_]{3,}", "@user").replaceAll("https?://\\S+", "url");
    }

    private static String signature(Throwable e) {
        final StackTraceElement[] st = e.getStackTrace();
        final StringBuilder sb = new StringBuilder(e.getClass().getName());
        for (int i = 0; i < Math.min(3, st.length); i++) {
            sb.append('|').append(st[i].getClassName()).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
        }
        return sb.toString();
    }

    private static void store(String kind, Throwable e, String threadName) throws Exception {
        final String sig = signature(e);
        final File[] existing = dir().listFiles();
        if (existing != null && existing.length >= MAX_FILES) {
            return;
        }
        final File f = new File(dir(), kind + "_" + Integer.toHexString(sig.hashCode()) + ".json");
        if (f.exists()) {
            return;
        }
        final StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String trace = scrub(sw.toString());
        if (trace.length() > 6000) {
            trace = trace.substring(0, 6000);
        }
        final JSONObject o = new JSONObject();
        o.put("kind", kind);
        o.put("ts", System.currentTimeMillis() / 1000);
        o.put("sig", sig);
        o.put("thread", threadName);
        o.put("trace", trace);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(o.toString().getBytes("UTF-8"));
        }
    }

    private static JSONObject common() throws Exception {
        final JSONObject o = new JSONObject();
        o.put("install", installId());
        o.put("version", BuildVars.BUILD_VERSION_STRING);
        o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        o.put("sdk", Build.VERSION.SDK_INT);
        o.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
        o.put("lang", Locale.getDefault().getLanguage());
        final JSONObject f = new JSONObject();
        f.put("no_ads", NullgramConfig.noAds());
        f.put("fast_downloads", NullgramConfig.fastDownloads());
        f.put("save_disappearing", NullgramConfig.saveDisappearing());
        f.put("edit_history", NullgramConfig.editHistory());
        f.put("hide_stories", NullgramConfig.hideStories());
        f.put("no_swipe_reply", NullgramConfig.noSwipeReply());
        f.put("hide_phone", NullgramConfig.hidePhone());
        f.put("confirm_calls", NullgramConfig.confirmCalls());
        f.put("screen_guard", NullgramConfig.screenGuard());
        f.put("ghost", GhostMode.isOn());
        f.put("ws_proxy", WsProxy.isEnabled());
        f.put("accounts", UserConfig.getActivatedAccountsCount());
        o.put("features", f);
        return o;
    }

    private static boolean post(JSONObject body) {
        try {
            final byte[] data = body.toString().getBytes("UTF-8");
            final HttpURLConnection c = (HttpURLConnection) new URL(NullgramSync.BASE + "/diag").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(data.length);
            c.getOutputStream().write(data);
            final int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Once per process: send the daily ping and any stored reports. Silent when disabled or offline. */
    public static void flush() {
        if (flushed || !enabled()) {
            return;
        }
        flushed = true;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final long day = System.currentTimeMillis() / 86_400_000L;
                if (prefs().getLong("ping_day", -1) != day) {
                    final JSONObject ping = common();
                    ping.put("kind", "ping");
                    ping.put("ts", System.currentTimeMillis() / 1000);
                    if (post(ping)) {
                        prefs().edit().putLong("ping_day", day).apply();
                    }
                }
                final File[] files = dir().listFiles();
                if (files == null) {
                    return;
                }
                for (File f : files) {
                    final byte[] raw = new byte[(int) Math.min(f.length(), 20000)];
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                        final int n = in.read(raw);
                        final JSONObject o = common();
                        final JSONObject rep = new JSONObject(new String(raw, 0, Math.max(n, 0), "UTF-8"));
                        final java.util.Iterator<String> it = rep.keys();
                        while (it.hasNext()) {
                            final String k = it.next();
                            o.put(k, rep.get(k));
                        }
                        if (post(o)) {
                            f.delete();
                        }
                    } catch (Throwable t) {
                        f.delete();
                    }
                }
            } catch (Throwable ignore) {
            }
        });
    }
}
