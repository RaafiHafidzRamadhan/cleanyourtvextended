package com.raafi.cyberhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds every launchable app once, then lets either the launcher or the web remote order and
 * filter the result using the shared layout configuration.
 */
final class AppScanner {

    private static final List<String> PRIORITY = Arrays.asList(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.android.vending",
            "com.google.android.youtube.tvmusic",
            "com.google.android.videos",
            "com.iqiyi.i18n.tv",
            "com.google.android.play.games");

    private AppScanner() {
    }

    static List<AppEntry> scan(Context context) {
        PackageManager pm = context.getPackageManager();
        Set<String> homePackages = new HashSet<>();
        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo info : pm.queryIntentActivities(homeIntent, 0)) {
            if (info.activityInfo != null) {
                homePackages.add(info.activityInfo.packageName);
            }
        }

        Map<String, AppEntry> found = new LinkedHashMap<>();
        collect(context, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, found, homePackages);
        collect(context, pm, Intent.CATEGORY_LAUNCHER, false, found, homePackages);

        List<AppEntry> apps = new ArrayList<>(found.values());
        Collections.sort(apps, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                int priorityA = PRIORITY.indexOf(a.pkg);
                int priorityB = PRIORITY.indexOf(b.pkg);
                if (priorityA < 0) {
                    priorityA = 999;
                }
                if (priorityB < 0) {
                    priorityB = 999;
                }
                if (priorityA != priorityB) {
                    return priorityA - priorityB;
                }
                int byLabel = a.label.compareToIgnoreCase(b.label);
                if (byLabel != 0) {
                    return byLabel;
                }
                return a.pkg.compareTo(b.pkg);
            }
        });
        return apps;
    }

    /**
     * Applies the saved order, optionally dropping hidden packages.
     */
    static List<AppEntry> order(List<AppEntry> scanned, LayoutConfig config, boolean includeHidden) {
        if (scanned == null || scanned.isEmpty()) {
            return new ArrayList<>();
        }
        LayoutConfig safe = config == null ? new LayoutConfig() : config;
        Set<String> hidden = new HashSet<>(safe.hiddenApps);
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < safe.order.size(); i++) {
            rank.put(safe.order.get(i), i);
        }

        List<AppEntry> result = new ArrayList<>();
        for (AppEntry entry : scanned) {
            if (!includeHidden && hidden.contains(entry.pkg)) {
                continue;
            }
            result.add(entry);
        }
        Collections.sort(result, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                Integer rankA = rank.get(a.pkg);
                Integer rankB = rank.get(b.pkg);
                int valueA = rankA == null ? Integer.MAX_VALUE : rankA;
                int valueB = rankB == null ? Integer.MAX_VALUE : rankB;
                if (valueA != valueB) {
                    return valueA < valueB ? -1 : 1;
                }
                return 0;
            }
        });
        // Collections.sort is stable: packages without a rank keep the scanner's sensible order.
        return result;
    }

    static List<AppEntry> hidden(List<AppEntry> scanned, LayoutConfig config) {
        List<AppEntry> result = new ArrayList<>();
        if (scanned == null || config == null) {
            return result;
        }
        Set<String> hidden = new HashSet<>(config.hiddenApps);
        for (AppEntry entry : scanned) {
            if (hidden.contains(entry.pkg)) {
                result.add(entry);
            }
        }
        return result;
    }

    static Intent launchIntent(Context context, String pkg) {
        if (TextUtils.isEmpty(pkg)) {
            return null;
        }
        PackageManager pm = context.getPackageManager();
        Intent intent = null;
        try {
            intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        } catch (Exception ignored) {
        }
        if (intent == null) {
            try {
                intent = pm.getLaunchIntentForPackage(pkg);
            } catch (Exception ignored) {
            }
        }
        if (intent == null) {
            for (AppEntry entry : scan(context)) {
                if (entry.pkg.equals(pkg)) {
                    intent = new Intent(Intent.ACTION_MAIN);
                    intent.addCategory(entry.leanback
                            ? Intent.CATEGORY_LEANBACK_LAUNCHER
                            : Intent.CATEGORY_LAUNCHER);
                    intent.setComponent(new ComponentName(entry.pkg, entry.activity));
                    break;
                }
            }
        }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
        return intent;
    }

    private static void collect(Context context, PackageManager pm, String category,
                                boolean leanback, Map<String, AppEntry> found,
                                Set<String> homePackages) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo info : pm.queryIntentActivities(intent, 0)) {
            ActivityInfo activity = info.activityInfo;
            if (activity == null) {
                continue;
            }
            String pkg = activity.packageName;
            if (TextUtils.isEmpty(pkg) || pkg.equals(context.getPackageName())) {
                continue;
            }
            if (homePackages.contains(pkg) && !pkg.contains("settings")) {
                continue;
            }
            AppEntry old = found.get(pkg);
            if (old != null && (!leanback || old.leanback)) {
                continue;
            }

            String label = null;
            try {
                label = info.loadLabel(pm).toString().trim();
            } catch (Exception ignored) {
            }
            if (TextUtils.isEmpty(label)) {
                label = pkg;
            }

            Drawable icon = null;
            try {
                icon = info.loadIcon(pm);
            } catch (Exception ignored) {
            }
            if (icon == null) {
                icon = pm.getDefaultActivityIcon();
            }

            found.put(pkg, new AppEntry(pkg, activity.name, label, icon, leanback));
        }
    }
}
