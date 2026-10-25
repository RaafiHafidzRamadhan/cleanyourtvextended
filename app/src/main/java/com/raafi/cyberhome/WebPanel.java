package com.raafi.cyberhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The on-TV card that shows the URL and PIN. It is the fallback when the phone is not in the
 * browser yet, and the only place where the PIN can be regenerated without logging in.
 */
final class WebPanel {

    private WebPanel() {
    }

    static void show(final Activity activity) {
        final WebRuntime runtime = WebRuntime.get(activity);
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final int accent = 0xFF5AA2FF;
        final int textPrimary = 0xFFEAF1FA;
        final int textSecondary = 0xFF8B9AB0;
        final int stroke = 0xFF243650;
        final int surface = 0xFF0D1522;

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 38), dp(activity, 32), dp(activity, 38), dp(activity, 30));
        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(surface);
        cardBackground.setCornerRadius(dp(activity, 28));
        cardBackground.setStroke(dp(activity, 1), stroke);
        card.setBackground(cardBackground);

        TextView eyebrow = text(activity, "KENDALI WEB", 11, accent);
        eyebrow.setLetterSpacing(0.16f);
        eyebrow.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        card.addView(eyebrow);

        TextView title = text(activity, "Remote dari HP", 30, textPrimary);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-light",
                android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(activity, 6);
        card.addView(title, titleParams);

        TextView subtitle = text(activity,
                "Buka alamat ini dari HP di Wi-Fi yang sama. Tidak perlu aplikasi tambahan.",
                13, textSecondary);
        subtitle.setLineSpacing(dp(activity, 3), 1f);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(activity, 8);
        card.addView(subtitle, subtitleParams);

        LinearLayout addressBox = new LinearLayout(activity);
        addressBox.setOrientation(LinearLayout.VERTICAL);
        addressBox.setPadding(dp(activity, 20), dp(activity, 14),
                dp(activity, 20), dp(activity, 16));
        GradientDrawable addressBackground = new GradientDrawable();
        addressBackground.setColor(0xFF0A111C);
        addressBackground.setCornerRadius(dp(activity, 16));
        addressBackground.setStroke(dp(activity, 1), 0xFF1D2B3D);
        addressBox.setBackground(addressBackground);
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addressParams.topMargin = dp(activity, 22);
        card.addView(addressBox, addressParams);

        TextView addressLabel = text(activity, "ALAMAT", 9, textSecondary);
        addressLabel.setLetterSpacing(0.18f);
        addressBox.addView(addressLabel);

        final TextView url = text(activity,
                runtime.isRunning() ? runtime.getUrl() : "Server belum aktif",
                25, runtime.isRunning() ? textPrimary : 0xFFFFB86B);
        url.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        urlParams.topMargin = dp(activity, 4);
        addressBox.addView(url, urlParams);

        LinearLayout pinRow = new LinearLayout(activity);
        pinRow.setOrientation(LinearLayout.HORIZONTAL);
        pinRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pinRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pinRowParams.topMargin = dp(activity, 16);
        card.addView(pinRow, pinRowParams);

        LinearLayout pinBlock = new LinearLayout(activity);
        pinBlock.setOrientation(LinearLayout.VERTICAL);
        pinRow.addView(pinBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView pinLabel = text(activity, "PIN", 9, textSecondary);
        pinLabel.setLetterSpacing(0.18f);
        pinBlock.addView(pinLabel);

        final TextView pin = text(activity, runtime.getPin(), 36, accent);
        pin.setLetterSpacing(0.22f);
        pin.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams pinParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pinParams.topMargin = dp(activity, 2);
        pinBlock.addView(pin, pinParams);

        final TextView refreshPin = action(activity, "PIN baru", false, accent, textPrimary,
                stroke, surface);
        refreshPin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String next = runtime.getAuth().regeneratePin();
                pin.setText(next);
                Toast.makeText(activity,
                        "PIN baru dibuat. HP yang sudah masuk harus masuk lagi.",
                        Toast.LENGTH_LONG).show();
            }
        });
        pinRow.addView(refreshPin, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 48)));

        LinearLayout adbRow = new LinearLayout(activity);
        adbRow.setOrientation(LinearLayout.HORIZONTAL);
        adbRow.setGravity(Gravity.CENTER_VERTICAL);
        adbRow.setPadding(dp(activity, 16), dp(activity, 12),
                dp(activity, 16), dp(activity, 12));
        GradientDrawable adbBackground = new GradientDrawable();
        adbBackground.setColor(0xFF0A1420);
        adbBackground.setCornerRadius(dp(activity, 14));
        adbRow.setBackground(adbBackground);
        LinearLayout.LayoutParams adbParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        adbParams.topMargin = dp(activity, 18);
        card.addView(adbRow, adbParams);

        final View dot = new View(activity);
        GradientDrawable dotBackground = new GradientDrawable();
        dotBackground.setShape(GradientDrawable.OVAL);
        dotBackground.setColor(runtime.getAdb().isConnected() ? 0xFF4ADE80 : 0xFFFFB86B);
        dot.setBackground(dotBackground);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(activity, 8),
                dp(activity, 8));
        dotParams.setMarginEnd(dp(activity, 10));
        adbRow.addView(dot, dotParams);

        final TextView adbStatus = text(activity, runtime.getAdb().getMessage(),
                12, textSecondary);
        adbRow.addView(adbStatus, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView probe = action(activity, "Periksa", false, accent, textPrimary,
                stroke, 0xFF0A1420);
        probe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                adbStatus.setText("Memeriksa\u2026");
                probe.setEnabled(false);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final boolean connected = runtime.getAdb().probe();
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (activity.isFinishing()) {
                                    return;
                                }
                                probe.setEnabled(true);
                                adbStatus.setText(runtime.getAdb().getMessage());
                                GradientDrawable background = new GradientDrawable();
                                background.setShape(GradientDrawable.OVAL);
                                background.setColor(connected ? 0xFF4ADE80 : 0xFFFFB86B);
                                dot.setBackground(background);
                                if (!connected) {
                                    Toast.makeText(activity,
                                            "Terima dialog izin ADB di TV, lalu tekan Periksa lagi.",
                                            Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                }, "web-panel-adb").start();
            }
        });
        adbRow.addView(probe, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 40)));

        TextView note = text(activity,
                "Remote tombol, keyboard, dan tangkapan layar memakai ADB lokal. "
                        + "Akses ini hanya untuk jaringan rumah, jangan buka port ini ke internet.",
                11, textSecondary);
        note.setLineSpacing(dp(activity, 2), 1f);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(activity, 16);
        card.addView(note, noteParams);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(activity, 22);
        card.addView(buttons, buttonParams);

        TextView close = action(activity, "Tutup", true, accent, 0xFF061120,
                0xFF5AA2FF, accent);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        buttons.addView(close, new LinearLayout.LayoutParams(dp(activity, 148),
                dp(activity, 50)));
        close.setFocusableInTouchMode(true);
        close.requestFocus();

        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.72f;
            window.setAttributes(attributes);
            window.setLayout(dp(activity, 660), ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
        dialog.show();
        close.post(new Runnable() {
            @Override
            public void run() {
                close.requestFocus();
            }
        });

        if (!runtime.isRunning()) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    runtime.startServer();
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (runtime.isRunning()) {
                                url.setText(runtime.getUrl());
                                url.setTextColor(textPrimary);
                            }
                        }
                    });
                }
            }, "web-panel-server").start();
        }

        if (TextUtils.isEmpty(runtime.getAdb().getMessage())) {
            adbStatus.setText("Belum tersambung");
        }
    }

    private static TextView action(Activity activity, String label, boolean primary,
                                   int accent, int primaryText, int stroke, int fill) {
        TextView view = text(activity, label, 13, primaryText);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        view.setPadding(dp(activity, 18), 0, dp(activity, 18), 0);
        view.setFocusable(true);
        view.setClickable(true);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(primary ? accent : fill);
        normal.setCornerRadius(dp(activity, primary ? 25 : 20));
        if (!primary) {
            normal.setStroke(dp(activity, 1), stroke);
        }
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(primary ? 0xFF7DB6FF : 0xFF172538);
        focused.setCornerRadius(dp(activity, primary ? 25 : 20));
        focused.setStroke(dp(activity, 2), accent);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, normal);
        view.setBackground(states);
        return view;
    }

    private static TextView text(Activity activity, String value, float size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        return view;
    }

    private static int dp(Activity activity, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, activity.getResources().getDisplayMetrics()));
    }
}
