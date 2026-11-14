package app.template.extension.gboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.preference.PreferenceManager;

/** "Voice typing in incognito" switch in Gboard's Voice typing settings (default on). */
@SuppressWarnings({"unused", "deprecation"})
public final class IncognitoVoice {
    static final String KEY = "mp_voice_incognito";

    private IncognitoVoice() {
    }

    /** Hook: Gboard's incognito flag for voice typing; returns false (= not incognito) when allowed. */
    public static boolean filter(boolean incognito) {
        if (!incognito) return false;
        return !allowed();
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static boolean allowed() {
        return GboardPrefs.getBoolean(KEY, true);
    }
}
