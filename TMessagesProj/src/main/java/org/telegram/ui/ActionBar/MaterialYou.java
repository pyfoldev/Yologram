package org.telegram.ui.ActionBar;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.SparseIntArray;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

public class MaterialYou {

    public static final int R_PRIMARY = 0, R_ON_PRIMARY = 1, R_PRIMARY_CONTAINER = 2, R_ON_PRIMARY_CONTAINER = 3,
        R_SECONDARY = 4, R_SECONDARY_CONTAINER = 5, R_ON_SECONDARY_CONTAINER = 6,
        R_SURFACE = 7, R_SURFACE_LOW = 8, R_SURFACE_CONTAINER = 9, R_SURFACE_HIGH = 10, R_SURFACE_HIGHEST = 11,
        R_ON_SURFACE = 12, R_ON_SURFACE_VARIANT = 13, R_OUTLINE = 14, R_OUTLINE_VARIANT = 15,
        R_ERROR = 16, R_ON_ERROR = 17, R_ERROR_CONTAINER = 18, R_BLACK = 19, ROLE_COUNT = 20;

    public static final int DEFAULT_SEED = 0xFF6750A4;
    public static final int[] PRESETS = {
        0xFF6750A4, 0xFF3B5BDB, 0xFF0B7285, 0xFF2B8A3E, 0xFF66A80F, 0xFFF08C00,
        0xFFE8590C, 0xFFD6336C, 0xFFC2255C, 0xFF9C36B5, 0xFF495057, 0xFF1C7ED6,
    };

    private static final String PREFS = "nullgram_material_you";
    private static boolean loaded;
    private static boolean enabled = true;
    private static boolean amoled;
    private static int seed = DEFAULT_SEED;
    private static float cornerScale = 1f;

    private static final SparseIntArray roleOfKey = new SparseIntArray();
    private static volatile SparseIntArray lightTable = new SparseIntArray();
    private static volatile SparseIntArray darkTable = new SparseIntArray();

    static {
        buildKeyMap();
    }

    public static boolean isEnabled() {
        ensureLoaded();
        return enabled;
    }

    public static int getSeed() {
        ensureLoaded();
        return seed;
    }

    public static boolean isAmoled() {
        ensureLoaded();
        return amoled;
    }

    public static float getCornerScale() {
        ensureLoaded();
        return cornerScale;
    }

    public static SparseIntArray table() {
        ensureLoaded();
        if (!enabled) {
            return null;
        }
        return Theme.isCurrentThemeDark() ? darkTable : lightTable;
    }

    public static void setSeed(int color) {
        ensureLoaded();
        seed = color | 0xFF000000;
        commit();
    }

    public static void setAmoled(boolean value) {
        ensureLoaded();
        amoled = value;
        commit();
    }

    public static void setEnabled(boolean value) {
        ensureLoaded();
        enabled = value;
        commit();
    }

    public static void setCornerScale(float value) {
        ensureLoaded();
        cornerScale = Math.max(0.3f, Math.min(1.6f, value));
        commit();
    }

    public static float corner(float base) {
        return base * getCornerScale();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (MaterialYou.class) {
            if (loaded) {
                return;
            }
            SharedPreferences p = prefs();
            enabled = p.getBoolean("enabled", true);
            seed = p.getInt("seed", DEFAULT_SEED);
            amoled = p.getBoolean("amoled", false);
            cornerScale = p.getFloat("corner", 1f);
            rebuildTables();
            loaded = true;
        }
    }

    private static void commit() {
        prefs().edit().putBoolean("enabled", enabled).putInt("seed", seed).putBoolean("amoled", amoled).putFloat("corner", cornerScale).apply();
        rebuildTables();
        Theme.refreshThemeColors();
    }

    private static void rebuildTables() {
        tintLightCache = null;
        tintDarkCache = null;
        lightTable = buildTable(false);
        darkTable = buildTable(true);
    }

    private static volatile int[] lightRoles = new int[ROLE_COUNT];
    private static volatile int[] darkRoles = new int[ROLE_COUNT];

