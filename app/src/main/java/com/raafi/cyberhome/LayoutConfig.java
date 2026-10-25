package com.raafi.cyberhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The personalisable part of the Home screen. Everything here is safe to change from the
 * web remote: values are clamped, colours are validated and unknown keys are ignored.
 */
final class LayoutConfig {

    static final int VERSION = 1;

    int columns = 5;
    int iconSize = 42;
    int labelSize = 12;
    int rowHeight = 94;
    int cellGap = 5;
    int rowGap = 10;
    int sidePadding = 44;
    int topPadding = 26;
    int bottomPadding = 22;
    int wallpaperDim = 52;

    String accent = "#4C8DFF";
    String theme = "auto";
    String greetingName = "";

    boolean showClock = true;
    boolean showStatus = true;
    boolean showWeather = true;
    boolean showMode = true;
    boolean showSettings = true;
    boolean showProfile = true;
    boolean showWeb = true;
    boolean showGlow = true;
    boolean showLabels = true;

    final List<String> hiddenApps = new ArrayList<>();
    final List<String> order = new ArrayList<>();

    LayoutConfig copy() {
        LayoutConfig copy = new LayoutConfig();
        copy.columns = columns;
        copy.iconSize = iconSize;
        copy.labelSize = labelSize;
        copy.rowHeight = rowHeight;
        copy.cellGap = cellGap;
        copy.rowGap = rowGap;
        copy.sidePadding = sidePadding;
        copy.topPadding = topPadding;
        copy.bottomPadding = bottomPadding;
        copy.wallpaperDim = wallpaperDim;
        copy.accent = accent;
        copy.theme = theme;
        copy.greetingName = greetingName;
        copy.showClock = showClock;
        copy.showStatus = showStatus;
        copy.showWeather = showWeather;
        copy.showMode = showMode;
        copy.showSettings = showSettings;
        copy.showProfile = showProfile;
        copy.showWeb = showWeb;
        copy.showGlow = showGlow;
        copy.showLabels = showLabels;
        copy.hiddenApps.addAll(hiddenApps);
        copy.order.addAll(order);
        return copy;
    }

    static LayoutConfig fromJson(JSONObject source) {
        LayoutConfig config = new LayoutConfig();
        if (source == null) {
            return config;
        }
        config.columns = source.optInt("columns", config.columns);
        config.iconSize = source.optInt("icon_size", config.iconSize);
        config.labelSize = source.optInt("label_size", config.labelSize);
        config.rowHeight = source.optInt("row_height", config.rowHeight);
        config.cellGap = source.optInt("cell_gap", config.cellGap);
        config.rowGap = source.optInt("row_gap", config.rowGap);
        config.sidePadding = source.optInt("side_padding", config.sidePadding);
        config.topPadding = source.optInt("top_padding", config.topPadding);
        config.bottomPadding = source.optInt("bottom_padding", config.bottomPadding);
        config.wallpaperDim = source.optInt("wallpaper_dim", config.wallpaperDim);

        config.accent = source.optString("accent", config.accent);
        config.theme = source.optString("theme", config.theme);
        config.greetingName = source.optString("greeting_name", config.greetingName);

        config.showClock = source.optBoolean("show_clock", config.showClock);
        config.showStatus = source.optBoolean("show_status", config.showStatus);
        config.showWeather = source.optBoolean("show_weather", config.showWeather);
        config.showMode = source.optBoolean("show_mode", config.showMode);
        config.showSettings = source.optBoolean("show_settings", config.showSettings);
        config.showProfile = source.optBoolean("show_profile", config.showProfile);
        config.showWeb = source.optBoolean("show_web", config.showWeb);
        config.showGlow = source.optBoolean("show_glow", config.showGlow);
        config.showLabels = source.optBoolean("show_labels", config.showLabels);

        readPackageList(source.optJSONArray("hidden_apps"), config.hiddenApps);
        readPackageList(source.optJSONArray("order"), config.order);

        config.normalize();
        return config;
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("version", VERSION);
            json.put("columns", columns);
            json.put("icon_size", iconSize);
            json.put("label_size", labelSize);
            json.put("row_height", rowHeight);
            json.put("cell_gap", cellGap);
            json.put("row_gap", rowGap);
            json.put("side_padding", sidePadding);
            json.put("top_padding", topPadding);
            json.put("bottom_padding", bottomPadding);
            json.put("wallpaper_dim", wallpaperDim);
            json.put("accent", accent);
            json.put("theme", theme);
            json.put("greeting_name", greetingName);
            json.put("show_clock", showClock);
            json.put("show_status", showStatus);
            json.put("show_weather", showWeather);
            json.put("show_mode", showMode);
            json.put("show_settings", showSettings);
            json.put("show_profile", showProfile);
            json.put("show_web", showWeb);
            json.put("show_glow", showGlow);
            json.put("show_labels", showLabels);
            json.put("hidden_apps", new JSONArray(hiddenApps));
            json.put("order", new JSONArray(order));
        } catch (JSONException ignored) {
        }
        return json;
    }

    void normalize() {
        columns = clamp(columns, 3, 8);
        iconSize = clamp(iconSize, 28, 64);
        labelSize = clamp(labelSize, 8, 18);
        rowHeight = clamp(rowHeight, 72, 148);
        cellGap = clamp(cellGap, 2, 16);
        rowGap = clamp(rowGap, 4, 26);
        sidePadding = clamp(sidePadding, 16, 96);
        topPadding = clamp(topPadding, 8, 64);
        bottomPadding = clamp(bottomPadding, 8, 48);
        wallpaperDim = clamp(wallpaperDim, 0, 85);

        if (!isHexColor(accent)) {
            accent = "#4C8DFF";
        } else {
            accent = accent.toUpperCase(Locale.US);
        }
        if (!"light".equals(theme) && !"dark".equals(theme)) {
            theme = "auto";
        }
        greetingName = greetingName == null ? "" : greetingName.trim();
        if (greetingName.length() > 24) {
            greetingName = greetingName.substring(0, 24);
        }

        dedupe(hiddenApps);
        dedupe(order);
    }

    static boolean isHexColor(String value) {
        return value != null && value.matches("^#[0-9a-fA-F]{6}$");
    }

    static boolean isPackageName(String value) {
        return value != null
                && value.length() >= 3
                && value.length() <= 180
                && value.matches("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$");
    }

    private static void readPackageList(JSONArray array, List<String> target) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length() && target.size() < 400; i++) {
            String value = array.optString(i, null);
            if (isPackageName(value)) {
                target.add(value);
            }
        }
    }

    private static void dedupe(List<String> values) {
        Set<String> unique = new LinkedHashSet<>(values);
        values.clear();
        values.addAll(unique);
        while (values.size() > 400) {
            values.remove(values.size() - 1);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
