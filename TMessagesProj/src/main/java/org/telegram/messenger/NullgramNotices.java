package org.telegram.messenger;

import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Locale;

/**
 * "Yologram" service notifications: the official-looking system chat (formerly "Telegram") receives the change log
 * after an update and a note when a newer build is on the server. Messages are injected as service notifications,
 * so they appear in the chat and raise a normal notification.
 */
public class NullgramNotices {

    private static final long POLL_EVERY_MS = 3 * 3600_000L;
    private static final long SERVICE_ID = 777000;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nullgram_notices", 0);
    }

    private static boolean ru() {
        return "ru".equals(Locale.getDefault().getLanguage());
    }

    private static final long NOTICES_EVERY_MS = 20_000L;
    private static boolean foreground;
    private static final Runnable FAST = new Runnable() {
        @Override
        public void run() {
            if (!foreground) {
                return;
            }
            pollNotices();
            AndroidUtilities.runOnUIThread(this, NOTICES_EVERY_MS);
        }
    };

    /** While the app is on screen the server is asked for new messages every 20 seconds. */
    public static void setForeground(boolean on) {
        if (on == foreground) {
            return;
        }
        foreground = on;
        AndroidUtilities.cancelRunOnUIThread(FAST);
        if (on && !NullgramSync.BASE.isEmpty()) {
            AndroidUtilities.runOnUIThread(FAST, 2000);
        }
    }

    /** Messages sent by hand from the server (send-notice.sh): checked every 20 seconds while the app is open. */
    private static void pollNotices() {
        final long now = System.currentTimeMillis();
        if (now - prefs().getLong("notices_at", 0) < NOTICES_EVERY_MS) {
            return;
        }
        prefs().edit().putLong("notices_at", now).apply();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final long last = prefs().getLong("notice_id", -1);
                if (last < 0) {
                    // first run: skip the history, only messages sent from now on are shown
                    final org.json.JSONObject o = getJson("/notices?latest=1");
                    if (o != null) {
                        prefs().edit().putLong("notice_id", o.optLong("latest", 0)).apply();
                    }
                    return;
                }
                final org.json.JSONObject o = getJson("/notices?since=" + last);
                if (o == null) {
                    return;
                }
                final org.json.JSONArray arr = o.optJSONArray("notices");
                long max = last;
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    final org.json.JSONObject n = arr.getJSONObject(i);
                    final String text = n.optString("text", "").trim();
                    if (!text.isEmpty()) {
                        broadcast(text);
                    }
                    max = Math.max(max, n.optLong("id", max));
                }
                if (max != last) {
                    prefs().edit().putLong("notice_id", max).apply();
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
    }

    private static org.json.JSONObject getJson(String path) throws Exception {
        final java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(NullgramSync.BASE + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        try {
            if (c.getResponseCode() != 200) {
                return null;
            }
            final java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[4096];
            int n;
            try (java.io.InputStream in = c.getInputStream()) {
                while ((n = in.read(buf)) >= 0) {
                    bo.write(buf, 0, n);
                }
            }
            return new org.json.JSONObject(bo.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    /** Called when the app comes to the foreground. */
    public static void poll() {
        if (NullgramSync.BASE.isEmpty()) {
            return;
        }
        pollNotices();
        final long now = System.currentTimeMillis();
        if (now - prefs().getLong("polled_at", 0) < POLL_EVERY_MS) {
            return;
        }
        prefs().edit().putLong("polled_at", now).apply();
        NullgramUpdater.check((newer, upToDate, error) -> {
            if (error != null) {
                prefs().edit().remove("polled_at").apply();
                return;
            }
            final long installed = NullgramUpdater.installedCode();
            final long seen = prefs().getLong("seen_code", 0);
            if (seen == 0 && !hasSignedInAccount()) {
                // a fresh install that nobody has signed in to yet: nothing to announce
                prefs().edit().putLong("seen_code", installed).apply();
            } else if (seen == 0 || installed > seen) {
                prefs().edit().putLong("seen_code", installed).apply();
                final NullgramUpdater.Info info = NullgramUpdater.latest;
                if (info != null && info.versionCode == installed) {
                    broadcast(updatedText(info));
                } else {
                    broadcast(ru()
                            ? "✅ Yologram обновлён до версии " + NullgramUpdater.installedName() + "."
                            : "✅ Yologram was updated to version " + NullgramUpdater.installedName() + ".");
                }
            }
            if (newer != null && prefs().getLong("announced_code", 0) < newer.versionCode) {
                prefs().edit().putLong("announced_code", newer.versionCode).apply();
                broadcast(availableText(newer));
            }
        });
    }

    private static boolean hasSignedInAccount() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final UserConfig uc = UserConfig.getInstance(a);
            if (uc != null && uc.isClientActivated()) {
                return true;
            }
        }
        return false;
    }

    private static String updatedText(NullgramUpdater.Info info) {
        final String notes = info.notes == null ? "" : info.notes.trim();
        if (ru()) {
            return "✅ Yologram обновлён до версии " + info.versionName + "\n\nЧто нового:\n" + (notes.isEmpty() ? "Исправления и улучшения." : notes);
        }
        return "✅ Yologram was updated to version " + info.versionName + "\n\nWhat's new:\n" + (notes.isEmpty() ? "Bug fixes and improvements." : notes);
    }

    private static String availableText(NullgramUpdater.Info info) {
        final String notes = info.notes == null ? "" : info.notes.trim();
        if (ru()) {
            return "🆕 Доступно обновление Yologram " + info.versionName + "\n\n" + (notes.isEmpty() ? "Исправления и улучшения." : notes)
                    + "\n\nОбновить можно в Настройках Yologram → «Обновления и диагностика» → «Проверить обновления».";
        }
        return "🆕 Yologram " + info.versionName + " is available\n\n" + (notes.isEmpty() ? "Bug fixes and improvements." : notes)
                + "\n\nUpdate in Yologram settings → Updates & diagnostics → Check for updates.";
    }

    private static void broadcast(final String text) {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                final UserConfig uc = UserConfig.getInstance(a);
                if (uc != null && uc.isClientActivated() && !NullgramHidden.isHidden(a)) {
                    post(a, text);
                }
            }
        });
    }

    private static void post(int account, String text) {
        try {
            final MessagesController mc = MessagesController.getInstance(account);
            final TL_update.TL_updateServiceNotification u = new TL_update.TL_updateServiceNotification();
            u.flags = 2;
            u.inbox_date = (int) (System.currentTimeMillis() / 1000);
            u.type = "yologram";
            u.message = text;
            u.popup = false;
            final ArrayList<TLRPC.Update> updates = new ArrayList<>();
            updates.add(u);
            final ArrayList<TLRPC.User> users = new ArrayList<>();
            final TLRPC.User service = mc.getUser(SERVICE_ID);
            if (service != null) {
                users.add(service);
            }
            mc.processUpdateArray(updates, users, null, false, 0);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
