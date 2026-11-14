package app.template.extension.gboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * Advanced voice typing's formatter files (punctuation, capitalization, spoken punctuation, emojis,
 * corrections). A build may ship them inside the patched APK (assets/mp_sd/en); otherwise the setup
 * screen downloads them once from Google. Either way they end up in files/smart_dictation_data/en and
 * are handed to Gboard from there.
 */
@SuppressWarnings("unused")
public final class SmartDictationData {
    private static final String LANG = "en";
    private static final String ASSET_DIR = "mp_sd/" + LANG + "/";
    static final String[] FILES = {
            "punctuation_model.tflite",
            "capitalization_model.tflite",
            "capitalization_vocab.txt",
            "spoken_punctuation_model.tflite",
            "spoken_punctuation_config.binarypb",
            "spoken_emojis_patterns.binarypb",
            "corrections_query_alternatives.binarypb",
    };
    private static volatile boolean extracted;

    private SmartDictationData() {
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

    private static File dir(Context c) {
        return new File(c.getFilesDir(), "smart_dictation_data/" + LANG);
    }

    private static synchronized boolean extract(Context c) {
        if (extracted) return true;
        try {
            File dir = dir(c);
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            for (String name : FILES) {
                File target = new File(dir, name);
                if (target.isFile() && target.length() > 0) continue; // written atomically below
                if (!hasAsset(c, name)) return false; // build without bundled files: download needed
                try (InputStream in = c.getAssets().open(ASSET_DIR + name)) {
                    File tmp = new File(dir, name + ".part");
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    if (!tmp.renameTo(target)) return false;
                }
            }
            extracted = true;
            VoiceDiagnostics.note("formatter files ready");
            return true;
        } catch (Throwable t) {
            VoiceDiagnostics.note("formatter files FAILED: " + t);
            return false;
        }
    }

    private static boolean hasAsset(Context c, String name) {
        try (InputStream in = c.getAssets().open(ASSET_DIR + name)) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when this build carries the files inside the APK. */
    static boolean bundled(Context c) {
        return hasAsset(c, FILES[0]);
    }

    /** True when every file is in place (bundled and extracted, or downloaded). */
    static boolean ready(Context c) {
        File d = dir(c);
        for (String f : FILES) {
            File x = new File(d, f);
            if (!x.isFile() || x.length() == 0) return bundled(c) && extract(c);
        }
        return true;
    }

    // Gboard's own download location for these files.
    private static final String BASE = "https://dl.google.com/gboard/klp/experiment/smartdictation/en/";

    /** Downloads the 7 files from Google (one time). Blocking; call off the main thread. */
    static void download(Context c) throws java.io.IOException {
        File dir = dir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Can't create folder");
        for (int i = 0; i < FILES.length; i++) {
            File target = new File(dir, FILES[i]);
            if (target.isFile() && target.length() > 0) continue;
            File tmp = new File(dir, FILES[i] + ".part");
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(BASE + FILES[i]).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                throw new java.io.IOException("HTTP " + code + " for " + FILES[i]);
            }
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                conn.disconnect();
            }
            if (tmp.length() == 0) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new java.io.IOException("Empty file: " + FILES[i]);
            }
            if (!tmp.renameTo(target)) throw new java.io.IOException("Can't save " + FILES[i]);
        }
        extracted = true;
        VoiceDiagnostics.note("formatter files downloaded");
    }

    static String sha1(File f) throws java.io.IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new java.io.IOException(e);
        }
    }

    private static boolean known(String name) {
        for (String f : FILES) if (f.equals(name)) return true;
        return false;
    }

    /** Hook: Gboard's download manager lacks {@code fileId} ("en_punctuation_model.tflite"). */
    public static boolean manualFileAvailable(String fileId) {
        DictationJni.ensure();
        try {
            if (fileId == null || !fileId.startsWith(LANG + "_")) return false;
            Context c = appContext();
            return c != null && known(fileId.substring(LANG.length() + 1)) && extract(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Hook: location of data file {@code name} for {@code locale}, or null to ask the download manager. */
    public static Uri manualFileUri(Locale locale, String name) {
        DictationJni.ensure();
        try {
            if (locale == null || !LANG.equals(locale.getLanguage()) || !known(name)) return null;
            Context c = appContext();
            if (c == null || !extract(c)) return null;
            // Gboard's storage library (MobStore) only opens its "android" scheme:
            // android://<package>/files/<path> = getFilesDir()/<path>.
            return new Uri.Builder().scheme("android").authority(c.getPackageName())
                    .appendPath("files").appendPath("smart_dictation_data").appendPath(LANG)
                    .appendPath(name).build();
        } catch (Throwable t) {
            return null;
        }
    }
}
