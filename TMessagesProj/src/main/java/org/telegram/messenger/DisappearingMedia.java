package org.telegram.messenger;

import org.telegram.messenger.secretmedia.EncryptedFileInputStream;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

public class DisappearingMedia {

    private static final Set<String> saved = new HashSet<>();

    public static void maybeSave(MessageObject message, File file) {
        if (!NullgramConfig.saveDisappearing() || message == null || message.messageOwner == null || file == null || !file.exists()) {
            return;
        }
        final TLRPC.Message m = message.messageOwner;
        final TLRPC.MessageMedia media = MessageObject.getMedia(m);
        if (media == null || !(media.ttl_seconds != 0 || message.isSecretMedia() || MessageObject.isSecretPhotoOrVideo(m))) {
            return;
        }
        final boolean video = message.isVideo() || message.isRoundVideo();
        final boolean photo = media.photo != null && !video;
        if (!video && !photo) {
            return;
        }
        final String key = message.getDialogId() + ":" + message.getId() + ":" + (video ? "v" : "p");
        synchronized (saved) {
            if (saved.contains(key)) {
                return;
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            File temp = null;
            try {
                if (photo && file.length() < 8 * 1024) {
                    return;
                }
                File source = file;
                final File keyFile = new File(FileLoader.getInternalCacheDir(), file.getName() + ".enc.key");
                if (keyFile.exists()) {
                    temp = new File(ApplicationLoader.applicationContext.getCacheDir(), "nullgram_ttl_" + System.nanoTime());
                    try (InputStream in = new EncryptedFileInputStream(file, keyFile); FileOutputStream out = new FileOutputStream(temp)) {
                        final byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    }
                    source = temp;
                }
                synchronized (saved) {
                    saved.add(key);
                }
                final File toDelete = temp;
                final String name = "Nullgram_" + System.currentTimeMillis() + (video ? ".mp4" : ".jpg");
                MediaController.saveFile(source.getAbsolutePath(), ApplicationLoader.applicationContext, video ? 1 : 0, name, video ? "video/mp4" : "image/jpeg", uri -> {
                    if (toDelete != null) {
                        toDelete.delete();
                    }
                });
            } catch (Exception e) {
                FileLog.e(e);
                if (temp != null) {
                    temp.delete();
                }
            }
        });
    }
}
