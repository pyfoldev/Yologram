package org.telegram.messenger;

import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * "Phone lent to someone" mode: chosen accounts disappear from the account switchers and stay silent
 * until the owner opens them with a PIN. The visible accounts keep working as usual.
 */
public class NullgramHidden {

    private static final long RELOCK_AFTER_MS = 20_000L;
    private static boolean unlocked;
    private static boolean decoy;
    private static long leftAt;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nullgram_hidden", 0);
    }

    private static String key(int account) {
        return String.valueOf(UserConfig.getInstance(account).getClientUserId());
    }

    public static boolean isHidden(int account) {
        return prefs().getStringSet("ids", new HashSet<>()).contains(key(account));
    }

    /** Hidden and not unlocked right now: nothing of this account should show. */
    public static boolean isConcealed(int account) {
        return !unlocked && isHidden(account);
    }

    public static boolean anyHidden() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && isHidden(a)) {
                return true;
            }
        }
        return false;
    }

    public static int visibleCount() {
        int n = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && !isHidden(a)) {
                n++;
            }
        }
        return n;
    }

    /** Returns false when hiding would leave no visible account. */
    public static boolean setHidden(int account, boolean hidden) {
        if (hidden && !isHidden(account) && visibleCount() <= 1) {
            return false;
        }
        final Set<String> ids = new HashSet<>(prefs().getStringSet("ids", new HashSet<>()));
        if (hidden) {
            ids.add(key(account));
        } else {
            ids.remove(key(account));
        }
        prefs().edit().putStringSet("ids", ids).apply();
        return true;
    }

    public static boolean isUnlocked() {
        return unlocked;
    }

    public static void unlock() {
        unlocked = true;
        decoy = false;
    }

    public static void lock() {
        unlocked = false;
        decoy = false;
    }

    /** The decoy PIN opens the same screen but shows no hidden account, so a forced unlock reveals nothing. */
    public static boolean isDecoy() {
        return decoy;
    }

    public static void unlockDecoy() {
        decoy = true;
        unlocked = false;
    }

    public static boolean hasDecoyPin() {
        return prefs().getString("decoy", null) != null;
    }

    /** Returns false when it equals the real PIN. */
    public static boolean setDecoyPin(String pin) {
        if (checkPin(pin)) {
            return false;
        }
        prefs().edit().putString("decoy", hash(prefs().getString("salt", ""), pin)).apply();
        return true;
    }

    public static boolean checkDecoyPin(String pin) {
        final String stored = prefs().getString("decoy", null);
        return stored != null && stored.equals(hash(prefs().getString("salt", ""), pin));
    }

    public static boolean hasPin() {
        return prefs().getString("pin", null) != null;
    }

    private static String hash(String salt, String pin) {
        try {
            final byte[] d = MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).getBytes("UTF-8"));
            final StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static void setPin(String pin) {
        final byte[] raw = new byte[8];
        new SecureRandom().nextBytes(raw);
        final StringBuilder salt = new StringBuilder();
        for (byte b : raw) {
            salt.append(String.format("%02x", b));
        }
        prefs().edit().putString("salt", salt.toString()).putString("pin", hash(salt.toString(), pin)).apply();
    }

    public static boolean checkPin(String pin) {
        final String salt = prefs().getString("salt", "");
        final String stored = prefs().getString("pin", null);
        return stored != null && stored.equals(hash(salt, pin));
    }

    public static void onPause() {
        leftAt = System.currentTimeMillis();
    }

    /** Called from the main activity on resume: re-lock after a short absence and leave a concealed account. */
    public static void onResume() {
        if (!anyHidden()) {
            return;
        }
        if ((unlocked || decoy) && leftAt != 0 && System.currentTimeMillis() - leftAt > RELOCK_AFTER_MS) {
            unlocked = false;
            decoy = false;
        }
        if (isConcealed(UserConfig.selectedAccount)) {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated() && !isHidden(a)) {
                    final int target = a;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (org.telegram.ui.LaunchActivity.instance != null) {
                            org.telegram.ui.LaunchActivity.instance.switchToAccount(target, true);
                        }
                    }, 150);
                    return;
                }
            }
        }
    }
}
