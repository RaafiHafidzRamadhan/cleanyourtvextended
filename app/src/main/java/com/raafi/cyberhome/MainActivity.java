package com.raafi.cyberhome;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int MODE_AUTO = 0;
    private static final int MODE_LIGHT = 1;
    private static final int MODE_DARK = 2;
    private static final long TICK_MS = 15_000L;
    private static final long WEATHER_TTL = 30L * 60L * 1000L;
    private static final long WEATHER_RETRY_MS = 5L * 60L * 1000L;
    private static final Locale INDONESIAN = new Locale("id", "ID");

    private static final class Palette {
        final int bg;
        final int surface;
        final int chipBg;
        final int stroke;
        final int textPrimary;
        final int textSecondary;
        final int accent;
        final int accentSoft;
        final int clock;

        Palette(int bg, int surface, int chipBg, int stroke, int textPrimary,
                int textSecondary, int accent, int accentSoft, int clock) {
            this.bg = bg;
            this.surface = surface;
            this.chipBg = chipBg;
            this.stroke = stroke;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.accent = accent;
            this.accentSoft = accentSoft;
            this.clock = clock;
        }

        static Palette dark(int accent) {
            int safeAccent = ensureVisibleAccent(accent, true);
            return new Palette(
                    0xFF070B12, 0xFF0D1522, 0xFF0F1928, 0xFF1D2B3D,
                    0xFFEAF1FA, 0xFF8B9AB0, safeAccent,
                    blend(0xFF0D1522, safeAccent, 0.20f), 0xFFF3F7FF);
        }

        static Palette light(int accent) {
            int safeAccent = ensureVisibleAccent(accent, false);
            return new Palette(
                    0xFFF2F6FC, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFDEE7F3,
                    0xFF0A1422, 0xFF5C6D84, safeAccent,
                    blend(0xFFFFFFFF, safeAccent, 0.12f), 0xFF071120);
        }
    }

    private static final class AppCell {
        final AppEntry entry;
        final View view;
        final ImageView icon;
        final TextView label;
        final int row;
        final int col;

        AppCell(AppEntry entry, View view, ImageView icon, TextView label, int row, int col) {
            this.entry = entry;
            this.view = view;
            this.icon = icon;
            this.label = label;
            this.row = row;
            this.col = col;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<AppEntry> apps = new ArrayList<>();
    private final List<AppCell> cells = new ArrayList<>();
    private final List<LinearLayout> gridRows = new ArrayList<>();
    private final List<View> topActions = new ArrayList<>();
    private final List<ObjectAnimator> ambientAnimations = new ArrayList<>();
    private final Map<View, AppEntry> cellApps = new HashMap<>();

    private SharedPreferences prefs;
    private LayoutStore layoutStore;
    private LayoutConfig config = new LayoutConfig();
    private Palette palette;
    private boolean darkMode;
    private int mode;
    private long appliedRevision = -1L;
    private int wallpaperGeneration;
    private boolean weatherLoading;
    private long lastWeatherAttempt;
    private WeatherClient.Weather cachedWeather;
    private String clockText = "";
    private String statusText = "";

    private View root;
    private View content;
    private View glowTop;
    private View glowBottom;
    private View bottomFade;
    private ImageView wallpaperView;
    private View wallpaperDim;
    private View timeBlock;
    private View statusRow;
    private View actionRow;
    private View headerDivider;
    private View liveDot;
    private TextView clock;
    private TextView statusLine;
    private TextView appsHeading;
    private TextView appsCount;
    private TextView hints;
    private TextView weatherTemp;
    private TextView weatherDesc;
    private TextView settingsLabel;
    private TextView profileLabel;
    private LinearLayout weatherChip;
    private LinearLayout settingsChip;
    private LinearLayout profileChip;
    private LinearLayout sectionHeader;
    private LinearLayout gridContainer;
    private ImageView modeBtn;
    private ImageView webBtn;
    private ImageView settingsIcon;
    private ImageView profileIcon;
    private View avatar;
    private WeatherIconView weatherIcon;
    private ScrollView appScroll;
    private View lastTopFocus;
    private View lastGridFocus;

    private final LayoutStore.Listener layoutListener = new LayoutStore.Listener() {
        @Override
        public void onLayoutChanged() {
            applyLayoutConfig();
        }

        @Override
        public void onWallpaperChanged() {
            loadWallpaper();
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateClockAndStatus(true);
            maybeSwitchAutoTheme();
            refreshWeatherIfStale();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable refreshApps = new Runnable() {
        @Override
        public void run() {
            loadApps();
            rebuildGrid(false);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(refreshApps);
            handler.postDelayed(refreshApps, 450L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("minimalhome", MODE_PRIVATE);
        layoutStore = LayoutStore.get(this);
        config = layoutStore.getConfig();
        mode = modeFromTheme(config.theme);

        bindViews();
        lastTopFocus = firstVisibleTopAction();
        setupActions();

        applyLayoutConfig();
        updateClockAndStatus(false);
        cachedWeather = readCachedWeather();
        renderWeather(cachedWeather, cachedWeather != null);

        playEntrance();
        hideSystemUi();
        registerPackageReceiver();
        refreshWeatherIfStale();
        PreviewBridge.attach(this);
        WebService.start(this);
    }

    private void bindViews() {
        root = findViewById(R.id.root);
        content = findViewById(R.id.content);
        glowTop = findViewById(R.id.glowTop);
        glowBottom = findViewById(R.id.glowBottom);
        bottomFade = findViewById(R.id.bottomFade);
        wallpaperView = findViewById(R.id.wallpaper);
        wallpaperDim = findViewById(R.id.wallpaperDim);
        timeBlock = findViewById(R.id.timeBlock);
        statusRow = findViewById(R.id.statusRow);
        actionRow = findViewById(R.id.actionRow);
        headerDivider = findViewById(R.id.headerDivider);
        liveDot = findViewById(R.id.liveDot);
        clock = findViewById(R.id.clock);
        statusLine = findViewById(R.id.statusLine);
        appsHeading = findViewById(R.id.appsHeading);
        appsCount = findViewById(R.id.appsCount);
        hints = findViewById(R.id.hints);
        weatherTemp = findViewById(R.id.weatherTemp);
        weatherDesc = findViewById(R.id.weatherDesc);
        settingsLabel = findViewById(R.id.settingsLabel);
        profileLabel = findViewById(R.id.profileLabel);
        weatherChip = findViewById(R.id.weatherChip);
        settingsChip = findViewById(R.id.settingsChip);
        profileChip = findViewById(R.id.profileChip);
        sectionHeader = findViewById(R.id.sectionHeader);
        gridContainer = findViewById(R.id.gridContainer);
        modeBtn = findViewById(R.id.modeBtn);
        webBtn = findViewById(R.id.webBtn);
        settingsIcon = findViewById(R.id.settingsIcon);
        profileIcon = findViewById(R.id.profileIcon);
        avatar = findViewById(R.id.avatar);
        weatherIcon = findViewById(R.id.weatherIcon);
        appScroll = findViewById(R.id.appScroll);

        topActions.add(weatherChip);
        topActions.add(modeBtn);
        topActions.add(webBtn);
        topActions.add(settingsChip);
        topActions.add(profileChip);
    }

    private void setupActions() {
        weatherChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                fetchWeather(true);
            }
        });
        modeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                cycleMode();
            }
        });
        webBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openWebPanel();
            }
        });
        settingsChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openSettings();
            }
        });
        profileChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openAccounts();
            }
        });

        for (final View action : topActions) {
            action.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean hasFocus) {
                    if (hasFocus) {
                        lastTopFocus = view;
                    }
                    view.animate().cancel();
                    view.animate()
                            .scaleX(hasFocus ? 1.03f : 1f)
                            .scaleY(hasFocus ? 1.03f : 1f)
                            .setDuration(130)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                }
            });
        }
    }

    private void applyLayoutConfig() {
        config = layoutStore.getConfig();
        appliedRevision = layoutStore.getRevision();
        mode = modeFromTheme(config.theme);

        boolean targetDark = chooseDark();
        darkMode = targetDark;
        applyPalette(targetDark ? Palette.dark(accentColor()) : Palette.light(accentColor()));
        updateModeIcon();
        applyLayoutChrome();
        loadApps();
        rebuildGrid(false);
        loadWallpaper();
    }

    private void applyLayoutChrome() {
        content.setPadding(dp(config.sidePadding), dp(config.topPadding),
                dp(config.sidePadding), dp(config.bottomPadding));

        timeBlock.setVisibility(config.showClock || config.showStatus
                ? View.VISIBLE : View.GONE);
        clock.setVisibility(config.showClock ? View.VISIBLE : View.GONE);
        statusRow.setVisibility(config.showStatus ? View.VISIBLE : View.GONE);

        weatherChip.setVisibility(config.showWeather ? View.VISIBLE : View.GONE);
        modeBtn.setVisibility(config.showMode ? View.VISIBLE : View.GONE);
        webBtn.setVisibility(config.showWeb ? View.VISIBLE : View.GONE);
        settingsChip.setVisibility(config.showSettings ? View.VISIBLE : View.GONE);
        profileChip.setVisibility(config.showProfile ? View.VISIBLE : View.GONE);

        boolean anyAction = config.showWeather || config.showMode || config.showWeb
                || config.showSettings || config.showProfile;
        actionRow.setVisibility(anyAction ? View.VISIBLE : View.GONE);

        glowTop.setVisibility(config.showGlow ? View.VISIBLE : View.GONE);
        glowBottom.setVisibility(config.showGlow ? View.VISIBLE : View.GONE);

        if (lastTopFocus == null || lastTopFocus.getVisibility() != View.VISIBLE) {
            View first = firstVisibleTopAction();
            if (first != null) {
                lastTopFocus = first;
            }
        }
    }

    private View firstVisibleTopAction() {
        for (View action : topActions) {
            if (action.getVisibility() == View.VISIBLE) {
                return action;
            }
        }
        return null;
    }

    private List<View> visibleTopActions() {
        List<View> visible = new ArrayList<>();
        for (View action : topActions) {
            if (action.getVisibility() == View.VISIBLE) {
                visible.add(action);
            }
        }
        return visible;
    }

    private int modeFromTheme(String theme) {
        if ("light".equals(theme)) {
            return MODE_LIGHT;
        }
        if ("dark".equals(theme)) {
            return MODE_DARK;
        }
        return MODE_AUTO;
    }

    private String themeFromMode(int value) {
        if (value == MODE_LIGHT) {
            return "light";
        }
        if (value == MODE_DARK) {
            return "dark";
        }
        return "auto";
    }

    private int accentColor() {
        try {
            return Color.parseColor(config.accent);
        } catch (Exception exception) {
            return 0xFF4C8DFF;
        }
    }

    private boolean chooseDark() {
        if (mode == MODE_LIGHT) {
            return false;
        }
        if (mode == MODE_DARK) {
            return true;
        }
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour < 6 || hour >= 18;
    }

    private void maybeSwitchAutoTheme() {
        if (mode != MODE_AUTO) {
            return;
        }
        boolean targetDark = chooseDark();
        if (targetDark != darkMode) {
            switchTheme(targetDark);
        }
    }

    private void cycleMode() {
        mode = (mode + 1) % 3;
        LayoutConfig next = config.copy();
        next.theme = themeFromMode(mode);
        if (!layoutStore.save(next)) {
            Toast.makeText(this, "Gagal menyimpan mode", Toast.LENGTH_SHORT).show();
            mode = modeFromTheme(config.theme);
            return;
        }

        boolean targetDark = chooseDark();
        String message;
        if (mode == MODE_AUTO) {
            message = "Mode otomatis (" + (targetDark ? "gelap" : "terang") + ")";
        } else {
            message = "Mode " + (targetDark ? "gelap" : "terang");
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void updateModeIcon() {
        int icon;
        String description;
        if (mode == MODE_LIGHT) {
            icon = R.drawable.ic_sun;
            description = "Mode terang";
        } else if (mode == MODE_DARK) {
            icon = R.drawable.ic_moon;
            description = "Mode gelap";
        } else {
            icon = R.drawable.ic_auto;
            description = "Mode otomatis";
        }
        modeBtn.setImageResource(icon);
        modeBtn.setContentDescription(description);
        if (palette != null) {
            modeBtn.setImageTintList(ColorStateList.valueOf(palette.accent));
        }
    }

    private void switchTheme(boolean toDark) {
        darkMode = toDark;
        final Palette next = toDark ? Palette.dark(accentColor()) : Palette.light(accentColor());
        content.animate().cancel();
        content.animate()
                .alpha(0.15f)
                .setDuration(150)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        applyPalette(next);
                        content.animate()
                                .alpha(1f)
                                .setDuration(260)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                    }
                })
                .start();
    }

    private void applyPalette(Palette next) {
        palette = next;
        root.setBackgroundColor(next.bg);

        clock.setTextColor(next.clock);
        statusLine.setTextColor(next.textSecondary);
        liveDot.setBackground(oval(next.accent));

        appsHeading.setTextColor(next.textPrimary);
        appsCount.setTextColor(next.textSecondary);
        hints.setTextColor(next.textSecondary);
        headerDivider.setBackgroundColor(next.stroke);

        weatherTemp.setTextColor(next.textPrimary);
        weatherDesc.setTextColor(next.textSecondary);
        weatherIcon.setColors(next.accent, next.textSecondary);

        settingsLabel.setTextColor(next.textPrimary);
        profileLabel.setTextColor(next.textPrimary);
        settingsIcon.setImageTintList(ColorStateList.valueOf(next.accent));
        modeBtn.setImageTintList(ColorStateList.valueOf(next.accent));
        webBtn.setImageTintList(ColorStateList.valueOf(next.accent));
        profileIcon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        avatar.setBackground(avatarDrawable(next));

        applyBackground(weatherChip, box(next.chipBg, next.stroke,
                next.accentSoft, next.accent, 25));
        applyBackground(settingsChip, box(next.chipBg, next.stroke,
                next.accentSoft, next.accent, 23));
        applyBackground(profileChip, box(next.chipBg, next.stroke,
                next.accentSoft, next.accent, 23));
        applyBackground(modeBtn, box(next.chipBg, next.stroke,
                next.accentSoft, next.accent, 23, true));
        applyBackground(webBtn, box(next.chipBg, next.stroke,
                next.accentSoft, next.accent, 23, true));

        for (AppCell cell : cells) {
            applyBackground(cell.view, box(next.surface, next.stroke,
                    next.accentSoft, next.accent, 18));
            cell.label.setTextColor(labelColors(next));
        }

        glowTop.setAlpha(darkMode ? 0.75f : 0.55f);
        glowBottom.setAlpha(darkMode ? 0.55f : 0.4f);
        try {
            glowTop.getBackground().setColorFilter(next.accent, PorterDuff.Mode.SRC_IN);
            glowBottom.getBackground().setColorFilter(next.accent, PorterDuff.Mode.SRC_IN);
        } catch (Exception ignored) {
        }

        bottomFade.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{next.bg & 0x00FFFFFF, next.bg}));
    }

    private void applyBackground(View view, Drawable background) {
        view.setBackground(background);
        background.setState(view.getDrawableState());
    }

    private Drawable box(int fill, int stroke, int focusFill, int focusStroke,
                         int radiusDp) {
        return box(fill, stroke, focusFill, focusStroke, radiusDp, false);
    }

    private Drawable box(int fill, int stroke, int focusFill, int focusStroke,
                         int radiusDp, boolean oval) {
        GradientDrawable normal = rounded(fill, stroke, 1, radiusDp, oval);
        GradientDrawable focused = rounded(focusFill, focusStroke, 2, radiusDp, oval);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, normal);
        return states;
    }

    private GradientDrawable rounded(int fill, int strokeColor, int strokeWidthDp,
                                     int radiusDp, boolean oval) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(oval ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        if (!oval) {
            drawable.setCornerRadius(dp(radiusDp));
        }
        drawable.setColor(fill);
        drawable.setStroke(dp(strokeWidthDp), strokeColor);
        return drawable;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private GradientDrawable avatarDrawable(Palette next) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{next.accent, blend(next.accent, 0xFF071120, 0.55f)});
        drawable.setShape(GradientDrawable.OVAL);
        return drawable;
    }

    private ColorStateList labelColors(Palette next) {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{next.accent, next.textPrimary});
    }

    private void updateClockAndStatus(boolean animate) {
        Calendar now = Calendar.getInstance();
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(now.getTime());
        if (!time.equals(clockText)) {
            clockText = time;
            clock.setText(time);
            if (animate) {
                clock.animate().cancel();
                clock.setAlpha(0.5f);
                clock.setTranslationY(dp(2));
                clock.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(240)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            }
        }

        int hour = now.get(Calendar.HOUR_OF_DAY);
        String greeting;
        if (hour < 11) {
            greeting = "Selamat pagi";
        } else if (hour < 15) {
            greeting = "Selamat siang";
        } else if (hour < 19) {
            greeting = "Selamat sore";
        } else {
            greeting = "Selamat malam";
        }

        String name = config.greetingName == null ? "" : config.greetingName.trim();
        String address = TextUtils.isEmpty(name) ? greeting : greeting + ", " + name;
        String date = new SimpleDateFormat("EEEE, d MMMM", INDONESIAN)
                .format(now.getTime());
        String status = address + " \u00B7 " + date;
        if (!status.equals(statusText)) {
            statusText = status;
            statusLine.setText(status);
        }
    }

    private void loadApps() {
        List<AppEntry> scanned = AppScanner.scan(this);
        apps.clear();
        apps.addAll(AppScanner.order(scanned, config, false));
    }

    private void rebuildGrid(boolean firstBuild) {
        View current = getCurrentFocus();
        boolean gridHadFocus = current != null && isDescendant(appScroll, current);
        String keepPkg = prefs.getString("focus_pkg", "");

        gridContainer.removeAllViews();
        cells.clear();
        cellApps.clear();
        gridRows.clear();

        int index = 0;
        int rowIndex = 0;
        int columns = config.columns;
        while (index < apps.size()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            for (int col = 0; col < columns; col++) {
                if (index < apps.size()) {
                    addCell(row, apps.get(index), rowIndex, col);
                    index++;
                } else {
                    View spacer = new View(this);
                    row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
                }
            }

            LinearLayout.LayoutParams rowLayout = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(config.rowHeight));
            rowLayout.bottomMargin = dp(config.rowGap);
            gridContainer.addView(row, rowLayout);
            gridRows.add(row);
            rowIndex++;
        }

        if (apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Tidak ada aplikasi");
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(28), 0, 0);
            if (palette != null) {
                empty.setTextColor(palette.textSecondary);
            }
            gridContainer.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        appsCount.setText("\u00B7 " + apps.size() + " aplikasi");

        AppCell target = null;
        for (AppCell cell : cells) {
            if (cell.entry.pkg.equals(keepPkg)) {
                target = cell;
                break;
            }
        }
        if (target == null && !cells.isEmpty()) {
            target = cells.get(0);
        }
        lastGridFocus = target != null ? target.view : null;

        wireNavigation();

        if (target != null && (firstBuild || gridHadFocus || current == null)) {
            final View focusView = target.view;
            focusView.post(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing() && focusView.isAttachedToWindow()) {
                        focusView.requestFocus();
                    }
                }
            });
        }
    }

    private void addCell(LinearLayout row, final AppEntry entry,
                         final int rowIndex, final int colIndex) {
        final LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);
        cell.setClickable(true);
        cell.setId(View.generateViewId());
        cell.setContentDescription(entry.label);
        cell.setPadding(dp(6), dp(6), dp(6), dp(6));

        LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        cellParams.setMarginStart(dp(config.cellGap));
        cellParams.setMarginEnd(dp(config.cellGap));
        row.addView(cell, cellParams);

        int labelBlock = config.showLabels ? dp(config.labelSize) + dp(10) : 0;
        int maxIcon = Math.max(dp(22), dp(config.rowHeight) - dp(12) - labelBlock);
        int iconSize = Math.min(dp(config.iconSize), maxIcon);

        final ImageView icon = new ImageView(this);
        icon.setImageDrawable(entry.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cell.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        final TextView label = new TextView(this);
        label.setText(entry.label);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.labelSize);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setDuplicateParentStateEnabled(true);
        label.setVisibility(config.showLabels ? View.VISIBLE : View.GONE);
        if (palette != null) {
            label.setTextColor(labelColors(palette));
            applyBackground(cell, box(palette.surface, palette.stroke,
                    palette.accentSoft, palette.accent, 18));
        }
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(5);
        labelParams.setMarginStart(dp(2));
        labelParams.setMarginEnd(dp(2));
        cell.addView(label, labelParams);

        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus) {
                    lastGridFocus = view;
                    prefs.edit().putString("focus_pkg", entry.pkg).apply();
                    for (View action : topActions) {
                        action.setNextFocusDownId(view.getId());
                    }
                }
                icon.animate().cancel();
                icon.animate()
                        .scaleX(hasFocus ? 1.12f : 1f)
                        .scaleY(hasFocus ? 1.12f : 1f)
                        .setDuration(140)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            }
        });
        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                launch(entry);
            }
        });
        cell.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                openAppInfo(entry);
                return true;
            }
        });

        cells.add(new AppCell(entry, cell, icon, label, rowIndex, colIndex));
        cellApps.put(cell, entry);
    }

    private void wireNavigation() {
        View fallback = lastGridFocus != null
                ? lastGridFocus
                : (cells.isEmpty() ? null : cells.get(0).view);

        List<View> actions = visibleTopActions();
        for (int i = 0; i < actions.size(); i++) {
            View action = actions.get(i);
            action.setNextFocusUpId(action.getId());
            action.setNextFocusDownId(fallback != null ? fallback.getId() : action.getId());
            action.setNextFocusLeftId(i == 0 ? action.getId() : actions.get(i - 1).getId());
            action.setNextFocusRightId(i == actions.size() - 1
                    ? action.getId() : actions.get(i + 1).getId());
        }

        for (AppCell cell : cells) {
            int id = cell.view.getId();
            View up;
            if (cell.row == 0) {
                View preferred = lastTopFocus != null
                        && lastTopFocus.getVisibility() == View.VISIBLE
                        ? lastTopFocus : (actions.isEmpty() ? null : actions.get(0));
                up = preferred != null ? preferred : cell.view;
            } else {
                AppCell above = nearestCellInRow(cell.row - 1, cell.col);
                up = above != null ? above.view : cell.view;
            }
            AppCell below = cell.row < gridRows.size() - 1
                    ? nearestCellInRow(cell.row + 1, cell.col)
                    : null;
            AppCell left = cellAt(cell.row, cell.col - 1);
            AppCell right = cellAt(cell.row, cell.col + 1);

            cell.view.setNextFocusUpId(up.getId());
            cell.view.setNextFocusDownId(below != null ? below.view.getId() : id);
            cell.view.setNextFocusLeftId(left != null ? left.view.getId() : id);
            cell.view.setNextFocusRightId(right != null ? right.view.getId() : id);
        }
    }

    private AppCell nearestCellInRow(int row, int col) {
        AppCell best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (AppCell cell : cells) {
            if (cell.row != row) {
                continue;
            }
            int distance = Math.abs(cell.col - col);
            if (distance < bestDistance) {
                best = cell;
                bestDistance = distance;
            }
        }
        return best;
    }

    private AppCell cellAt(int row, int col) {
        if (col < 0 || col >= config.columns) {
            return null;
        }
        for (AppCell cell : cells) {
            if (cell.row == row && cell.col == col) {
                return cell;
            }
        }
        return null;
    }

    private void loadWallpaper() {
        final File file = layoutStore.getWallpaperFile();
        if (file == null || !file.isFile() || file.length() == 0L) {
            wallpaperGeneration++;
            wallpaperView.setImageDrawable(null);
            wallpaperView.setVisibility(View.GONE);
            wallpaperDim.setVisibility(View.GONE);
            return;
        }

        final int generation = ++wallpaperGeneration;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = decodeWallpaper(file);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != wallpaperGeneration || isFinishing()) {
                            return;
                        }
                        if (bitmap == null) {
                            wallpaperView.setVisibility(View.GONE);
                            wallpaperDim.setVisibility(View.GONE);
                            return;
                        }
                        wallpaperView.setImageBitmap(bitmap);
                        wallpaperView.setVisibility(View.VISIBLE);
                        applyWallpaperDim();
                    }
                });
            }
        });
    }

    private void applyWallpaperDim() {
        boolean visible = wallpaperView.getVisibility() == View.VISIBLE;
        if (!visible || config.wallpaperDim <= 0) {
            wallpaperDim.setVisibility(View.GONE);
            return;
        }
        wallpaperDim.setVisibility(View.VISIBLE);
        wallpaperDim.setAlpha(config.wallpaperDim / 100f);
    }

    private Bitmap decodeWallpaper(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int target = Math.max(960, getResources().getDisplayMetrics().widthPixels);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= target) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            try {
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            } catch (OutOfMemoryError error) {
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            }
        } catch (Exception | OutOfMemoryError exception) {
            return null;
        }
    }

    private void playEntrance() {
        View[] views = {timeBlock, actionRow, headerDivider, sectionHeader, appScroll};
        for (int i = 0; i < views.length; i++) {
            View view = views[i];
            view.setAlpha(0f);
            view.setTranslationY(dp(10));
            view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(380)
                    .setStartDelay(60L + i * 55L)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private void startAmbientAnimations() {
        stopAmbientAnimations();

        ObjectAnimator topX = ObjectAnimator.ofFloat(glowTop, View.TRANSLATION_X,
                -dp(40), dp(70));
        ObjectAnimator topY = ObjectAnimator.ofFloat(glowTop, View.TRANSLATION_Y,
                -dp(70), dp(30));
        ObjectAnimator bottomX = ObjectAnimator.ofFloat(glowBottom, View.TRANSLATION_X,
                dp(60), -dp(40));
        ObjectAnimator bottomY = ObjectAnimator.ofFloat(glowBottom, View.TRANSLATION_Y,
                dp(50), -dp(50));
        ObjectAnimator pulse = ObjectAnimator.ofFloat(liveDot, View.ALPHA, 0.25f, 1f);

        topX.setDuration(26_000L);
        topY.setDuration(31_000L);
        bottomX.setDuration(29_000L);
        bottomY.setDuration(23_000L);
        pulse.setDuration(1_400L);
        pulse.setStartDelay(500L);

        for (ObjectAnimator animator : new ObjectAnimator[]{topX, topY, bottomX, bottomY}) {
            animator.setRepeatCount(ObjectAnimator.INFINITE);
            animator.setRepeatMode(ObjectAnimator.REVERSE);
            animator.setInterpolator(new LinearInterpolator());
            animator.start();
            ambientAnimations.add(animator);
        }
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.setInterpolator(new LinearInterpolator());
        pulse.start();
        ambientAnimations.add(pulse);
    }

    private void stopAmbientAnimations() {
        for (ObjectAnimator animator : ambientAnimations) {
            animator.cancel();
        }
        ambientAnimations.clear();
    }

    private void refreshWeatherIfStale() {
        if (weatherLoading) {
            return;
        }
        long now = System.currentTimeMillis();
        if (cachedWeather != null && cachedWeather.fetchedAt > 0L
                && now >= cachedWeather.fetchedAt
                && now - cachedWeather.fetchedAt < WEATHER_TTL) {
            return;
        }
        if (now - lastWeatherAttempt < WEATHER_RETRY_MS) {
            return;
        }
        fetchWeather(false);
    }

    private void fetchWeather(final boolean manual) {
        if (weatherLoading) {
            if (manual) {
                Toast.makeText(this, "Sedang memperbarui cuaca", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        weatherLoading = true;
        lastWeatherAttempt = System.currentTimeMillis();
        if (manual && cachedWeather == null) {
            weatherDesc.setText("Memuat cuaca\u2026");
            weatherChip.setContentDescription("Memuat cuaca. Tekan OK untuk mencoba lagi");
        }

        worker.execute(new Runnable() {
            @Override
            public void run() {
                WeatherClient.Weather result = null;
                try {
                    result = WeatherClient.fetch();
                } catch (Exception ignored) {
                }
                final WeatherClient.Weather fetched = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        weatherLoading = false;
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (fetched != null) {
                            cachedWeather = fetched;
                            saveWeather(fetched);
                            renderWeather(fetched, true);
                        } else if (cachedWeather == null) {
                            weatherDesc.setText("Cuaca tidak tersedia");
                            weatherChip.setContentDescription(
                                    "Cuaca tidak tersedia. Tekan OK untuk mencoba lagi");
                            if (manual) {
                                Toast.makeText(MainActivity.this,
                                        "Belum bisa memuat cuaca", Toast.LENGTH_SHORT).show();
                            }
                        } else if (manual) {
                            Toast.makeText(MainActivity.this,
                                    "Cuaca belum bisa diperbarui", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    private void renderWeather(WeatherClient.Weather weather, boolean animate) {
        int iconMode;
        String temperature;
        String description;
        if (weather == null) {
            iconMode = WeatherIconView.CLOUD;
            temperature = "--\u00B0";
            description = "Memuat cuaca\u2026";
        } else {
            iconMode = WeatherIconView.modeFor(weather.code, weather.day);
            temperature = Math.round(weather.temperature) + "\u00B0";
            String condition = WeatherIconView.describe(weather.code);
            description = TextUtils.isEmpty(weather.city)
                    ? condition
                    : condition + " \u00B7 " + weather.city;
        }

        weatherIcon.setMode(iconMode);
        weatherTemp.setText(temperature);
        weatherDesc.setText(description);
        weatherChip.setContentDescription("Cuaca " + temperature + " " + description
                + ". Tekan OK untuk memperbarui");

        if (animate) {
            weatherIcon.animate().cancel();
            weatherIcon.setScaleX(0.82f);
            weatherIcon.setScaleY(0.82f);
            weatherIcon.setAlpha(0f);
            weatherIcon.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            weatherIcon.setAlpha(1f);
            weatherIcon.setScaleX(1f);
            weatherIcon.setScaleY(1f);
        }
    }

    private WeatherClient.Weather readCachedWeather() {
        if (!prefs.getBoolean("w_valid", false)) {
            return null;
        }
        int temp10 = prefs.getInt("w_temp10", Integer.MIN_VALUE);
        if (temp10 == Integer.MIN_VALUE) {
            return null;
        }
        return new WeatherClient.Weather(
                temp10 / 10.0,
                prefs.getInt("w_code", 0),
                prefs.getBoolean("w_day", true),
                prefs.getString("w_city", ""),
                prefs.getLong("w_at", 0L));
    }

    private void saveWeather(WeatherClient.Weather weather) {
        prefs.edit()
                .putBoolean("w_valid", true)
                .putInt("w_temp10", (int) Math.round(weather.temperature * 10.0))
                .putInt("w_code", weather.code)
                .putBoolean("w_day", weather.day)
                .putString("w_city", weather.city)
                .putLong("w_at", weather.fetchedAt)
                .apply();
    }

    private void launch(AppEntry entry) {
        if (entry == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(entry.leanback
                    ? Intent.CATEGORY_LEANBACK_LAUNCHER
                    : Intent.CATEGORY_LAUNCHER);
            intent.setComponent(new ComponentName(entry.pkg, entry.activity));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            prefs.edit().putString("focus_pkg", entry.pkg).apply();
            startActivity(intent);
        } catch (Exception exception) {
            Toast.makeText(this, "Tidak bisa membuka " + entry.label,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppInfo(AppEntry entry) {
        if (entry == null) {
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + entry.pkg)));
        } catch (Exception exception) {
            Toast.makeText(this, "Info aplikasi tidak tersedia",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception exception) {
            Toast.makeText(this, "Setelan tidak ditemukan", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAccounts() {
        try {
            startActivity(new Intent(Settings.ACTION_SYNC_SETTINGS));
        } catch (Exception exception) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception fallback) {
                Toast.makeText(this, "Setelan akun tidak tersedia",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openWebPanel() {
        try {
            WebPanel.show(this);
        } catch (Exception exception) {
            Toast.makeText(this, "Panel web tidak tersedia", Toast.LENGTH_SHORT).show();
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void registerPackageReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        registerReceiver(packageReceiver, filter);
    }

    private boolean isDescendant(ViewGroup parent, View child) {
        View cursor = child;
        while (cursor != null) {
            if (cursor == parent) {
                return true;
            }
            ViewParent viewParent = cursor.getParent();
            cursor = viewParent instanceof View ? (View) viewParent : null;
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        layoutStore.addListener(layoutListener);
        hideSystemUi();
        updateClockAndStatus(true);
        maybeSwitchAutoTheme();
        refreshWeatherIfStale();
        startAmbientAnimations();
        if (appliedRevision != layoutStore.getRevision()) {
            applyLayoutConfig();
        } else {
            applyWallpaperDim();
        }
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, TICK_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        stopAmbientAnimations();
        layoutStore.removeListener(layoutListener);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (lastGridFocus != null) {
            final View focusView = lastGridFocus;
            focusView.post(new Runnable() {
                @Override
                public void run() {
                    if (focusView.isAttachedToWindow()) {
                        focusView.requestFocus();
                    }
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopAmbientAnimations();
        layoutStore.removeListener(layoutListener);
        worker.shutdownNow();
        PreviewBridge.detach(this);
        try {
            unregisterReceiver(packageReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Home stays Home.
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            AppEntry focused = focusedApp();
            if (focused != null) {
                openAppInfo(focused);
            } else {
                openWebPanel();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SETTINGS) {
            openSettings();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private AppEntry focusedApp() {
        View focus = getCurrentFocus();
        View cursor = focus;
        while (cursor != null) {
            AppEntry entry = cellApps.get(cursor);
            if (entry != null) {
                return entry;
            }
            ViewParent parent = cursor.getParent();
            cursor = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics()));
    }

    private static int blend(int base, int overlay, float ratio) {
        float inverse = 1f - ratio;
        int red = Math.round(Color.red(base) * inverse + Color.red(overlay) * ratio);
        int green = Math.round(Color.green(base) * inverse + Color.green(overlay) * ratio);
        int blue = Math.round(Color.blue(base) * inverse + Color.blue(overlay) * ratio);
        return Color.rgb(red, green, blue);
    }

    private static int ensureVisibleAccent(int accent, boolean onDark) {
        double luminance = 0.2126 * Color.red(accent) / 255.0
                + 0.7152 * Color.green(accent) / 255.0
                + 0.0722 * Color.blue(accent) / 255.0;
        if (onDark && luminance < 0.25) {
            return blend(accent, 0xFFFFFFFF, 0.35f);
        }
        if (!onDark && luminance > 0.62) {
            return blend(accent, 0xFF071120, 0.35f);
        }
        return accent;
    }
}
