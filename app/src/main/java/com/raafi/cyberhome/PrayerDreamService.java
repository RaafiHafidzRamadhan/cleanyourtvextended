package com.raafi.cyberhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The TV screensaver. One dream service, three scenes: the prayer schedule, the newest notes or
 * the to-do list. The scene and its content come from ScreensaverStore, which the web remote writes.
 * The class name is kept from the original prayer-only dream so the TV's screensaver setting keeps
 * pointing at this component after an update.
 */
public class PrayerDreamService extends DreamService implements ScreensaverStore.Listener {

    private static final long RETRY_MS = 5 * 60 * 1000L;
    private static final int CARD_HEIGHT_DP = 120;
    private static final int NOTES_ON_SCREEN = 6;
    private static final int TODOS_ON_SCREEN = 8;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView[] cardNames = new TextView[5];
    private final TextView[] cardTimes = new TextView[5];
    private final TextView[] cardTags = new TextView[5];
    private final View[] cards = new View[5];

    private ExecutorService executor;
    private ScreensaverStore store;
    private ScreensaverConfig config;
    private PrayerTimes times;
    private boolean fetching;
    private boolean dreaming;
    private boolean destroyed;
    private boolean uiReady;
    private long lastFetchAttempt;

    private View header;
    private View clockBlock;
    private View sceneHost;
    private View prayerScene;
    private View notesScene;
    private View todoScene;
    private View countdownPill;
    private View prayerStrip;
    private View progress;
    private View footerHint;
    private LinearLayout notesGrid;
    private LinearLayout notesEmpty;
    private LinearLayout todoGrid;
    private LinearLayout todoEmpty;
    private LinearLayout todoSummaryRow;
    private View todoProgress;
    private TextView clock;
    private TextView gregorian;
    private TextView hijri;
    private TextView sceneLabel;
    private TextView scenePill;
    private TextView countdownLabel;
    private TextView countdownValue;
    private TextView todoSummary;
    private TextView todoPercent;

