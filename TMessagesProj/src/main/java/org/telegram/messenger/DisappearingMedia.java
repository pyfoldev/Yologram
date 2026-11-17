package org.telegram.messenger;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.telegram.messenger.secretmedia.EncryptedFileInputStream;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Keeps a copy of self-destructing photos and videos. Writes straight to MediaStore: MediaController.saveFile
 * refuses every file under the app's own data dir (isInternalUri), which is exactly where these files live.
 */
public class DisappearingMedia {

    private static final Set<String> saved = new HashSet<>();
    private static final Set<String> inFlight = new HashSet<>();

    /** Called when the viewer opens the message: the file may already be on disk, so FileLoader never reports it. */
    public static void saveFromMessage(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return;
        }
        try {
            final int account = message.currentAccount;
            File file = null;
            if (message.attachPathExists && message.messageOwner.attachPath != null) {
                file = new File(message.messageOwner.attachPath);
            }
            if (file == null || !file.exists()) {
                file = FileLoader.getInstance(account).getPathToMessage(message.messageOwner);
            }
            if (file != null && !file.exists()) {
                final File enc = new File(file.getAbsolutePath() + ".enc");
                if (enc.exists()) {
                    file = enc;
                }
            }
            if (file != null && file.exists()) {
                maybeSave(message, file);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void maybeSave(MessageObject message, File file) {
        if (!NullgramConfig.saveDisappearing() || message == null || message.messageOwner == null || file == null || !file.exists()) {
            return;
        }
        final TLRPC.Message m = message.messageOwner;
        final TLRPC.MessageMedia media = MessageObject.getMedia(m);
        if (media == null || !(media.ttl_seconds != 0 || message.isSecretMedia() || MessageObject.isSecretPhotoOrVideo(m))) {
            return;
        }
        final boolean video = message.isVideo() || message.isRoundVideo() || (media.document != null && !MessageObject.isGifDocument(media.document) && media.photo == null);
        final boolean photo = media.photo != null && !video;
        if (!video && !photo) {
            return;
        }
        final String key = message.getDialogId() + ":" + message.getId() + ":" + (video ? "v" : "p");
        synchronized (saved) {
            if (saved.contains(key) || !inFlight.add(key)) {
                return;
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            File temp = null;
            boolean ok = false;
            try {
                if (file.length() < (photo ? 2 * 1024 : 16 * 1024)) {
                    return;
                }
                File source = file;
                final File keyFile = new File(FileLoader.getInternalCacheDir(), file.getName() + ".enc.key");
                if (keyFile.exists()) {
                    temp = new File(ApplicationLoader.applicationContext.getCacheDir(), "yologram_ttl_" + System.nanoTime());
                    try (InputStream in = new EncryptedFileInputStream(file, keyFile); FileOutputStream out = new FileOutputStream(temp)) {
                        copy(in, out);
                    }
                    source = temp;
                }
                final String name = "Yologram_" + System.currentTimeMillis() + (video ? ".mp4" : ".jpg");
                ok = store(source, video, name, video ? "video/mp4" : "image/jpeg");
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (temp != null) {
                    temp.delete();
                }
                synchronized (saved) {
                    inFlight.remove(key);
                    if (ok) {
                        saved.add(key);
                    }
                }
            }
        });
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        final byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    public static boolean store(File source, boolean video, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            final ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
            final ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/Yologram");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            final Uri base = video ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            final Uri uri = resolver.insert(base, values);
            if (uri == null) {
                return false;
            }
            try (InputStream in = new FileInputStream(source); OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    resolver.delete(uri, null, null);
                    return false;
                }
                copy(in, out);
            } catch (Exception e) {
                resolver.delete(uri, null, null);
                throw e;
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return true;
        }
        final File dir = new File(Environment.getExternalStoragePublicDirectory(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), "Yologram");
        if (!dir.exists() && !dir.mkdirs()) {
            return false;
        }
        final File dest = new File(dir, name);
        try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }
        MediaScannerConnection.scanFile(ApplicationLoader.applicationContext, new String[]{dest.getAbsolutePath()}, new String[]{mime}, null);
        return true;
    }
}
