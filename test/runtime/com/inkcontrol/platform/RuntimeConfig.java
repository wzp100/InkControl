package com.inkcontrol.platform;
import com.inkcontrol.core.Configuration;
import java.util.ArrayList;
public final class RuntimeConfig {
    private static Configuration config = new Configuration();
    private static final ArrayList<Runnable> listeners = new ArrayList<>();
    public static boolean enabled(String id) { return config.enabled(id); }
    public static String value(String id) { return config.value(id); }
    public static void listen(Runnable listener) { listeners.add(listener); }
    public static void enableFixes() {
        for (String id : new String[]{"fix.video","fix.modes","fix.configlock","fix.exceptions","fix.permissions"}) config = config.change(id, true, "default");
    }
    public static void disableAll() { config = config.disableAll(); for (Runnable listener : listeners) listener.run(); }
    public static void fail(String message) { throw new AssertionError(message); }
}
