package com.geely.drivemem.util;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import java.util.Locale;

/** App-only language preference, including on the Android 9 head unit. */
public final class AppLanguage {
    public static final String SYSTEM = "";
    public static final String ENGLISH = "en";
    public static final String THAI = "th";
    private static final String KEY = "app_language";

    private AppLanguage() {}

    public static String selected(Context context) {
        String language = context.getSharedPreferences("drivemem", Context.MODE_PRIVATE)
            .getString(KEY, SYSTEM);
        return ENGLISH.equals(language) || THAI.equals(language) ? language : SYSTEM;
    }

    public static void save(Context context, String language) {
        if (!SYSTEM.equals(language) && !ENGLISH.equals(language) && !THAI.equals(language)) {
            throw new IllegalArgumentException("Unsupported app language: " + language);
        }
        context.getSharedPreferences("drivemem", Context.MODE_PRIVATE)
            .edit().putString(KEY, language).apply();
        applyToApplication(context);
    }

    private static Configuration configuration(Context context) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        String language = selected(context);
        LocaleList locales = SYSTEM.equals(language)
            ? Resources.getSystem().getConfiguration().getLocales()
            : new LocaleList(Locale.forLanguageTag(language));
        config.setLocales(locales);
        config.setLayoutDirection(locales.get(0));
        return config;
    }

    public static Context wrap(Context context) {
        return context.createConfigurationContext(configuration(context));
    }

    public static Locale locale(Context context) {
        return context.getResources().getConfiguration().getLocales().get(0);
    }

    /** Format display dates at render time; stored ISO dates remain unchanged. */
    public static String date(Context context, long timeMs, String skeleton) {
        Locale locale = locale(context);
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton);
        return new java.text.SimpleDateFormat(pattern, locale).format(new java.util.Date(timeMs));
    }

    /** Refresh contexts held by process-wide state without restarting car services.
     * Activities use their own configuration contexts and recreate on return.
     * Do not change Locale.setDefault(): protocol/storage formatting must stay stable. */
    @SuppressWarnings("deprecation")
    public static void applyToApplication(Context context) {
        Context app = context.getApplicationContext();
        Resources resources = app.getResources();
        resources.updateConfiguration(configuration(app), resources.getDisplayMetrics());
    }
}
