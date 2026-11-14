package app.template.extension.gboard;

/**
 * Values the "Advanced voice typing" patch substitutes for Gboard feature flag reads.
 * Used in place of the flag's value right after Gboard reads it.
 */
@SuppressWarnings("unused")
public final class AdvancedVoiceFlags {
    /** Speech Services by Google: on-device speech recognition. (5 would be S3, Google's server.) */
    private static final Long ASR_PROVIDER_SBG = 6L;

    private AdvancedVoiceFlags() {
    }

    public static Object enabled() {
        return Boolean.TRUE;
    }

    public static Object disabled() {
        return Boolean.FALSE;
    }

    public static Object onDeviceAsrProvider() {
        return ASR_PROVIDER_SBG;
    }

    /**
     * Locale used for Speech Services by Google: any English variant (en-CA, en-GB, ...) uses the
     * en-US on-device model, which Speech Services ships offline.
     */
    public static java.util.Locale sbgLocale(java.util.Locale locale) {
        if (locale != null && "en".equals(locale.getLanguage()) && !"US".equals(locale.getCountry())) {
            return java.util.Locale.US;
        }
        return locale;
    }

    /**
     * For code that passes the flag object itself on (and reads it later): returns a proxy of the
     * flag whose value getter g() returns true; every other call goes to the real flag.
     */
    public static Object forceEnabled(Object flag) {
        if (flag == null) return null;
        try {
            java.util.LinkedHashSet<Class<?>> interfaces = new java.util.LinkedHashSet<>();
            for (Class<?> c = flag.getClass(); c != null; c = c.getSuperclass()) {
                for (Class<?> i : c.getInterfaces()) interfaces.add(i);
            }
            if (interfaces.isEmpty()) return flag;
            return java.lang.reflect.Proxy.newProxyInstance(
                    flag.getClass().getClassLoader(),
                    interfaces.toArray(new Class<?>[0]),
                    (proxy, method, args) -> {
                        if ("g".equals(method.getName()) && method.getParameterTypes().length == 0
                                && method.getReturnType() == Object.class) {
                            return Boolean.TRUE;
                        }
                        try {
                            return method.invoke(flag, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        } catch (Throwable t) {
            return flag;
        }
    }
}
