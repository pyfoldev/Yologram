package org.telegram.ui.Components;

import android.media.MediaPlayer;

import org.telegram.messenger.FileLog;

import java.io.File;

/** Plays one profile track at a time; tapping the same profile again stops it. */
public class ProfileMusic {

    private static MediaPlayer player;
    private static String playingKey;

    public static boolean isPlaying(String key) {
        return key != null && key.equals(playingKey);
    }

    /** Starts the track of this profile, or stops it when it is already playing. Returns true when music is playing afterwards. */
    public static boolean toggle(String key, File file) {
        final boolean wasPlaying = isPlaying(key);
        stop();
        if (wasPlaying || file == null || !file.exists()) {
            return false;
        }
        try {
            final MediaPlayer p = new MediaPlayer();
            p.setDataSource(file.getAbsolutePath());
            p.setLooping(true);
            p.setOnPreparedListener(MediaPlayer::start);
            p.setOnErrorListener((mp, what, extra) -> {
                if (player == mp) {
                    stop();
                }
                return true;
            });
            p.prepareAsync();
            player = p;
            playingKey = key;
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            stop();
            return false;
        }
    }

    public static void stop() {
        final MediaPlayer p = player;
        player = null;
        playingKey = null;
        if (p != null) {
            try {
                p.release();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    public static void stopIf(String key) {
        if (isPlaying(key)) {
            stop();
        }
    }
}
