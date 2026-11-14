package app.template.extension.gboard;

import android.media.AudioFocusRequest;
import android.media.AudioManager;

/**
 * "Keep other audio playing" switch (Voice typing settings). Gboard's audio focus requests go
 * through here; with the switch on they're skipped and reported as granted, so music/videos keep
 * playing at full volume while dictating.
 */
@SuppressWarnings("unused")
public final class AudioFocusGuard {
    static final String KEY = "mp_voice_no_audio_focus";

    private AudioFocusGuard() {
    }

    private static boolean skip() {
        return GboardPrefs.getBoolean(KEY, false);
    }

    /** Hook: replaces AudioManager.requestAudioFocus(AudioFocusRequest). */
    public static int request(AudioManager am, AudioFocusRequest r) {
        if (skip()) return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return am.requestAudioFocus(r);
    }

    /** Hook: replaces AudioManager.abandonAudioFocusRequest(AudioFocusRequest). */
    public static int abandon(AudioManager am, AudioFocusRequest r) {
        try {
            int res = am.abandonAudioFocusRequest(r); // harmless if never requested
            return res;
        } catch (Throwable t) {
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }
}
