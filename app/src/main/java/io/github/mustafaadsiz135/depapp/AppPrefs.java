package io.github.mustafaadsiz135.depapp;

import android.content.Context;
import android.content.SharedPreferences;

/** Ayarlar ve olay kayıtları (telefon hafızasında saklanır). */
public final class AppPrefs {
    private static final String FILE = "depapp";
    private static final String K_SENS = "sensitivity";
    private static final String K_SOUND = "sound";
    private static final String K_LOG = "log";
    private static final String SEP = "\n⁣\n";
    private static final int MAX_LOG = 50;

    private AppPrefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static int getSensitivity(Context c) { return sp(c).getInt(K_SENS, 3); }
    public static void setSensitivity(Context c, int v) { sp(c).edit().putInt(K_SENS, v).apply(); }

    public static boolean isSoundOn(Context c) { return sp(c).getBoolean(K_SOUND, true); }
    public static void setSoundOn(Context c, boolean v) { sp(c).edit().putBoolean(K_SOUND, v).apply(); }

    public static synchronized void addLog(Context c, String entry) {
        String old = sp(c).getString(K_LOG, "");
        String[] parts = old.isEmpty() ? new String[0] : old.split(SEP);
        StringBuilder sb = new StringBuilder(entry);
        for (int i = 0; i < parts.length && i < MAX_LOG - 1; i++) {
            sb.append(SEP).append(parts[i]);
        }
        sp(c).edit().putString(K_LOG, sb.toString()).apply();
    }

    public static synchronized String[] getLog(Context c) {
        String s = sp(c).getString(K_LOG, "");
        return s.isEmpty() ? new String[0] : s.split(SEP);
    }

    public static synchronized void clearLog(Context c) {
        sp(c).edit().remove(K_LOG).apply();
    }
}
