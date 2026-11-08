package android.os;

import java.util.HashMap;
import java.util.Map;

/** Host stub: only what the module writes through reflection. */
public final class SystemProperties {
    private static final Map<String, String> VALUES = new HashMap<>();

    public static String get(String key, String fallback) {
        String value = VALUES.get(key);
        return value == null ? fallback : value;
    }

    public static void set(String key, String value) {
        VALUES.put(key, value);
    }

    private SystemProperties() {}
}
