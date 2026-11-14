package app.template.extension.gboard;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps Gboard's own voice typing log lines in files/voice_diagnostics.txt (root-readable) so it can be
 * seen why advanced voice typing is or isn't used.
 * Hooked into Gboard's central logging call; only voice typing lines are kept, never typed text.
 */
@SuppressWarnings("unused")
public final class VoiceDiagnostics {
    private static final int MAX_LINES = 600;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final Map<Integer, String> NAMES = new HashMap<>();
    private static final Pattern ELIGIBILITY = Pattern.compile("(eligibility: )(-?\\d+)");
    private static volatile boolean loaded;
    private static long lastWrite;

    static {
        NAMES.put(0, "UNKNOWN");
        NAMES.put(1, "ELIGIBLE");
        NAMES.put(2, "NON_ELIGIBLE_ASSISTANT_DICTATION_FLAG_DISABLED");
        NAMES.put(3, "NON_ELIGIBLE_ASSISTANT_NOT_CONNECTED_TO_KEYBOARD");
        NAMES.put(4, "NON_ELIGIBLE_ON_DEVICE_STACK_NOT_ENABLED");
        NAMES.put(9, "NON_ELIGIBLE_LOCALE_MISMATCH");
        NAMES.put(10, "NON_ELIGIBLE_UNSUPPORTED_INPUT_FIELD");
        NAMES.put(12, "NON_ELIGIBLE_ASSISTANT_VOICE_TYPING_SETTING_DISABLED");
        NAMES.put(30, "NON_ELIGIBLE_GBOARD_VOICE_TYPING_SETTING_DISABLED");
        NAMES.put(13, "NON_ELIGIBLE_TALKBACK_ACTIVE");
        NAMES.put(15, "NON_ELIGIBLE_WORK_PROFILE");
        NAMES.put(16, "NON_ELIGIBLE_KEYBOARD_INCOGNITO_MODE");
        NAMES.put(17, "NON_ELIGIBLE_AIAI_UNKNOWN_ERROR");
        NAMES.put(18, "NON_ELIGIBLE_AIAI_LANGUAGE_PACK_NOT_INSTALLED");
        NAMES.put(19, "NON_ELIGIBLE_AIAI_LANGUAGE_NOT_SUPPORTED");
        NAMES.put(20, "NON_ELIGIBLE_AIAI_RECOGNITION_SERVICE_MISSING");
        NAMES.put(21, "NON_ELIGIBLE_AIAI_TIMEOUT");
        NAMES.put(23, "NON_ELIGIBLE_AIAI_BUSY");
        NAMES.put(27, "NON_ELIGIBLE_AIAI_OLD_VERSION");
        NAMES.put(22, "NON_ELIGIBLE_ASSISTANT_MIC_PERMISSION_NOT_GRANTED");
        NAMES.put(25, "NON_ELIGIBLE_KEYBOARD_UNSUPPORTED_IME");
        NAMES.put(26, "NON_ELIGIBLE_DATA_FILES_NOT_DOWNLOADED");
        NAMES.put(28, "NON_ELIGIBLE_UNSUPPORTED_DICTATION_LOCALE");
        NAMES.put(31, "NON_ELIGIBLE_UNSUPPORTED_SYSTEM_LOCALE");
        NAMES.put(29, "NON_ELIGIBLE_SAA_DISABLED");
        NAMES.put(32, "NON_ELIGIBLE_NLUF_GROUPS_FOR_LOCALE_NOT_DOWNLOADED");
        NAMES.put(35, "NON_ELIGIBLE_ANDROID_OLD_VERSION");
        NAMES.put(36, "NON_ELIGIBLE_ASSISTANT_NOT_ENABLED");
        NAMES.put(37, "NON_ELIGIBLE_KEYBOARD_NOT_VISIBLE");
        NAMES.put(38, "NON_ELIGIBLE_MODEL_DOWNLOAD_NEEDS_USER_REQUEST");
        NAMES.put(39, "NON_ELIGIBLE_MODEL_DOWNLOAD_IN_PROGRESS");
        NAMES.put(40, "NON_ELIGIBLE_VOICE_ACCESSIBILITY_SERVICE_ENABLED");
        NAMES.put(41, "NON_ELIGIBLE_PACKAGE_SUSPENDED");
        NAMES.put(42, "NON_ELIGIBLE_LANGUAGE_PACK_NOT_SUPPORTED");
        NAMES.put(43, "NON_ELIGIBLE_AGSA_SODA_UNKNOWN_ERROR");
        NAMES.put(44, "NON_ELIGIBLE_AGSA_SODA_TIMEOUT");
        NAMES.put(45, "NON_ELIGIBLE_AGSA_SODA_BUSY");
        NAMES.put(46, "NON_ELIGIBLE_AGSA_SODA_LANGUAGE_NOT_SUPPORTED");
        NAMES.put(47, "NON_ELIGIBLE_SBG_UNKNOWN_ERROR");
        NAMES.put(48, "NON_ELIGIBLE_SBG_TIMEOUT");
        NAMES.put(49, "NON_ELIGIBLE_SBG_BUSY");
        NAMES.put(50, "NON_ELIGIBLE_SBG_LANGUAGE_NOT_SUPPORTED");
        NAMES.put(51, "NON_ELIGIBLE_NATIVE_SPLIT_UNAVAILABLE");
        NAMES.put(52, "NON_ELIGIBLE_MORSE_CODE_VARIANT");
        NAMES.put(53, "NON_ELIGIBLE_DISABLED_WITH_NETWORK_CONNECTION");
    }

