package org.telegram.messenger;

import android.content.SharedPreferences;

public class Thoughts {

    public static final int MAX_LENGTH = 60;
    private static final long LIFETIME_MS = 24L * 60 * 60 * 1000;

    private static String k(String name) {
        return name + "_" + UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nullgram_thoughts", 0);
    }

    public static String get() {
        final String text = prefs().getString(k("text"), null);
        if (text == null) {
            return null;
        }
        if (System.currentTimeMillis() - prefs().getLong(k("time"), 0) >= LIFETIME_MS) {
            clear();
            return null;
        }
        return text;
    }

    public static void set(String text) {
        final String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            clear();
            return;
        }
        prefs().edit().putString(k("text"), t.length() > MAX_LENGTH ? t.substring(0, MAX_LENGTH) : t).putLong(k("time"), System.currentTimeMillis()).apply();
    }

    public static void clear() {
        prefs().edit().remove(k("text")).remove(k("time")).apply();
    }

    public static int hoursLeft() {
        if (get() == null) {
            return 0;
        }
        final long left = LIFETIME_MS - (System.currentTimeMillis() - prefs().getLong(k("time"), 0));
        return (int) Math.max(1, (left + 3_599_999) / 3_600_000);
    }
}
