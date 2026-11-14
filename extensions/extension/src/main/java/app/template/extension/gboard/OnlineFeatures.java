package app.template.extension.gboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.preference.PreferenceManager;

import java.util.function.BiPredicate;

/** Module conditions for Gboard's online features. */
@SuppressWarnings({"unused", "deprecation"})
public final class OnlineFeatures {
    /** "GIF search" switch in Emojis, stickers & GIFs settings (off by default). */
    public static final String GIF_KEY = "mp_gif_search";

    /** Never met: keeps an online-only module unloaded. */
    public static final BiPredicate<Object, Object> NEVER = (a, b) -> false;

    /** Met while the GIF search switch is on. */
    public static final BiPredicate<Object, Object> GIF = (a, b) -> {
        boolean on = gifEnabled();
        VoiceDiagnostics.note("GIF module check: switch " + (on ? "on" : "off")
                + " (prefs=" + (a == null ? "null" : a.getClass().getName()) + ", key=" + b + ")");
        return on;
    };

    /**
     * Settings that are hidden by the patch because they need the internet or send data, forced
     * off so they can't stay on behind the scenes: Emoji Kitchen on emoji-key tap, sticker
     * suggestions, GIF topic suggestions, usage statistics, federated training, audio donations.
     */
    private static final String[] FORCED_OFF = {
            "enable_emoji_to_expression",
            "enable_sticker_predictions_while_typing",
            "pref_key_enable_conv2query",
            "enable_user_metrics",
            "user_enable_federated_training",
            "enable_voice_donation",
    };
    private static volatile boolean forced;

    private OnlineFeatures() {
    }

    /** Hook: early in Gboard's start (module setup). Writes the forced-off values once per process. */
    public static void forceOffOnce() {
        if (forced) return;
        forced = true;
        try {
            Context c = appContext();
            if (c == null) {
                forced = false;
                return;
            }
            android.content.SharedPreferences p = null;
            try {
                p = GboardPrefs.raw(c);
            } catch (Throwable ignored) {
            }
            if (p == null) p = PreferenceManager.getDefaultSharedPreferences(c);
            android.content.SharedPreferences.Editor e = null;
            for (String k : FORCED_OFF) {
                boolean on;
                try {
                    on = !p.contains(k) || p.getBoolean(k, true);
                } catch (Throwable notBoolean) {
                    on = true;
                }
                if (on) {
                    if (e == null) e = p.edit();
                    e.putBoolean(k, false);
                }
            }
            if (e != null) e.apply();
        } catch (Throwable ignored) {
        }
    }

    public static boolean gifEnabled() {
        return GboardPrefs.getBoolean(GIF_KEY, false);
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    static Context appContext() {
        try {
            return (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
