package org.telegram.messenger;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;

public class NullgramSync {

    public static final String BASE = BuildConfig.NG_SYNC_URL;

    public interface Callback {
        void onResult(String thought, File banner);
    }

    private static final HashMap<Long, Object[]> cache = new HashMap<>();

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nullgram_sync", 0);
    }

    private static long myId() {
        return UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
    }

    public static void pushThought(String text) {
        put("/thought", text == null ? new byte[0] : text.getBytes());
    }

    public static void pushBanner(File jpeg) {
        byte[] data = new byte[0];
        if (jpeg != null) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(jpeg)) {
                data = readAll(in);
            } catch (Exception e) {
                FileLog.e(e);
                return;
            }
            if (data.length > 1_400_000) {
                data = shrink(data);
                if (data == null) {
                    return;
                }
            }
        }
        put("/banner", data);
    }

    private static byte[] shrink(byte[] jpeg) {
        try {
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (b == null) {
                return null;
            }
            int q = 80;
            while (q >= 40) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                b.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, out);
                if (out.size() <= 1_400_000) {
                    return out.toByteArray();
                }
                q -= 10;
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return null;
    }

    private static void put(final String sub, final byte[] body) {
        final long me = myId();
        if (me == 0 || BASE.isEmpty()) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/u/" + me + sub).openConnection();
                c.setRequestMethod("PUT");
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setDoOutput(true);
                final String token = prefs().getString("token_" + me, "");
                c.setRequestProperty("X-Token", token);
                c.setFixedLengthStreamingMode(body.length);
                c.getOutputStream().write(body);
                if (c.getResponseCode() == 200) {
                    final JSONObject o = new JSONObject(new String(readAll(c.getInputStream())));
                    final String issued = o.optString("token", "");
                    if (!issued.isEmpty() && !"null".equals(issued)) {
                        prefs().edit().putString("token_" + me, issued).apply();
                    }
                }
                c.disconnect();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public static void pushAll() {
        final String t = Thoughts.get();
        if (t != null) {
            pushThought(t);
        }
        final File f = new File(ApplicationLoader.getFilesDirFixed(), "profile_banner.jpg");
        if (f.exists()) {
            pushBanner(f);
        }
    }

    public static void fetch(final long uid, final Callback cb) {
        if (BASE.isEmpty()) {
            cb.onResult(null, null);
            return;
        }
        final Object[] cached = cache.get(uid);
        if (cached != null && System.currentTimeMillis() - (Long) cached[0] < 5 * 60_000L) {
            cb.onResult((String) cached[1], (File) cached[2]);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            String thought = null;
            File banner = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/u/" + uid).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() == 200) {
                    final JSONObject o = new JSONObject(new String(readAll(c.getInputStream())));
                    thought = o.isNull("thought") ? null : o.optString("thought", null);
                    final long bts = o.optLong("banner_ts", 0);
                    if (bts > 0) {
                        final File f = new File(ApplicationLoader.applicationContext.getCacheDir(), "ng_banner_" + uid + ".jpg");
                        if (!f.exists() || f.lastModified() / 1000 < bts) {
                            HttpURLConnection bc = (HttpURLConnection) new URL(BASE + "/u/" + uid + "/banner.jpg").openConnection();
                            bc.setConnectTimeout(6000);
                            bc.setReadTimeout(15000);
                            if (bc.getResponseCode() == 200) {
                                try (FileOutputStream out = new FileOutputStream(f)) {
                                    out.write(readAll(bc.getInputStream()));
                                }
                            }
                            bc.disconnect();
                        }
                        if (f.exists()) {
                            banner = f;
                        }
                    }
                }
                c.disconnect();
            } catch (Exception e) {
                FileLog.e(e);
            }
            final String t = thought;
            final File b = banner;
            cache.put(uid, new Object[]{System.currentTimeMillis(), t, b});
            AndroidUtilities.runOnUIThread(() -> cb.onResult(t, b));
        });
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
