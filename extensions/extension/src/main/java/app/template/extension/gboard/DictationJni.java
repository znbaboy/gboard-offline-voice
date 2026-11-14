package app.template.extension.gboard;

/** Loads libdictation_jni.so (formatter + emoji NLU natives). The smi.a()=true patch skips Gboard's own load. */
public final class DictationJni {
    private static volatile int state; // 0 = not tried, 1 = loaded, -1 = failed

    private DictationJni() {}

    public static boolean ensure() {
        int s = state;
        if (s != 0) return s > 0;
        synchronized (DictationJni.class) {
            if (state != 0) return state > 0;
            try {
                System.loadLibrary("dictation_jni");
                state = 1;
            } catch (Throwable t) {
                state = -1;
            }
            return state > 0;
        }
    }
}
