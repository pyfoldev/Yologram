package org.telegram.messenger;

/** Hides message text in notifications (same treatment Telegram uses while the passcode is locked). */
public class PrivacyShield {

    public static boolean hideNotificationText() {
        return NullgramConfig.hideNotificationText();
    }
}
