package org.telegram.messenger;

import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

public class NullgramConfig {

    private static final String PREFS = "nullgram_config";

    public static final int SEASON_OFF = 0, SEASON_NEW_YEAR = 1, SEASON_HALLOWEEN = 2;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    public static boolean noAds() {
        return prefs().getBoolean("no_ads", true);
    }

    public static void setNoAds(boolean v) {
        prefs().edit().putBoolean("no_ads", v).apply();
    }

    public static boolean fastDownloads() {
        return prefs().getBoolean("fast_downloads", true);
    }

    public static void setFastDownloads(boolean v) {
        prefs().edit().putBoolean("fast_downloads", v).apply();
    }

    public static boolean saveDisappearing() {
        return prefs().getBoolean("save_disappearing", true);
    }

    public static void setSaveDisappearing(boolean v) {
        prefs().edit().putBoolean("save_disappearing", v).apply();
    }

    public static boolean editHistory() {
        return prefs().getBoolean("edit_history", true);
    }

    public static void setEditHistory(boolean v) {
        prefs().edit().putBoolean("edit_history", v).apply();
    }

    public static int season() {
        return prefs().getInt("season", SEASON_OFF);
    }

    public static void setSeason(int v) {
        prefs().edit().putInt("season", v).apply();
    }

    public static boolean seasonEffects() {
        return prefs().getBoolean("season_effects", true);
    }

    public static void setSeasonEffects(boolean v) {
        prefs().edit().putBoolean("season_effects", v).apply();
    }

    public static boolean cleanLinks() {
        return prefs().getBoolean("clean_links", true);
    }

    public static void setCleanLinks(boolean v) {
        prefs().edit().putBoolean("clean_links", v).apply();
    }

    public static java.util.List<String> snippets() {
        final String raw = prefs().getString("snippets", "");
        final java.util.ArrayList<String> list = new java.util.ArrayList<>();
        if (!raw.isEmpty()) {
            for (String s : raw.split("\u0001")) {
                if (!s.isEmpty()) list.add(s);
            }
        }
        return list;
    }

    public static void setSnippets(java.util.List<String> list) {
        prefs().edit().putString("snippets", android.text.TextUtils.join("\u0001", list)).apply();
    }

    private static final Set<String> silent = new HashSet<>();
    private static boolean silentLoaded;

    private static synchronized void loadSilent() {
        if (!silentLoaded) {
            silent.addAll(prefs().getStringSet("silent_chats", new HashSet<String>()));
            silentLoaded = true;
        }
    }

    public static synchronized boolean isSilentChat(long dialogId) {
        loadSilent();
        return silent.contains(Long.toString(dialogId));
    }

    public static synchronized void setSilentChat(long dialogId, boolean on) {
        loadSilent();
        if (on) silent.add(Long.toString(dialogId)); else silent.remove(Long.toString(dialogId));
        prefs().edit().putStringSet("silent_chats", new HashSet<>(silent)).apply();
    }

    private static boolean flag(String k, boolean d) { return prefs().getBoolean(k, d); }
    private static void flag(String k, boolean v, boolean unused) { prefs().edit().putBoolean(k, v).apply(); }

    public static boolean hideStories() { return flag("hide_stories", false); }
    public static void setHideStories(boolean v) { flag("hide_stories", v, true); }
    public static boolean noSwipeReply() { return flag("no_swipe_reply", false); }
    public static void setNoSwipeReply(boolean v) { flag("no_swipe_reply", v, true); }
    public static boolean hidePhone() { return flag("hide_phone", false); }
    public static void setHidePhone(boolean v) { flag("hide_phone", v, true); }
    public static boolean confirmCalls() { return flag("confirm_calls", false); }
    public static void setConfirmCalls(boolean v) { flag("confirm_calls", v, true); }
    public static boolean screenGuard() { return flag("screen_guard", false); }

