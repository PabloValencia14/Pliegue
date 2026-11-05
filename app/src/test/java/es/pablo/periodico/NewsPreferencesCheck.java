package es.pablo.periodico;

import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;

// Run with app_process so ContextWrapper uses the real Android implementation.
public final class NewsPreferencesCheck {
    public static void main(String[] args) {
        HashMap<String, Boolean> values = new HashMap<>();
        SharedPreferences settings = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("getBoolean")) return values.getOrDefault((String) arguments[0], (Boolean) arguments[1]);
                throw new UnsupportedOperationException(method.getName());
            });
        ContextWrapper context = new ContextWrapper(null) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return settings; }
        };
        require(News.enabled(context, "Internacional"), "Default international section is disabled");
        values.put("Mundo", false);
        require(!News.enabled(context, "Internacional"), "Legacy disabled selection was lost");
        values.put("Internacional", true);
        require(News.enabled(context, "Internacional"), "Explicit new selection did not override legacy preference");
        values.put("Mundo", true);
        values.put("Internacional", false);
        require(!News.enabled(context, "Internacional"), "Explicit disabled selection did not override legacy preference");
        require(News.enabled(context, "Actualidad"), "Unrelated topic changed");
        System.out.println("NewsPreferencesCheck: legacy and explicit international preferences passed");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