    public static int role(int role) {
        ensureLoaded();
        return (Theme.isCurrentThemeDark() ? darkRoles : lightRoles)[role];
    }

    public static int avatarRadius(float halfSizeDp, float plainCircleDp) {
        if (!isEnabled()) {
            return AndroidUtilities.dp(plainCircleDp);
        }
        return Math.round(Math.min(AndroidUtilities.dpf2(halfSizeDp), AndroidUtilities.dpf2(halfSizeDp) * 0.62f * getCornerScale()));
    }

    private static SparseIntArray buildTable(boolean dark) {
        final int[] roles = roles(seed, dark, amoled);
        if (dark) darkRoles = roles; else lightRoles = roles;
        final SparseIntArray t = new SparseIntArray(roleOfKey.size());
        for (int i = 0; i < roleOfKey.size(); i++) {
            final int packed = roleOfKey.valueAt(i);
            final int color = roles[packed & 0xFF];
            final int alpha = packed >>> 8;
            t.put(roleOfKey.keyAt(i), alpha >= 255 ? color : ColorUtils.setAlphaComponent(color, alpha));
        }
        return t;
    }

    private static int[] roles(int seedColor, boolean dark, boolean amoled) {
        final double[] lin = OKLCH.rgb2srgbLinear(OKLCH.rgb(seedColor));
        final double[] lch = OKLCH.oklab2oklch(OKLCH.xyz2oklab(OKLCH.rgbLinear2xyz(lin)));
        final double hue = Double.isNaN(lch[2]) ? 0 : lch[2];
        final double cp = Math.max(0.12, Math.min(0.20, lch[1]));

        final double[] primary = {hue, cp};
        final double[] secondary = {hue, 0.05};
        final double[] neutral = {hue, 0.008};
        final double[] variant = {hue, 0.016};
        final double[] error = {29, 0.17};

        final int[] r = new int[ROLE_COUNT];
        if (!dark) {
            r[R_PRIMARY] = c(primary, 40);
            r[R_ON_PRIMARY] = c(primary, 100);
            r[R_PRIMARY_CONTAINER] = c(primary, 90);
            r[R_ON_PRIMARY_CONTAINER] = c(primary, 10);
            r[R_SECONDARY] = c(secondary, 40);
            r[R_SECONDARY_CONTAINER] = c(secondary, 90);
            r[R_ON_SECONDARY_CONTAINER] = c(secondary, 10);
            r[R_SURFACE] = c(neutral, 98);
            r[R_SURFACE_LOW] = c(neutral, 96);
            r[R_SURFACE_CONTAINER] = c(neutral, 94);
            r[R_SURFACE_HIGH] = c(neutral, 92);
            r[R_SURFACE_HIGHEST] = c(neutral, 90);
            r[R_ON_SURFACE] = c(neutral, 10);
            r[R_ON_SURFACE_VARIANT] = c(variant, 30);
            r[R_OUTLINE] = c(variant, 50);
            r[R_OUTLINE_VARIANT] = c(variant, 80);
            r[R_ERROR] = c(error, 40);
            r[R_ON_ERROR] = c(error, 100);
            r[R_ERROR_CONTAINER] = c(error, 90);
        } else {
            r[R_PRIMARY] = c(primary, 80);
            r[R_ON_PRIMARY] = c(primary, 20);
            r[R_PRIMARY_CONTAINER] = c(primary, 30);
            r[R_ON_PRIMARY_CONTAINER] = c(primary, 90);
            r[R_SECONDARY] = c(secondary, 80);
            r[R_SECONDARY_CONTAINER] = c(secondary, 30);
            r[R_ON_SECONDARY_CONTAINER] = c(secondary, 90);
            r[R_SURFACE] = amoled ? 0xFF000000 : c(neutral, 6);
            r[R_SURFACE_LOW] = c(neutral, amoled ? 4 : 10);
            r[R_SURFACE_CONTAINER] = c(neutral, amoled ? 8 : 12);
            r[R_SURFACE_HIGH] = c(neutral, amoled ? 12 : 17);
            r[R_SURFACE_HIGHEST] = c(neutral, amoled ? 17 : 22);
            r[R_ON_SURFACE] = c(neutral, 90);
            r[R_ON_SURFACE_VARIANT] = c(variant, 80);
            r[R_OUTLINE] = c(variant, 60);
            r[R_OUTLINE_VARIANT] = c(variant, 30);
            r[R_ERROR] = c(error, 80);
            r[R_ON_ERROR] = c(error, 20);
            r[R_ERROR_CONTAINER] = c(error, 30);
        }
        r[R_BLACK] = 0xFF000000;
        return r;
    }

