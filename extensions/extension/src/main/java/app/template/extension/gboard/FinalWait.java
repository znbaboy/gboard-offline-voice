package app.template.extension.gboard;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.lang.reflect.Method;

/**
 * Holds a keyboard "stop dictation" request until Speech Services' final result for the current
 * utterance has been committed (or a timeout passes). Without this, Gboard tears the session down
 * the moment the mic is tapped and drops the final result, leaving the last partial (truncated,
 * missing final punctuation).
 */
@SuppressWarnings("unused")
public final class FinalWait {
    private static final long MAX_WAIT_MS = 1800;
    private static final long AFTER_FINAL_MS = 150;
    private static final String KEYBOARD_STOP = "SUCCESS_KEYBOARD_STOP_REQUEST";

    private static volatile long lastResult;
    private static volatile long lastFinal;

    private static final Object LOCK = new Object();
    private static Object target;
    private static Object status;
    private static String methodName;
    private static final ThreadLocal<Boolean> BYPASS = new ThreadLocal<>();

    private static Handler handler;

    private FinalWait() {
    }

    private static Handler handler() {
        synchronized (LOCK) {
            if (handler == null) {
                HandlerThread t = new HandlerThread("mp-finalwait");
                t.start();
                handler = new Handler(t.getLooper());
            }
            return handler;
        }
    }

    /** Hook: Gboard received recognized text (DictationLogger.onTextReceived). */
    public static void onText(boolean isFinal, String text) {
        long now = SystemClock.uptimeMillis();
        lastResult = now;
        if (!isFinal) return;
        lastFinal = now;
        boolean pending;
        synchronized (LOCK) {
            pending = target != null;
        }
        if (pending) handler().postDelayed(FinalWait::fire, AFTER_FINAL_MS);
    }

    /**
     * Hook at the start of Dictation.stop(status). Returns true to postpone this stop (the caller
     * then takes its "stop already in progress" path); the stop is re-issued later.
     */
    public static boolean shouldDefer(Object dictation, Object stopStatus, String stopMethod) {
        try {
            if (Boolean.TRUE.equals(BYPASS.get())) return false;
            if (!(stopStatus instanceof Enum) || !KEYBOARD_STOP.equals(((Enum<?>) stopStatus).name())) return false;
            synchronized (LOCK) {
                if (target != null) {
                    // Second tap while waiting: stop now.
                    target = null;
                    return false;
                }
                if (lastResult <= lastFinal) return false; // nothing un-finalized on screen
                target = dictation;
                status = stopStatus;
                methodName = stopMethod;
            }
            handler().postDelayed(FinalWait::fire, MAX_WAIT_MS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void fire() {
        Object d, s;
        String name;
        synchronized (LOCK) {
            if (target == null) return;
            d = target;
            s = status;
            name = methodName;
            target = null;
        }
        BYPASS.set(Boolean.TRUE);
        try {
            for (Class<?> c = d.getClass(); c != null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0].isInstance(s)) {
                        m.setAccessible(true);
                        m.invoke(d, s);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            BYPASS.remove();
        }
    }
}
