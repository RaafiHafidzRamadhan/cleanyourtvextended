package com.raafi.cyberhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

final class PrayerTimes {

    static final String[] NAMES = {"Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya"};

    private static final String[] PREF_KEYS = {
            "fajr", "dhuhr", "asr", "maghrib", "isha"
    };
    private static final String[] API_KEYS = {
            "Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"
    };
    private static final String[] HIJRI_MONTHS = {
            "Muharram", "Safar", "Rabiul Awal", "Rabiul Akhir", "Jumadil Awal",
            "Jumadil Akhir", "Rajab", "Sya'ban", "Ramadhan", "Syawal",
            "Dzulqa'dah", "Dzulhijjah"
    };
    private static final String PREFS = "prayer";

    final int[] minutes;
    final int tomorrowFajr;
    final String hijri;
    final String city;
    final String dateKey;
    final long fetchedAt;

    private PrayerTimes(int[] minutes, int tomorrowFajr, String hijri, String city,
                        String dateKey, long fetchedAt) {
        this.minutes = minutes;
        this.tomorrowFajr = tomorrowFajr;
        this.hijri = hijri;
        this.city = city;
        this.dateKey = dateKey;
        this.fetchedAt = fetchedAt;
    }

    static PrayerTimes load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String dateKey = prefs.getString("date", null);
        if (dateKey == null) {
            return null;
        }
        int[] minutes = new int[5];
        for (int i = 0; i < 5; i++) {
            minutes[i] = prefs.getInt(PREF_KEYS[i], -1);
            if (minutes[i] < 0) {
                return null;
            }
        }
        return new PrayerTimes(
                minutes,
                prefs.getInt("tomorrow_fajr", minutes[0]),
                prefs.getString("hijri", ""),
                prefs.getString("city", ""),
                dateKey,
                prefs.getLong("fetched", 0));
    }

    static PrayerTimes fetch(Context context) throws Exception {
        WeatherClient.Location location = WeatherClient.locate();
        String today = dateKey(0);
        JSONObject data = WeatherClient.getJson(timingsUrl(location, today)).getJSONObject("data");
        JSONObject timings = data.getJSONObject("timings");

        int[] minutes = new int[5];
        for (int i = 0; i < 5; i++) {
            minutes[i] = parseTime(timings.optString(API_KEYS[i]));
        }

        int tomorrowFajr = minutes[0];
        try {
            JSONObject tomorrow = WeatherClient
                    .getJson(timingsUrl(location, dateKey(1)))
                    .getJSONObject("data")
                    .getJSONObject("timings");
            tomorrowFajr = parseTime(tomorrow.optString("Fajr"));
        } catch (Exception ignored) {
        }

        String hijri = hijriText(data.getJSONObject("date").getJSONObject("hijri"));
        PrayerTimes result = new PrayerTimes(minutes, tomorrowFajr, hijri,
                location.city, today, System.currentTimeMillis());
        result.save(context);
        return result;
    }

    boolean isForToday() {
        return dateKey.equals(dateKey(0));
    }

    boolean hasTomorrowFajr() {
        return tomorrowFajr > 0;
    }

    int nextIndex(int nowMinutes) {
        for (int i = 0; i < 5; i++) {
            if (minutes[i] > nowMinutes) {
                return i;
            }
        }
        return 0;
    }

    int nextAbsoluteMinutes(int nowMinutes) {
        int index = nextIndex(nowMinutes);
        if (index == 0 && nowMinutes >= minutes[4]) {
            return 1440 + tomorrowFajr;
        }
        return minutes[index];
    }

    int previousAbsoluteMinutes(int nowMinutes) {
        if (nowMinutes >= minutes[4]) {
            return minutes[4];
        }
        if (nowMinutes < minutes[0]) {
            return minutes[4] - 1440;
        }
        int previous = minutes[0];
        for (int i = 1; i < 5; i++) {
            if (minutes[i] <= nowMinutes) {
                previous = minutes[i];
            } else {
                break;
            }
        }
        return previous;
    }

    static String formatTime(int minutes) {
        return String.format(Locale.US, "%02d:%02d", (minutes / 60) % 24, minutes % 60);
    }

    private void save(Context context) {
        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putString("date", dateKey)
                .putString("city", city)
                .putString("hijri", hijri)
                .putInt("tomorrow_fajr", tomorrowFajr)
                .putLong("fetched", fetchedAt);
        for (int i = 0; i < 5; i++) {
            editor.putInt(PREF_KEYS[i], minutes[i]);
        }
        editor.apply();
    }

    private static String timingsUrl(WeatherClient.Location location, String date) {
        return String.format(Locale.US,
                "https://api.aladhan.com/v1/timings/%s?latitude=%.4f&longitude=%.4f&method=20",
                date, location.latitude, location.longitude);
    }

    private static String dateKey(int plusDays) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, plusDays);
        return new SimpleDateFormat("dd-MM-yyyy", Locale.US).format(calendar.getTime());
    }

    private static int parseTime(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() < 5 || value.charAt(2) != ':') {
            return 0;
        }
        try {
            int hour = Integer.parseInt(value.substring(0, 2));
            int minute = Integer.parseInt(value.substring(3, 5));
            return hour * 60 + minute;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String hijriText(JSONObject hijri) {
        JSONObject month = hijri.optJSONObject("month");
        int number = month == null ? 0 : month.optInt("number", 0);
        String name;
        if (number >= 1 && number <= HIJRI_MONTHS.length) {
            name = HIJRI_MONTHS[number - 1];
        } else {
            name = month == null ? "" : month.optString("en", "");
        }
        return String.format(Locale.US, "%s %s %s H",
                hijri.optString("day", "-"), name, hijri.optString("year", "-")).trim();
    }
}
