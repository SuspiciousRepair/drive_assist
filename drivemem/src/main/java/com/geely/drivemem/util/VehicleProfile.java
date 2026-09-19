package com.geely.drivemem.util;

import android.content.SharedPreferences;

/** Local vehicle appearance preferences, independent of vehicle commands. */
public final class VehicleProfile {
    public static final String NAME_KEY = "vehicle_name";
    public static final String MODEL_KEY = "vehicle_model";
    public static final String MODEL_EX2 = "ex2";
    public static final String MODEL_EX2_MAX = "ex2_max";
    public static final String DEFAULT_NAME = "Geely EX2";
    public static final int NAME_LIMIT = 40;

    private VehicleProfile() {}

    public static String customName(SharedPreferences prefs) {
        return prefs.getString(NAME_KEY, "");
    }

    public static String getModel(SharedPreferences prefs) {
        return validModel(prefs.getString(MODEL_KEY, MODEL_EX2));
    }

    public static String modelName(SharedPreferences prefs) {
        return modelName(getModel(prefs));
    }

    public static String modelName(String model) {
        return MODEL_EX2_MAX.equals(model) ? "Geely EX2 Max" : DEFAULT_NAME;
    }

    public static String displayName(SharedPreferences prefs) {
        String name = customName(prefs);
        String model = modelName(prefs);
        return name.isEmpty() ? model : model + "\n" + name;
    }

    public static void saveProfile(SharedPreferences prefs, String model, String nickname) {
        SharedPreferences.Editor editor = prefs.edit().putString(MODEL_KEY, validModel(model));
        writeName(editor, nickname);
        editor.apply();
    }

    public static void saveName(SharedPreferences prefs, String text) {
        SharedPreferences.Editor editor = prefs.edit();
        writeName(editor, text);
        editor.apply();
    }

    private static String validModel(String model) {
        return MODEL_EX2_MAX.equals(model) ? MODEL_EX2_MAX : MODEL_EX2;
    }

    private static void writeName(SharedPreferences.Editor editor, String text) {
        String name = text.replaceAll("[\\p{Z}\\s]+", " ").trim();
        if (name.isEmpty()) editor.remove(NAME_KEY);
        else editor.putString(NAME_KEY, name);
    }
}