    public static boolean hideNotificationText() { return flag("hide_notification_text", false); }
    public static void setHideNotificationText(boolean v) { prefs().edit().putBoolean("hide_notification_text", v).apply(); }

    public static boolean animations() { return flag("animations", true); }
    public static void setAnimations(boolean v) { prefs().edit().putBoolean("animations", v).apply(); }

    public static boolean stripExif() { return flag("strip_exif", true); }
    public static void setStripExif(boolean v) { prefs().edit().putBoolean("strip_exif", v).apply(); }

    public static boolean freeTranslate() { return flag("free_translate", true); }
    public static void setFreeTranslate(boolean v) { prefs().edit().putBoolean("free_translate", v).apply(); }

    public static boolean roundBackCamera() { return flag("round_back_camera", false); }
    public static void setRoundBackCamera(boolean v) { prefs().edit().putBoolean("round_back_camera", v).apply(); }

    public static boolean cameraFront() { return flag("camera_front", false); }
    public static void setCameraFront(boolean v) { prefs().edit().putBoolean("camera_front", v).apply(); }
    public static boolean cameraGrid() { return flag("camera_grid", false); }
    public static void setCameraGrid(boolean v) { prefs().edit().putBoolean("camera_grid", v).apply(); }

    public static boolean voiceHq() { return flag("voice_hq", true); }
    public static void setVoiceHq(boolean v) { prefs().edit().putBoolean("voice_hq", v).apply(); }
    public static boolean voiceNoise() { return flag("voice_noise", true); }
    public static void setVoiceNoise(boolean v) { prefs().edit().putBoolean("voice_noise", v).apply(); }
    public static boolean voiceAgc() { return flag("voice_agc", false); }
    public static void setVoiceAgc(boolean v) { prefs().edit().putBoolean("voice_agc", v).apply(); }

    public static boolean showIds() { return flag("show_ids", true); }
    public static void setShowIds(boolean v) { prefs().edit().putBoolean("show_ids", v).apply(); }

    public static boolean showBadge() { return flag("show_badge", true); }
    public static void setShowBadge(boolean v) { prefs().edit().putBoolean("show_badge", v).apply(); }

    public static boolean snow() { return flag("snow", false); }
    public static void setSnow(boolean v) { prefs().edit().putBoolean("snow", v).apply(); }

    public static boolean winterIcons() { return snow() && flag("winter_icons", true); }
    public static void setWinterIcons(boolean v) { prefs().edit().putBoolean("winter_icons", v).apply(); }

    /** true: dots always visible; false: auto, only while the phone is moving. */
    public static boolean motionAlways() { return flag("motion_always", true); }
    public static void setMotionAlways(boolean v) { prefs().edit().putBoolean("motion_always", v).apply(); }

    /** 0 weak, 1 medium, 2 strong. */
    public static int motionSens() { return prefs().getInt("motion_sens", 1); }
    public static void setMotionSens(int v) { prefs().edit().putInt("motion_sens", Math.max(0, Math.min(2, v))).apply(); }

    public static boolean motionCues() { return flag("motion_cues", false); }
    public static void setMotionCues(boolean v) { prefs().edit().putBoolean("motion_cues", v).apply(); }

    public static boolean fastMode() { return flag("fast_mode", true); }
    public static void setFastMode(boolean v) { prefs().edit().putBoolean("fast_mode", v).apply(); }

    public static boolean diagnostics() { return flag("diagnostics", true); }
    public static void setDiagnostics(boolean v) { prefs().edit().putBoolean("diagnostics", v).apply(); }
    public static void setScreenGuard(boolean v) { flag("screen_guard", v, true); }

    public static String phone(String formatted) {
        if (!hidePhone() || formatted == null) {
            return formatted;
        }
        final StringBuilder sb = new StringBuilder();
        int digits = 0;
        for (int i = 0; i < formatted.length(); i++) {
            final char c = formatted.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(digits++ < 1 ? c : '\u2022');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