    private int lastNextIndex = -1;
    private int lastMinute = -1;
    private String lastGregorian = "";
    private ValueAnimator pulse;
    private ObjectAnimator floatAnim;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!dreaming) {
                return;
            }
            if (uiReady) {
                updateClock();
                if (isPrayerMode()) {
                    updateNext(false);
                }
            }
            if (isPrayerMode() && (times == null || !times.isForToday()) && !fetching
                    && System.currentTimeMillis() - lastFetchAttempt > RETRY_MS) {
                fetchSchedule();
            }
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        setInteractive(false);
        setFullscreen(true);
        executor = Executors.newSingleThreadExecutor();
        store = ScreensaverStore.get(this);
        store.addListener(this);
        config = store.getConfig();
        times = PrayerTimes.load(this);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (uiReady) {
            return;
        }
        setContentView(R.layout.dream_prayer);

        header = findViewById(R.id.dreamHeader);
        clockBlock = findViewById(R.id.clockBlock);
        sceneHost = findViewById(R.id.sceneHost);
        prayerScene = findViewById(R.id.prayerScene);
        notesScene = findViewById(R.id.notesScene);
        todoScene = findViewById(R.id.todoScene);
        countdownPill = findViewById(R.id.countdownPill);
        prayerStrip = findViewById(R.id.prayerStrip);
        progress = findViewById(R.id.progress);
        footerHint = findViewById(R.id.footerHint);
        notesGrid = (LinearLayout) findViewById(R.id.notesGrid);
        notesEmpty = (LinearLayout) findViewById(R.id.notesEmpty);
        todoGrid = (LinearLayout) findViewById(R.id.todoGrid);
        todoEmpty = (LinearLayout) findViewById(R.id.todoEmpty);
        todoSummaryRow = (LinearLayout) findViewById(R.id.todoSummaryRow);
        todoProgress = findViewById(R.id.todoProgress);
        clock = findViewById(R.id.clock);
        gregorian = findViewById(R.id.gregorian);
        hijri = findViewById(R.id.hijri);
        sceneLabel = findViewById(R.id.sceneLabel);
        scenePill = findViewById(R.id.scenePill);
        countdownLabel = findViewById(R.id.countdownLabel);
        countdownValue = findViewById(R.id.countdownValue);
        todoSummary = findViewById(R.id.todoSummary);
        todoPercent = findViewById(R.id.todoPercent);

        buildCards();
        uiReady = true;
        applyConfig();
        updateClock();
        if (isPrayerMode()) {
            updateNext(false);
        }
        if (dreaming) {
            startAnimations();
        }
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        handler.postDelayed(ticker, 1000L);
        if (uiReady) {
            startAnimations();
        }
        if (isPrayerMode() && (times == null || !times.isForToday())) {
            fetchSchedule();
        }
    }

    @Override
    public void onDreamingStopped() {
        dreaming = false;
        handler.removeCallbacks(ticker);
        stopAnimations();
        super.onDreamingStopped();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (store != null) {
            store.removeListener(this);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public void onScreensaverChanged() {
        if (destroyed) {
            return;
        }
        config = store.getConfig();
        if (!uiReady) {
            return;
        }
        applyConfig();
        updateClock();
        if (isPrayerMode()) {
            updateNext(true);
            if (times == null || !times.isForToday()) {
                fetchSchedule();
            }
        }
    }

    // ------------------------------------------------------------------ scenes

    private boolean isPrayerMode() {
        return ScreensaverConfig.MODE_PRAYER.equals(config.mode);
    }

    private void applyConfig() {
        if (!uiReady) {
            return;
        }
        boolean prayer = isPrayerMode();
        boolean notes = ScreensaverConfig.MODE_NOTES.equals(config.mode);
        boolean todos = ScreensaverConfig.MODE_TODOS.equals(config.mode);

        sceneLabel.setText(prayer ? "JADWAL SHOLAT" : notes ? "CATATAN" : "TO-DO LIST");
        prayerScene.setVisibility(prayer ? View.VISIBLE : View.GONE);
        notesScene.setVisibility(notes ? View.VISIBLE : View.GONE);
        todoScene.setVisibility(todos ? View.VISIBLE : View.GONE);

        clock.setTextSize(prayer ? 104 : 92);
        clock.setVisibility(config.showClock ? View.VISIBLE : View.GONE);
        gregorian.setVisibility(config.showDate ? View.VISIBLE : View.GONE);
        hijri.setVisibility(config.showDate && prayer ? View.VISIBLE : View.GONE);
        clockBlock.setVisibility(config.showClock || config.showDate
                ? View.VISIBLE : View.GONE);

        if (!prayer) {
            stopPulse();
            for (View card : cards) {
                if (card != null) {
                    card.setScaleX(1f);
                    card.setScaleY(1f);
                }
            }
        }

        if (prayer) {
            renderSchedule();
        }
        if (notes) {
            buildNotes();
        }
        if (todos) {
            buildTodos();
        }
        updateHeaderPill();
    }

    private void buildNotes() {
        notesGrid.removeAllViews();
        int total = config.notes.size();
        int count = Math.min(total, NOTES_ON_SCREEN);
        notesEmpty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        notesGrid.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        if (count == 0) {
            return;
        }

        boolean roomy = count <= 2;
        int rows = (count + 1) / 2;
        for (int row = 0; row < rows; row++) {
            LinearLayout rowView = new LinearLayout(this);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            rowView.setClipChildren(false);
            LinearLayout.LayoutParams rowParams =
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            if (row > 0) {
                rowParams.topMargin = dp(10);
            }

            int first = row * 2;
            int inRow = Math.min(2, count - first);
            for (int column = 0; column < inRow; column++) {
                LinearLayout noteCard = buildNoteCard(config.notes.get(first + column), roomy);
                LinearLayout.LayoutParams cardParams =
                        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                if (column > 0) {
                    cardParams.setMarginStart(dp(10));
                }
                rowView.addView(noteCard, cardParams);
            }
            notesGrid.addView(rowView, rowParams);
        }
    }

    private LinearLayout buildNoteCard(ScreensaverConfig.Note note, boolean roomy) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.TOP);
        card.setBackgroundResource(R.drawable.bg_dream_note_card);
        card.setPadding(dp(roomy ? 22 : 16), dp(roomy ? 20 : 14),
                dp(roomy ? 22 : 16), dp(12));

        TextView title = new TextView(this);
        title.setText(note.title.isEmpty() ? "Tanpa judul" : note.title);
        title.setTextSize(roomy ? 24 : 16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(0xFFF7FAFF);
        title.setIncludeFontPadding(false);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title, wrap());

        TextView body = new TextView(this);
        body.setText(note.body.isEmpty() ? "Catatan tanpa isi" : note.body);
        body.setTextSize(roomy ? 14 : 12);
        body.setTextColor(note.body.isEmpty() ? 0xFF8FB4DF : 0xFFD6E6FF);
        body.setIncludeFontPadding(false);
        body.setMaxLines(roomy ? 5 : 3);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams bodyParams = wrap();
        bodyParams.topMargin = dp(roomy ? 10 : 6);
        card.addView(body, bodyParams);

        return card;
    }

    private void buildTodos() {
        todoGrid.removeAllViews();
        int total = config.todos.size();
        int done = 0;
        List<ScreensaverConfig.Todo> ordered = new ArrayList<>();
        for (ScreensaverConfig.Todo todo : config.todos) {
            if (todo.done) {
                done++;
            } else {
                ordered.add(todo);
            }
        }
        for (ScreensaverConfig.Todo todo : config.todos) {
            if (todo.done) {
                ordered.add(todo);
            }
        }

        int count = Math.min(total, TODOS_ON_SCREEN);
        boolean empty = count == 0;
        todoEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        todoSummaryRow.setVisibility(empty ? View.GONE : View.VISIBLE);
        todoProgress.setVisibility(empty ? View.GONE : View.VISIBLE);
        todoGrid.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            return;
        }

        int remaining = total - done;
        todoSummary.setText(remaining == 0
                ? "SEMUA TUGAS SELESAI"
                : String.format(Locale.US, "%d TUGAS TERSISA", remaining));
        todoPercent.setText(String.format(Locale.US, "%d / %d SELESAI", done, total));
        ((PrayerProgressView) todoProgress).setFraction(done / (float) total);

        int columns = count <= 4 ? 1 : 2;
        int rows = (count + columns - 1) / columns;
        LinearLayout columnsView = new LinearLayout(this);
        columnsView.setOrientation(LinearLayout.HORIZONTAL);

        for (int column = 0; column < columns; column++) {
            LinearLayout columnView = new LinearLayout(this);
            columnView.setOrientation(LinearLayout.VERTICAL);
            for (int row = 0; row < rows; row++) {
                int index = column * rows + row;
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
                if (row > 0) {
                    rowParams.topMargin = dp(8);
                }
                if (index < count) {
                    columnView.addView(buildTodoRow(ordered.get(index)), rowParams);
                } else {
                    columnView.addView(new View(this), rowParams);
                }
            }
            LinearLayout.LayoutParams columnParams =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (column > 0) {
                columnParams.setMarginStart(dp(12));
            }
            columnsView.addView(columnView, columnParams);
        }

        todoGrid.addView(columnsView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
    }

    private LinearLayout buildTodoRow(ScreensaverConfig.Todo todo) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_dream_todo_row);
        row.setPadding(dp(12), 0, dp(12), 0);

        TextView check = new TextView(this);
        check.setGravity(Gravity.CENTER);
        check.setIncludeFontPadding(false);
        check.setTextSize(12);
        check.setTextColor(Color.WHITE);
        if (todo.done) {
            check.setBackgroundResource(R.drawable.bg_dream_check_done);
            check.setText("✓");
        } else {
            check.setBackgroundResource(R.drawable.bg_dream_check);
        }
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        checkParams.setMarginEnd(dp(10));
        row.addView(check, checkParams);

        TextView label = new TextView(this);
        label.setText(todo.text);
        label.setTextSize(14);
        label.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        label.setIncludeFontPadding(false);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        if (todo.done) {
            label.setTextColor(0xFF8FB4DF);
            label.setPaintFlags(label.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        } else {
            label.setTextColor(0xFFEAF3FF);
        }
        row.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setAlpha(todo.done ? 0.72f : 1f);
        return row;
    }

    private void updateHeaderPill() {
        if (isPrayerMode()) {
            String city = times == null || times.city == null ? "" : times.city.trim();
            scenePill.setText(city.isEmpty() ? "LOKASI ANDA" : city.toUpperCase(Locale.US));
            return;
        }
        if (ScreensaverConfig.MODE_NOTES.equals(config.mode)) {
            int count = config.notes.size();
            scenePill.setText(count == 0
                    ? "TANPA CATATAN"
                    : String.format(Locale.US, "%d CATATAN", count));
            return;
        }
        int total = config.todos.size();
        int done = 0;
        for (ScreensaverConfig.Todo todo : config.todos) {
            if (todo.done) {
                done++;
            }
        }
        if (total == 0) {
            scenePill.setText("TANPA TUGAS");
        } else if (done == total) {
            scenePill.setText("SEMUA SELESAI");
        } else {
            scenePill.setText(String.format(Locale.US, "%d TERSISA", total - done));
        }
    }

    // ----------------------------------------------------------------- prayer

    private void buildCards() {
        LinearLayout strip = (LinearLayout) prayerStrip;
        for (int i = 0; i < 5; i++) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(8), dp(13), dp(8), dp(10));
            card.setBackgroundResource(R.drawable.bg_prayer_card);
            card.setClipChildren(false);
            card.setClipToPadding(false);

            TextView name = new TextView(this);
            name.setText(PrayerTimes.NAMES[i].toUpperCase(Locale.US));
            name.setTextSize(12);
            name.setLetterSpacing(0.18f);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setTextColor(0xFFA9C6EA);
            name.setGravity(Gravity.CENTER);
            name.setIncludeFontPadding(false);
            card.addView(name, wrap());

            TextView time = new TextView(this);
            time.setText("--:--");
            time.setTextSize(30);
            time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            time.setTextColor(0xFFF2F7FF);
            time.setGravity(Gravity.CENTER);
            time.setIncludeFontPadding(false);
            LinearLayout.LayoutParams timeParams = wrap();
            timeParams.topMargin = dp(3);
            card.addView(time, timeParams);

            TextView tag = new TextView(this);
            tag.setText("BESOK");
            tag.setTextSize(8);
            tag.setLetterSpacing(0.16f);
            tag.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tag.setTextColor(0xFFFFFFFF);
            tag.setGravity(Gravity.CENTER);
            tag.setIncludeFontPadding(false);
            tag.setBackgroundResource(R.drawable.bg_dream_tag);
            tag.setPadding(dp(7), dp(2), dp(7), dp(3));
            tag.setVisibility(View.INVISIBLE);
            LinearLayout.LayoutParams tagParams = wrap();
            tagParams.topMargin = dp(5);
            card.addView(tag, tagParams);

            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(0, dp(CARD_HEIGHT_DP), 1f);
            params.setMarginStart(dp(6));
            params.setMarginEnd(dp(6));
            strip.addView(card, params);

            cards[i] = card;
            cardNames[i] = name;
            cardTimes[i] = time;
            cardTags[i] = tag;
        }
    }

    private void renderSchedule() {
        if (times == null) {
            for (int i = 0; i < 5; i++) {
                cardTimes[i].setText("--:--");
            }
            hijri.setText("");
            return;
        }

        for (int i = 0; i < 5; i++) {
            cardTimes[i].setText(PrayerTimes.formatTime(times.minutes[i]));
        }
        hijri.setText(times.isForToday() ? times.hijri : "Jadwal sholat belum diperbarui");
    }

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        if (minute != lastMinute) {
            clock.setText(String.format(Locale.US, "%02d:%02d",
                    minute / 60, minute % 60));
            if (lastMinute != -1) {
                clock.animate().cancel();
                clock.setAlpha(0.35f);
                clock.animate().alpha(1f).setDuration(300).start();
            }
            lastMinute = minute;
        }

        String current = new SimpleDateFormat("EEEE, d MMMM yyyy",
                new Locale("id", "ID")).format(now.getTime());
        if (!current.equals(lastGregorian)) {
            gregorian.setText(current);
            lastGregorian = current;
        }
    }

    private void updateNext(boolean force) {
        if (!isPrayerMode()) {
            return;
        }
        if (times == null) {
            showMessage("JADWAL SHOLAT", "Memuat jadwal…", 20);
            applyHighlight(-1, force);
            applyTomorrowTag(false);
            setProgress(0f);
            return;
        }
        if (!times.isForToday()) {
            showMessage("JADWAL TERSIMPAN", "Menunggu koneksi internet", 18);
            applyHighlight(-1, force);
            applyTomorrowTag(false);
            setProgress(0f);
            hijri.setText("Jadwal sholat belum diperbarui");
            return;
        }

        Calendar now = Calendar.getInstance();
        int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int nowSeconds = nowMinutes * 60 + now.get(Calendar.SECOND);
        int index = times.nextIndex(nowMinutes);
        int nextAbsolute = times.nextAbsoluteMinutes(nowMinutes);
        int previousAbsolute = times.previousAbsoluteMinutes(nowMinutes);

        applyHighlight(index, force);
        boolean tomorrow = nowMinutes >= times.minutes[4];
        applyTomorrowTag(tomorrow);
        String subuh = PrayerTimes.formatTime(
                tomorrow && times.hasTomorrowFajr() ? times.tomorrowFajr : times.minutes[0]);
        if (!subuh.contentEquals(cardTimes[0].getText())) {
            cardTimes[0].setText(subuh);
        }

        long remainingSeconds = nextAbsolute * 60L - nowSeconds;
        if (remainingSeconds < 0) {
            remainingSeconds = 0;
        }
        long hours = remainingSeconds / 3600;
        long minutes = (remainingSeconds % 3600) / 60;
        long seconds = remainingSeconds % 60;
        countdownLabel.setText("MENUJU " + PrayerTimes.NAMES[index].toUpperCase(Locale.US));
        if (countdownValue.getTextSize() != sp(26)) {
            countdownValue.setTextSize(26);
        }
        countdownValue.setText(String.format(Locale.US, "%02d : %02d : %02d",
                hours, minutes, seconds));

        float span = (nextAbsolute - previousAbsolute) * 60f;
        float elapsed = nowSeconds - previousAbsolute * 60f;
        setProgress(span <= 0 ? 0f : elapsed / span);
    }

    private void showMessage(String label, String value, float valueSize) {
        countdownLabel.setText(label);
        if (countdownValue.getTextSize() != sp(valueSize)) {
            countdownValue.setTextSize(valueSize);
        }
        if (!value.contentEquals(countdownValue.getText())) {
            countdownValue.setText(value);
        }
    }

    private void applyHighlight(int index, boolean force) {
        if (!force && index == lastNextIndex) {
            return;
        }
        lastNextIndex = index;

        for (int i = 0; i < 5; i++) {
            boolean active = i == index;
            cards[i].setBackgroundResource(active
                    ? R.drawable.bg_prayer_card_next
                    : R.drawable.bg_prayer_card);
            cardNames[i].setTextColor(active ? 0xFFFFFFFF : 0xFFA9C6EA);
            cardTimes[i].setTextColor(active ? 0xFFFFFFFF : 0xFFF2F7FF);
            cards[i].setScaleX(1f);
            cards[i].setScaleY(1f);
        }

        if (index < 0) {
            stopPulse();
            return;
        }
        startPulse(index);
    }

    private void applyTomorrowTag(boolean visible) {
        cardTags[0].setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    private void setProgress(float value) {
        ((PrayerProgressView) progress).setFraction(value);
    }

    private void startPulse(int index) {
        stopPulse();
        final View target = cards[index];
        ValueAnimator animator = ValueAnimator.ofFloat(1f, 1.024f);
        animator.setDuration(1800L);
        animator.setStartDelay(300L);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float value = (float) animation.getAnimatedValue();
                target.setScaleX(value);
                target.setScaleY(value);
            }
        });
        pulse = animator;
        if (dreaming) {
            pulse.start();
        }
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
    }

    private void startAnimations() {
        playEntrance();
        startFloat();
        updateClock();
        if (isPrayerMode()) {
            updateNext(true);
        }
    }

    private void playEntrance() {
        View[] groups = {header, clockBlock, sceneHost};
        for (int i = 0; i < groups.length; i++) {
            View view = groups[i];
            view.setAlpha(0f);
            view.setTranslationY(dp(16));
            view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(60L + i * 90L)
                    .setDuration(520L)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }

        footerHint.setAlpha(0f);
        footerHint.setTranslationY(dp(10));
        footerHint.animate()
                .alpha(0.5f)
                .translationY(0f)
                .setStartDelay(360L)
                .setDuration(520L)
                .start();
    }

    private void startFloat() {
        if (floatAnim != null) {
            floatAnim.cancel();
        }
        floatAnim = ObjectAnimator.ofFloat(clockBlock, View.TRANSLATION_Y, 0f, dp(8));
        floatAnim.setDuration(9000L);
        floatAnim.setStartDelay(1200L);
        floatAnim.setRepeatCount(ValueAnimator.INFINITE);
        floatAnim.setRepeatMode(ValueAnimator.REVERSE);
        floatAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        floatAnim.start();
    }

    private void stopAnimations() {
        if (floatAnim != null) {
            floatAnim.cancel();
            floatAnim = null;
        }
        stopPulse();
        for (View card : cards) {
            if (card != null) {
                card.animate().cancel();
                card.setScaleX(1f);
                card.setScaleY(1f);
            }
        }
    }

    private void fetchSchedule() {
        if (fetching || executor == null || executor.isShutdown()) {
            return;
        }
        fetching = true;
        lastFetchAttempt = System.currentTimeMillis();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final PrayerTimes fetched = PrayerTimes.fetch(PrayerDreamService.this);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            fetching = false;
                            times = fetched;
                            if (uiReady) {
                                updateHeaderPill();
                                if (isPrayerMode()) {
                                    renderSchedule();
                                    updateClock();
                                    updateNext(true);
                                }
                            }
                        }
                    });
                } catch (Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            fetching = false;
                            if (!destroyed && uiReady && isPrayerMode()) {
                                updateNext(false);
                            }
                        }
                    });
                }
            }
        });
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