    private static int c(double[] hc, double tone) {
        return argb(tone, hc[1], hc[0]);
    }

    private static double toneToL(double tone) {
        if (tone <= 0) return 0;
        if (tone >= 100) return 1;
        final double y = tone > 8 ? Math.pow((tone + 16) / 116.0, 3) : tone / 903.3;
        return Math.cbrt(y);
    }

    private static int argb(double tone, double chroma, double hue) {
        final double l = toneToL(tone);
        double ch = chroma;
        double[] lin = null;
        for (int i = 0; i < 32; i++) {
            lin = OKLCH.xyz2rgbLinear(OKLCH.oklab2xyz(OKLCH.oklch2oklab(new double[]{l, ch, hue})));
            if (inGamut(lin)) break;
            ch *= 0.92;
        }
        return OKLCH.rgb(OKLCH.srgbLinear2rgb(lin)) | 0xFF000000;
    }

    private static boolean inGamut(double[] v) {
        final double e = 0.0015;
        return v[0] >= -e && v[0] <= 1 + e && v[1] >= -e && v[1] <= 1 + e && v[2] >= -e && v[2] <= 1 + e;
    }

    public static String s(String en, String ru) {
        return "ru".equals(java.util.Locale.getDefault().getLanguage()) ? ru : en;
    }

    public static int density() {
        return prefs().getInt("density", 1);
    }

    public static void setDensity(int value) {
        prefs().edit().putInt("density", Math.max(0, Math.min(2, value))).apply();
    }

    public static int rowExtra() {
        return 2 + 2 * density();
    }

    public static float radius(float dp) {
        return AndroidUtilities.dpf2(dp) * getCornerScale();
    }

    private static volatile long[] tintLightCache;
    private static volatile long[] tintDarkCache;

    private static long[] tintCache(boolean dark) {
        long[] c = dark ? tintDarkCache : tintLightCache;
        if (c == null) {
            c = new long[Theme.colorsCount + 1];
            if (dark) tintDarkCache = c; else tintLightCache = c;
        }
        return c;
    }

    public static int tint(int key, int color) {
        if (!loaded) {
            ensureLoaded();
        }
        if (!enabled) {
            return color;
        }
        if (key < 0) {
            return tintColor(color);
        }
        final long[] cache = tintCache(Theme.isCurrentThemeDark());
        if (key >= cache.length) {
            return tintColor(color);
        }
        final long v = cache[key];
        if (v != 0 && (int) (v >>> 32) == color) {
            return (int) v;
        }
        final int out = tintColor(color);
        cache[key] = ((long) color << 32) | (out & 0xFFFFFFFFL);
        return out;
    }

    private static int tintColor(int color) {
        final double[] lch = toOklch(color);
        final double c = lch[1];
        final double h = Double.isNaN(lch[2]) ? 0 : lch[2];
        if (c < 0.04 || h < 195 || h > 290) {
            return color;
        }
        final double[] seedLch = toOklch(seed);
        final double seedHue = Double.isNaN(seedLch[2]) ? 0 : seedLch[2];
        final double newC = Math.min(c, Math.max(0.05, seedLch[1]));
        final int rgb = fromOklch(lch[0], newC, seedHue);
        return ColorUtils.setAlphaComponent(rgb, Color.alpha(color));
    }

    private static double[] toOklch(int color) {
        final double[] lin = OKLCH.rgb2srgbLinear(OKLCH.rgb(color));
        return OKLCH.oklab2oklch(OKLCH.xyz2oklab(OKLCH.rgbLinear2xyz(lin)));
    }

