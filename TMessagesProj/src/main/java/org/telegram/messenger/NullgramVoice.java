package org.telegram.messenger;

import android.media.AudioRecord;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

/** Microphone effects for voice messages, switched in Yologram settings → Chats. */
public class NullgramVoice {

    private static NoiseSuppressor noise;
    private static AutomaticGainControl agc;

    public static int bitrate() {
        return NullgramConfig.voiceHq() ? 64 * 1024 : 30 * 1024;
    }

    public static void attach(AudioRecord recorder) {
        release();
        if (recorder == null) {
            return;
        }
        try {
            final int session = recorder.getAudioSessionId();
            if (NullgramConfig.voiceNoise() && NoiseSuppressor.isAvailable()) {
                noise = NoiseSuppressor.create(session);
                if (noise != null) {
                    noise.setEnabled(true);
                }
            }
            if (NullgramConfig.voiceAgc() && AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(session);
                if (agc != null) {
                    agc.setEnabled(true);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static void release() {
        try {
            if (noise != null) {
                noise.release();
            }
            if (agc != null) {
                agc.release();
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        noise = null;
        agc = null;
    }
}
