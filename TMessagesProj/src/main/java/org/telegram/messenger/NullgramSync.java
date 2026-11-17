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
    private static final HashMap<Long, Long> musicStamps = new HashMap<>();
    private static final int MAX_MUSIC = 2_900_000;

    /** Profile music of the selected account (one short track, up to about 3 MB). */
    public static File musicFile() {
        return new File(ApplicationLoader.getFilesDirFixed(), "profile_music_" + myId() + ".audio");
    }

    /** True once a fetch showed that this user published a profile track. */
    public static boolean hasMusic(long uid) {
        final Long v = musicStamps.get(uid);
        return v != null && v > 0;
    }

    /** Copies a track the user picked, checks the size and sends it to the server. Returns false when it is refused. */
    public static boolean importMusic(android.content.Context context, android.net.Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            final ByteArrayOutputStream bo = new ByteArrayOutputStream();
            final byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_MUSIC) {
                    return false;
                }
            }
            if (bo.size() < 1024) {
                return false;
            }
            try (FileOutputStream out = new FileOutputStream(musicFile())) {
                bo.writeTo(out);
            }
            put("/music", bo.toByteArray());
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public static void removeMusic() {
        musicFile().delete();
        put("/music", new byte[0]);
    }

    public interface FileCallback {
        void onResult(File file);
    }

    /** Downloads another user's profile track into the cache (kept while the server's version stamp is unchanged). */
    public static void fetchMusic(final long uid, final FileCallback cb) {
        if (BASE.isEmpty()) {
            cb.onResult(null);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            File result = null;
            try {
                final File f = new File(ApplicationLoader.applicationContext.getCacheDir(), "ng_music_" + uid + ".audio");
                final long stamp = musicStamps.containsKey(uid) ? musicStamps.get(uid) : 0;
                final String key = "music_ts_" + uid;
                if (f.exists() && stamp > 0 && prefs().getLong(key, 0) == stamp) {
                    result = f;
                } else {
                    final HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/u/" + uid + "/music").openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(20000);
                    if (c.getResponseCode() == 200) {
                        try (FileOutputStream out = new FileOutputStream(f)) {
                            out.write(readAll(c.getInputStream()));
                        }
                        prefs().edit().putLong(key, stamp).apply();
                        result = f;
                    }
                    c.disconnect();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            final File done = result;
            AndroidUtilities.runOnUIThread(() -> cb.onResult(done));
        });
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nullgram_sync", 0);
    }

    /** Banner of the currently selected account; the old single shared file moves to the first account that asks. */
    public static File bannerFile() {
        final File dir = ApplicationLoader.getFilesDirFixed();
        final File f = new File(dir, "profile_banner_" + myId() + ".jpg");
        final File legacy = new File(dir, "profile_banner.jpg");
        if (!f.exists() && legacy.exists()) {
            legacy.renameTo(f);
        }
        return f;
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
        put(myId(), sub, body);
    }

    /** Tells the server this Telegram account runs Nullgram (and whether to show the badge). Hidden accounts stay silent. */
    /** After the badge setting changed: announce again right away. */
    public static void refreshHello() {
        prefs().edit().remove("hello_at").apply();
        hello();
    }

    public static void hello() {
        final long now = System.currentTimeMillis();
        final boolean show = NullgramConfig.showBadge();
        if (prefs().getBoolean("hello_show", !show) == show && now - prefs().getLong("hello_at", 0) < 6 * 3600_000L) {
            return;
        }
        final byte[] body = (NullgramConfig.showBadge() ? "1" : "0").getBytes();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final UserConfig uc = UserConfig.getInstance(a);
            if (uc != null && uc.isClientActivated() && !NullgramHidden.isHidden(a)) {
                put(uc.getClientUserId(), "/hello", body);
            }
        }
    }

    private static void put(final long me, final String sub, final byte[] body) {
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
                    if ("/hello".equals(sub)) {
                        // only a ping the server really answered counts: a failed one is retried next launch
                        prefs().edit().putLong("hello_at", System.currentTimeMillis()).putBoolean("hello_show", body.length > 0 && body[0] == '1').apply();
                    }
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
        final File f = bannerFile();
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
        if (cached != null && System.currentTimeMillis() - (Long) cached[0] < 15_000L) {
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
                    musicStamps.put(uid, o.optLong("music_ts", 0));
                    thought = o.isNull("thought") ? null : o.optString("thought", null);
                    final long bts = o.optLong("banner_ts", 0);
                    if (bts == 0) {
                        new File(ApplicationLoader.applicationContext.getCacheDir(), "ng_banner_" + uid + ".jpg").delete();
                    }
                    if (bts > 0) {
                        final File f = new File(ApplicationLoader.applicationContext.getCacheDir(), "ng_banner_" + uid + ".jpg");
                        // compare the server's own version stamp: the phone's clock may be ahead of or behind the server's
                        final String tsKey = "banner_ts_" + uid;
                        if (!f.exists() || prefs().getLong(tsKey, 0) != bts) {
                            HttpURLConnection bc = (HttpURLConnection) new URL(BASE + "/u/" + uid + "/banner.jpg").openConnection();
                            bc.setConnectTimeout(6000);
                            bc.setReadTimeout(15000);
                            if (bc.getResponseCode() == 200) {
                                try (FileOutputStream out = new FileOutputStream(f)) {
                                    out.write(readAll(bc.getInputStream()));
                                }
                            }
                            bc.disconnect();
                            prefs().edit().putLong(tsKey, bts).apply();
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