    private static int fromOklch(double l, double chroma, double hue) {
        double ch = chroma;
        double[] lin = null;
        for (int i = 0; i < 32; i++) {
            lin = OKLCH.xyz2rgbLinear(OKLCH.oklab2xyz(OKLCH.oklch2oklab(new double[]{l, ch, hue})));
            if (inGamut(lin)) break;
            ch *= 0.92;
        }
        return OKLCH.rgb(OKLCH.srgbLinear2rgb(lin)) | 0xFF000000;
    }

    private static void m(int key, int role, int alpha) {
        roleOfKey.put(key, role | (alpha << 8));
    }

    private static void buildKeyMap() {
        m(Theme.key_windowBackgroundWhite, R_SURFACE, 255);
        m(Theme.key_windowBackgroundGray, R_SURFACE_CONTAINER, 255);
        m(Theme.key_windowBackgroundWhiteBlackText, R_ON_SURFACE, 255);
        m(Theme.key_windowBackgroundWhiteGrayText, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_windowBackgroundWhiteGrayText2, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_windowBackgroundWhiteGrayText3, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_windowBackgroundWhiteGrayText4, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_windowBackgroundWhiteGrayText5, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_windowBackgroundWhiteGrayText6, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_windowBackgroundWhiteGrayText7, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_windowBackgroundWhiteGrayText8, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_windowBackgroundWhiteGrayIcon, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_windowBackgroundWhiteHintText, R_OUTLINE, 255);
        m(Theme.key_windowBackgroundWhiteInputField, R_OUTLINE, 255);
        m(Theme.key_windowBackgroundWhiteBlueText, R_PRIMARY, 255);
        m(Theme.key_windowBackgroundWhiteBlueText2, R_PRIMARY, 255);
        m(Theme.key_windowBackgroundWhiteBlueText3, R_PRIMARY, 255);
        m(Theme.key_windowBackgroundWhiteBlueText4, R_PRIMARY, 255);
        m(Theme.key_windowBackgroundWhiteBlueText5, R_PRIMARY, 255);
        m(Theme.key_windowBackgroundWhiteBlueText6, R_PRIMARY, 255);
        m(Theme.key_windowBackgroundWhiteBlueText7, R_PRIMARY, 255);
        m(Theme.key_windowBackgroundWhiteBlueHeader, R_PRIMARY, 255);
        m(Theme.key_windowBackgroundWhiteBlueIcon, R_PRIMARY, 255);
        m(Theme.key_windowBackgroundWhiteLinkText, R_PRIMARY, 255);
        m(Theme.key_windowBackgroundWhiteValueText, R_PRIMARY, 255);
        m(Theme.key_windowBackgroundWhiteInputFieldActivated, R_PRIMARY, 255);
        m(Theme.key_windowBackgroundWhiteBlueButton, R_PRIMARY, 255);
        m(Theme.key_windowBackgroundWhiteLinkSelection, R_PRIMARY, 64);
        m(Theme.key_divider, R_OUTLINE_VARIANT, 255);
        m(Theme.key_listSelector, R_ON_SURFACE, 20);
        m(Theme.key_actionBarDefault, R_SURFACE, 255);
        m(Theme.key_actionBarDefaultIcon, R_ON_SURFACE, 255);
        m(Theme.key_actionBarDefaultTitle, R_ON_SURFACE, 255);
        m(Theme.key_actionBarDefaultSearch, R_ON_SURFACE, 255);
        m(Theme.key_actionBarDefaultSubmenuItem, R_ON_SURFACE, 255);
        m(Theme.key_actionBarDefaultSubtitle, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_actionBarDefaultSearchPlaceholder, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_actionBarDefaultSubmenuItemIcon, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_actionBarDefaultSelector, R_ON_SURFACE, 31);
        m(Theme.key_actionBarDefaultSubmenuBackground, R_SURFACE_HIGH, 255);
        m(Theme.key_actionBarDefaultSubmenuSeparator, R_OUTLINE_VARIANT, 255);
        m(Theme.key_chats_name, R_ON_SURFACE, 255);
        m(Theme.key_chats_menuItemText, R_ON_SURFACE, 255);
        m(Theme.key_chats_message, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chats_message_threeLines, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chats_date, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chats_pinnedIcon, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chats_sentClock, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chats_muteIcon, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chats_menuItemIcon, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chats_menuItemCheck, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chats_nameMessage, R_PRIMARY, 255);
        m(Theme.key_chats_nameMessage_threeLines, R_PRIMARY, 255);
        m(Theme.key_chats_attachMessage, R_PRIMARY, 255);
        m(Theme.key_chats_actionMessage, R_PRIMARY, 255);
        m(Theme.key_chats_sentCheck, R_PRIMARY, 255);
        m(Theme.key_chats_sentReadCheck, R_PRIMARY, 255);
        m(Theme.key_chats_verifiedBackground, R_PRIMARY, 255);
        m(Theme.key_chats_draft, R_ERROR, 255);
        m(Theme.key_chats_unreadCounter, R_PRIMARY, 255);
        m(Theme.key_chats_tabUnreadActiveBackground, R_PRIMARY, 255);
        m(Theme.key_chats_unreadCounterText, R_ON_PRIMARY, 255);
        m(Theme.key_chats_verifiedCheck, R_ON_PRIMARY, 255);
        m(Theme.key_chats_unreadCounterMuted, R_OUTLINE, 255);
        m(Theme.key_chats_actionBackground, R_PRIMARY_CONTAINER, 255);
        m(Theme.key_chats_menuTopBackground, R_PRIMARY_CONTAINER, 255);
        m(Theme.key_chats_actionIcon, R_ON_PRIMARY_CONTAINER, 255);
        m(Theme.key_chats_menuName, R_ON_PRIMARY_CONTAINER, 255);
        m(Theme.key_chats_menuPhone, R_ON_PRIMARY_CONTAINER, 255);
        m(Theme.key_chats_actionPressedBackground, R_SECONDARY_CONTAINER, 255);
        m(Theme.key_chats_menuBackground, R_SURFACE_LOW, 255);
        m(Theme.key_chats_pinnedOverlay, R_SURFACE_CONTAINER, 255);
        m(Theme.key_chats_tabletSelectedOverlay, R_PRIMARY_CONTAINER, 128);
        m(Theme.key_chat_inBubble, R_SURFACE_HIGH, 255);
        m(Theme.key_chat_outBubble, R_PRIMARY_CONTAINER, 255);
        m(Theme.key_chat_outBubbleSelected, R_PRIMARY_CONTAINER, 255);
        m(Theme.key_chat_messageTextIn, R_ON_SURFACE, 255);
        m(Theme.key_chat_inReplyMessageText, R_ON_SURFACE, 255);
        m(Theme.key_chat_inReplyMediaMessageText, R_ON_SURFACE, 255);
        m(Theme.key_chat_inAudioTitleText, R_ON_SURFACE, 255);
        m(Theme.key_chat_inFileNameText, R_ON_SURFACE, 255);
        m(Theme.key_chat_inContactNameText, R_ON_SURFACE, 255);
        m(Theme.key_chat_messageTextOut, R_ON_PRIMARY_CONTAINER, 255);
        m(Theme.key_chat_outReplyMessageText, R_ON_PRIMARY_CONTAINER, 255);
        m(Theme.key_chat_outReplyMediaMessageText, R_ON_PRIMARY_CONTAINER, 255);
        m(Theme.key_chat_outAudioTitleText, R_ON_PRIMARY_CONTAINER, 255);
        m(Theme.key_chat_outFileNameText, R_ON_PRIMARY_CONTAINER, 255);
        m(Theme.key_chat_outContactNameText, R_ON_PRIMARY_CONTAINER, 255);
        m(Theme.key_chat_outSentClock, R_ON_PRIMARY_CONTAINER, 255);
        m(Theme.key_chat_messageLinkIn, R_PRIMARY, 255);
        m(Theme.key_chat_messageLinkOut, R_PRIMARY, 255);
        m(Theme.key_chat_inReplyLine, R_PRIMARY, 255);
        m(Theme.key_chat_outReplyLine, R_PRIMARY, 255);
        m(Theme.key_chat_inReplyNameText, R_PRIMARY, 255);
        m(Theme.key_chat_outReplyNameText, R_PRIMARY, 255);
        m(Theme.key_chat_inForwardedNameText, R_PRIMARY, 255);
        m(Theme.key_chat_outForwardedNameText, R_PRIMARY, 255);
        m(Theme.key_chat_inViaBotNameText, R_PRIMARY, 255);
        m(Theme.key_chat_outViaBotNameText, R_PRIMARY, 255);
        m(Theme.key_chat_inSiteNameText, R_PRIMARY, 255);
        m(Theme.key_chat_outSiteNameText, R_PRIMARY, 255);
        m(Theme.key_chat_inPreviewLine, R_PRIMARY, 255);
        m(Theme.key_chat_outPreviewLine, R_PRIMARY, 255);
        m(Theme.key_chat_outSentCheck, R_PRIMARY, 255);
        m(Theme.key_chat_outSentCheckRead, R_PRIMARY, 255);
        m(Theme.key_chat_inVoiceSeekbarFill, R_PRIMARY, 255);
        m(Theme.key_chat_outVoiceSeekbarFill, R_PRIMARY, 255);
        m(Theme.key_chat_inAudioSeekbarFill, R_PRIMARY, 255);
        m(Theme.key_chat_outAudioSeekbarFill, R_PRIMARY, 255);
        m(Theme.key_chat_inFileProgress, R_PRIMARY, 255);
        m(Theme.key_chat_outFileProgress, R_PRIMARY, 255);
        m(Theme.key_chat_inLoader, R_PRIMARY, 255);
        m(Theme.key_chat_outLoader, R_PRIMARY, 255);
        m(Theme.key_chat_inTimeText, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chat_inSentClock, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chat_inViews, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chat_inMenu, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chat_inFileInfoText, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chat_inAudioDurationText, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chat_inAudioPerformerText, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chat_outTimeText, R_ON_PRIMARY_CONTAINER, 179);
        m(Theme.key_chat_outViews, R_ON_PRIMARY_CONTAINER, 179);
        m(Theme.key_chat_outMenu, R_ON_PRIMARY_CONTAINER, 179);
        m(Theme.key_chat_outFileInfoText, R_ON_PRIMARY_CONTAINER, 179);
        m(Theme.key_chat_outAudioDurationText, R_ON_PRIMARY_CONTAINER, 179);
        m(Theme.key_chat_outAudioPerformerText, R_ON_PRIMARY_CONTAINER, 179);
        m(Theme.key_chat_inVoiceSeekbar, R_OUTLINE_VARIANT, 255);
        m(Theme.key_chat_inAudioSeekbar, R_OUTLINE_VARIANT, 255);
        m(Theme.key_chat_outVoiceSeekbar, R_PRIMARY, 77);
        m(Theme.key_chat_outAudioSeekbar, R_PRIMARY, 77);
        m(Theme.key_chat_wallpaper, R_SURFACE, 255);
        m(Theme.key_chat_serviceBackground, R_SURFACE_HIGHEST, 204);
        m(Theme.key_chat_serviceBackgroundSelected, R_SURFACE_HIGHEST, 204);
        m(Theme.key_chat_serviceText, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chat_selectedBackground, R_PRIMARY, 38);
        m(Theme.key_chat_textSelectBackground, R_PRIMARY, 38);
        m(Theme.key_chat_unreadMessagesStartBackground, R_SURFACE_HIGH, 255);
        m(Theme.key_chat_unreadMessagesStartText, R_PRIMARY, 255);
        m(Theme.key_chat_messagePanelBackground, R_SURFACE_CONTAINER, 255);
        m(Theme.key_chat_emojiPanelBackground, R_SURFACE_CONTAINER, 255);
        m(Theme.key_chat_messagePanelText, R_ON_SURFACE, 255);
        m(Theme.key_chat_messagePanelHint, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chat_messagePanelIcons, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chat_emojiPanelIcon, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chat_emojiBottomPanelIcon, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chat_replyPanelIcons, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chat_replyPanelClose, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chat_topPanelMessage, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chat_topPanelClose, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_chat_messagePanelSend, R_PRIMARY, 255);
        m(Theme.key_chat_messagePanelCursor, R_PRIMARY, 255);
        m(Theme.key_chat_emojiPanelIconSelected, R_PRIMARY, 255);
        m(Theme.key_chat_replyPanelName, R_PRIMARY, 255);
        m(Theme.key_chat_replyPanelLine, R_PRIMARY, 255);
        m(Theme.key_chat_topPanelTitle, R_PRIMARY, 255);
        m(Theme.key_chat_topPanelLine, R_PRIMARY, 255);
        m(Theme.key_chat_topPanelBackground, R_SURFACE_HIGH, 255);
        m(Theme.key_chat_goDownButton, R_SURFACE_HIGH, 255);
        m(Theme.key_chat_goDownButtonCounterBackground, R_PRIMARY, 255);
        m(Theme.key_chat_goDownButtonCounter, R_ON_PRIMARY, 255);
        m(Theme.key_dialogBackground, R_SURFACE_HIGH, 255);
        m(Theme.key_dialogBackgroundGray, R_SURFACE_CONTAINER, 255);
        m(Theme.key_dialogTextBlack, R_ON_SURFACE, 255);
        m(Theme.key_dialogTextGray, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_dialogTextGray2, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_dialogTextGray3, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_dialogTextGray4, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_dialogTextHint, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_dialogIcon, R_ON_SURFACE_VARIANT, 255);
        m(Theme.key_dialogTextBlue, R_PRIMARY, 255);
        m(Theme.key_dialogTextBlue2, R_PRIMARY, 255);
        m(Theme.key_dialogTextBlue4, R_PRIMARY, 255);
        m(Theme.key_dialogButton, R_PRIMARY, 255);
        m(Theme.key_dialogTextLink, R_PRIMARY, 255);
        m(Theme.key_dialogInputFieldActivated, R_PRIMARY, 255);
        m(Theme.key_dialogCheckboxSquareBackground, R_PRIMARY, 255);
        m(Theme.key_dialogRadioBackgroundChecked, R_PRIMARY, 255);
        m(Theme.key_dialogInputField, R_OUTLINE, 255);
        m(Theme.key_dialogRadioBackground, R_OUTLINE, 255);
        m(Theme.key_dialogGrayLine, R_OUTLINE_VARIANT, 255);
        m(Theme.key_dialogButtonSelector, R_PRIMARY, 31);
        m(Theme.key_switchTrackChecked, R_PRIMARY, 255);
        m(Theme.key_switchTrackBlueChecked, R_PRIMARY, 255);
        m(Theme.key_switch2TrackChecked, R_PRIMARY, 255);
        m(Theme.key_progressCircle, R_PRIMARY, 255);
        m(Theme.key_fastScrollActive, R_PRIMARY, 255);
        m(Theme.key_switchTrack, R_OUTLINE, 255);
        m(Theme.key_switchTrackBlue, R_OUTLINE, 255);
        m(Theme.key_switch2Track, R_OUTLINE, 255);
        m(Theme.key_fastScrollInactive, R_OUTLINE, 255);
        m(Theme.key_switchTrackBlueThumbChecked, R_ON_PRIMARY, 255);
        m(Theme.key_text_RedRegular, R_ERROR, 255);
        m(Theme.key_text_RedBold, R_ERROR, 255);
        m(Theme.key_featuredStickers_addButton, R_PRIMARY, 255);
        m(Theme.key_featuredStickers_addButtonPressed, R_PRIMARY_CONTAINER, 255);
        m(Theme.key_featuredStickers_buttonText, R_ON_PRIMARY, 255);
        m(Theme.key_chats_actionBackground, R_PRIMARY, 255);
        m(Theme.key_chats_actionPressedBackground, R_PRIMARY_CONTAINER, 255);
        m(Theme.key_chats_actionIcon, R_ON_PRIMARY, 255);
    }
}
