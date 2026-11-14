package org.telegram.messenger;

import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.RequestDelegateTimestamp;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.BufferedReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GhostMode {

    private static final String PREFS = "nullgram_ghost";
    private static boolean loaded;
    private static boolean ghost;
    private static boolean keepDeleted = true;

    private static final Set<String> deleted = Collections.synchronizedSet(new HashSet<String>());
    private static File deletedFile;

    public static boolean isOn() {
        ensureLoaded();
        return ghost;
    }

    public static void setOn(boolean value) {
        ensureLoaded();
        ghost = value;
        prefs().edit().putBoolean("ghost", value).apply();
        if (value) {
            goOfflineNow();
        }
    }

    public static boolean isKeepDeleted() {
        ensureLoaded();
        return keepDeleted;
    }

    public static void setKeepDeleted(boolean value) {
        ensureLoaded();
        keepDeleted = value;
        prefs().edit().putBoolean("keep_deleted", value).apply();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (GhostMode.class) {
            if (loaded) {
                return;
            }
            final SharedPreferences p = prefs();
            ghost = p.getBoolean("ghost", false);
            keepDeleted = p.getBoolean("keep_deleted", true);
            deletedFile = new File(ApplicationLoader.getFilesDirFixed(), "nullgram_deleted.txt");
            try {
                if (deletedFile.exists()) {
                    try (BufferedReader r = new BufferedReader(new FileReader(deletedFile))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (!line.isEmpty()) deleted.add(line);
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            loaded = true;
        }
    }

    public static boolean shouldDrop(TLObject o) {
        if (!isOn()) {
            return false;
        }
        return o instanceof TLRPC.TL_messages_readHistory
            || o instanceof TLRPC.TL_channels_readHistory
            || o instanceof TLRPC.TL_messages_readMessageContents
            || o instanceof TLRPC.TL_channels_readMessageContents
            || o instanceof TLRPC.TL_messages_readDiscussion
            || o instanceof TLRPC.TL_messages_readSavedHistory
            || o instanceof TLRPC.TL_messages_readEncryptedHistory
            || o instanceof TLRPC.TL_messages_setTyping
            || o instanceof TL_stories.TL_stories_readStories
            || o instanceof TL_stories.TL_stories_incrementStoryViews
            || (o instanceof TL_account.updateStatus && !((TL_account.updateStatus) o).offline);
    }

    public static void failRequest(RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp) {
        final TLRPC.TL_error error = new TLRPC.TL_error();
        error.code = -1;
        error.text = "GHOST_MODE";
        try {
            if (onComplete != null) {
                onComplete.run(null, error);
            }
            if (onCompleteTimestamp != null) {
                onCompleteTimestamp.run(null, error, 0L);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void goOfflineNow() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                final TL_account.updateStatus req = new TL_account.updateStatus();
                req.offline = true;
                ConnectionsManager.getInstance(a).sendRequest(req, (response, error) -> {
                });
            }
        }
    }

    public static void markDeleted(long dialogId, List<Integer> ids) {
        ensureLoaded();
        final StringBuilder append = new StringBuilder();
        for (int id : ids) {
            final String key = dialogId + ":" + id;
            if (deleted.add(key)) {
                append.append(key).append('\n');
            }
        }
        if (append.length() == 0) {
            return;
        }
        final String text = append.toString();
        Utilities.globalQueue.postRunnable(() -> {
            try (FileOutputStream out = new FileOutputStream(deletedFile, true)) {
                out.write(text.getBytes());
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public static boolean isDeleted(MessageObject message) {
        if (message == null || message.messageOwner == null || deletedEmpty()) {
            return false;
        }
        final int id = message.getId();
        final long dialogId = message.getDialogId();
        if (deleted.contains(dialogId + ":" + id)) {
            return true;
        }
        final boolean channel = message.messageOwner.peer_id != null && message.messageOwner.peer_id.channel_id != 0;
        return !channel && deleted.contains("0:" + id);
    }

    private static boolean deletedEmpty() {
        ensureLoaded();
        return deleted.isEmpty();
    }
}
