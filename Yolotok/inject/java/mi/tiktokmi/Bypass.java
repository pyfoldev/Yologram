package mi.tiktokmi;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Getting TikTok through a block: the app's traffic goes through a proxy the
 * person supplies. The mod ships no proxy of its own and does not pick one --
 * whose server the traffic passes through is the one choice nobody should make
 * for somebody else.
 *
 * Reached from the start-up hook, so it is in place before TikTok opens its
 * first connection, and from the settings screen, where it takes effect at
 * once for new connections.
 *
 * What it reaches: every connection made through Java's own networking and
 * OkHttp, and the native network stack to the extent that stack follows the
 * process's proxy properties. The diary says what was applied. It does not
 * reach anything that opens its own sockets below that, which is why the
 * settings page says so.
 */
public final class Bypass {

    private Bypass() {}

    static final String KEY_ENABLED = "bypass_enabled";
    static final String KEY_PROXY = "bypass_proxy";

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Margy.PREFS, 0);
    }

    public static boolean enabled(Context context) {
        return context != null && prefs(context).getBoolean(KEY_ENABLED, false);
    }

    /** The address as stored, or an empty string. */
    public static String proxy(Context context) {
        return context == null ? "" : prefs(context).getString(KEY_PROXY, "");
    }

    /** Read at start-up; does nothing unless it was switched on and has an address. */
    public static void start(Context context) {
        apply(context);
    }

    /**
     * Make the process agree with the settings.
     *
     * @return what happened, for the settings screen and the diary
     */
    public static String apply(Context context) {
        try {
            if (!enabled(context)) {
                if (BypassRules.installed()) {
                    BypassRules.uninstall();
                    Diary.note("bypass: switched off, the process's own proxy settings are back");
                }
                return "off";
            }
            BypassRules.Spec spec = BypassRules.parse(proxy(context));
            if (spec == null) {
                Diary.note("bypass: switched on, but there is no usable proxy address");
                return "no-address";
            }
            BypassRules.install(spec);
            Diary.note("bypass: traffic goes through a " + (spec.socks ? "SOCKS" : "HTTP")
                    + " proxy at " + spec.host + ":" + spec.port);
            return "on";
        } catch (Throwable error) {
            Diary.note("bypass failed: " + error);
            return "error";
        }
    }

    /**
     * Store a new address. False, and nothing stored, if it cannot be read as one.
     * An empty string clears it.
     */
    public static boolean setProxy(Context context, String text) {
        if (context == null) return false;
        String clean = text == null ? "" : text.trim();
        SharedPreferences.Editor editor = prefs(context).edit();
        if (clean.isEmpty()) {
            editor.remove(KEY_PROXY).putBoolean(KEY_ENABLED, false).apply();
            apply(context);
            return true;
        }
        BypassRules.Spec spec = BypassRules.parse(clean);
        if (spec == null) return false;
        editor.putString(KEY_PROXY, spec.text()).apply();
        apply(context);
        return true;
    }

    /** Switch on or off. Switching on with no address stays off: there is nowhere to send traffic. */
    public static boolean setEnabled(Context context, boolean on) {
        if (context == null) return false;
        boolean can = BypassRules.parse(proxy(context)) != null;
        boolean now = on && can;
        prefs(context).edit().putBoolean(KEY_ENABLED, now).apply();
        apply(context);
        return now == on;
    }
}
