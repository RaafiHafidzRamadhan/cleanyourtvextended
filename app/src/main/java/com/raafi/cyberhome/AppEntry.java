package com.raafi.cyberhome;

import android.graphics.drawable.Drawable;

final class AppEntry {

    final String pkg;
    final String activity;
    final String label;
    final Drawable icon;
    final boolean leanback;

    AppEntry(String pkg, String activity, String label, Drawable icon, boolean leanback) {
        this.pkg = pkg;
        this.activity = activity;
        this.label = label;
        this.icon = icon;
        this.leanback = leanback;
    }
}