    private VoiceDiagnostics() {
    }

    /** Replaced by the patch with "return true" when the advanced voice typing patch is applied. */
    public static boolean patched() {
        return false;
    }

    private static final String[] KEYWORDS = {
            "[SD", "[on-device]", "NGA", "Nga", "nga ", "ictation", "handleLaunchVoice", "andshake",
            "backend", "Backend", "ligib", "ration", "gRPC", "grpc", "SecurityPolicy", "PERMISSION_DENIED",
            "UNAVAILABLE", "Sbg", "SBG", "AiCore", "AICore", "Astrea", "ASR", "asr ", "Asr", "soda", "Soda",
            "SODA", "peech", "oice", "KeyboardService", "fallback", "Fallback", "avt ", "native split",
    };

    private static boolean relevant(String fmt) {
        for (String k : KEYWORDS) {
            if (fmt.contains(k)) return true;
        }
        return false;
    }

    /** Hook: Gboard's central log call (format string + arguments). Never throws. */
    public static void log(String fmt, Object[] args) {
        try {
            if (fmt == null) return;
            String text = fmt;
            if ("%s".equals(fmt) && args != null && args.length == 1 && args[0] instanceof String) {
                text = (String) args[0];
                if (!relevant(text)) return;
            } else {
                if (!relevant(fmt)) return;
                if (args != null && args.length > 0) {
                    try {
                        text = String.format(Locale.ROOT, fmt, args);
                    } catch (Throwable ignored) {
                        text = fmt;
                    }
                }
            }
            if (text.contains("text=") || text.contains("transcript")) return; // never keep dictated text
            add(nameEligibility(text));
        } catch (Throwable ignored) {
        }
    }

    static String nameEligibility(String text) {
        Matcher m = ELIGIBILITY.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = NAMES.get(Integer.parseInt(m.group(2)));
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + m.group(2)
                    + (name != null ? " (" + name + ")" : "")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Lets other patch extensions record their own diagnostic lines into the same log. */
    public static void note(String line) {
        try {
            if (line != null) add("[mp] " + line);
        } catch (Throwable ignored) {
        }
    }

    private static void add(String line) {
        String stamped = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  "
                + (line.length() > 300 ? line.substring(0, 300) + "\u2026" : line);
        synchronized (LINES) {
            ensureLoaded();
            if (stamped.substring(10).equals(LINES.peekLast() == null ? null : LINES.peekLast().substring(10))) {
                return; // skip exact repeats
            }
            LINES.addLast(stamped);
            while (LINES.size() > MAX_LINES) LINES.removeFirst();
            long now = System.currentTimeMillis();
            if (now - lastWrite > 1000) {
                lastWrite = now;
                persist();
            }
        }
    }

    private static File file() {
        Context c = SmartDictationData.appContext();
        return c == null ? null : new File(c.getFilesDir(), "voice_diagnostics.txt");
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            File f = file();
            if (f == null || !f.isFile()) return;
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            for (String l : new String(b, StandardCharsets.UTF_8).split("\n")) {
                if (!l.isEmpty()) LINES.addLast(l);
            }
            while (LINES.size() > MAX_LINES) LINES.removeFirst();
        } catch (Throwable ignored) {
        }
    }

    private static void persist() {
        try {
            File f = file();
            if (f == null) return;
            StringBuilder sb = new StringBuilder();
            for (String l : LINES) sb.append(l).append('\n');
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    /** All kept lines, oldest first. */
    static String dump() {
        synchronized (LINES) {
            ensureLoaded();
            persist();
            StringBuilder sb = new StringBuilder();
            for (String l : LINES) sb.append(l).append('\n');
            return sb.toString();
        }
    }

    static void clear() {
        synchronized (LINES) {
            ensureLoaded();
            LINES.clear();
            persist();
        }
    }
}
