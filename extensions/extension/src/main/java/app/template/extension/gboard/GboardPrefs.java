package app.template.extension.gboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * Reads Gboard's own settings store. Gboard's settings screens save through its own preferences
 * wrapper (not the plain default SharedPreferences), so switches we add must be read from there.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class GboardPrefs {
    private GboardPrefs() {
    }

    /** Replaced by the patch: returns the SharedPreferences behind Gboard's preferences wrapper. */
    static SharedPreferences raw(Context c) {
        return null;
    }

    public static boolean getBoolean(String key, boolean def) {
        try {
            Context c = appContext();
            if (c == null) return def;
            SharedPreferences p = null;
            try {
                p = raw(c);
            } catch (Throwable ignored) {
            }
            if (p != null && p.contains(key)) return p.getBoolean(key, def);
            SharedPreferences d = PreferenceManager.getDefaultSharedPreferences(c);
            return d.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
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
