package com.raafi.cyberhome;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class WeatherClient {

    static final class Weather {
        final double temperature;
        final int code;
        final boolean day;
        final String city;
        final long fetchedAt;

        Weather(double temperature, int code, boolean day, String city, long fetchedAt) {
            this.temperature = temperature;
            this.code = code;
            this.day = day;
            this.city = city;
            this.fetchedAt = fetchedAt;
        }
    }

    static final class Location {
        final double latitude;
        final double longitude;
        final String city;

        Location(double latitude, double longitude, String city) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.city = city;
        }
    }

    private WeatherClient() {
    }

    static Weather fetch() throws Exception {
        Location location = locate();
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code,is_day&timezone=auto",
                location.latitude, location.longitude);
        JSONObject root = getJson(url);
        JSONObject current = root.optJSONObject("current");
        if (current == null) {
            throw new IOException("No current weather");
        }
        return new Weather(
                current.getDouble("temperature_2m"),
                current.optInt("weather_code", 0),
                current.optInt("is_day", 1) != 0,
                location.city,
                System.currentTimeMillis());
    }

    static Location locate() throws Exception {
        try {
            JSONObject ip = getJson("https://ipwho.is/");
            if (ip.optBoolean("success", false)) {
                double latitude = ip.optDouble("latitude", Double.NaN);
                double longitude = ip.optDouble("longitude", Double.NaN);
                if (!Double.isNaN(latitude) && !Double.isNaN(longitude)) {
                    return new Location(latitude, longitude, city(ip));
                }
            }
        } catch (Exception ignored) {
        }

        JSONObject ip = getJson("https://ipapi.co/json/");
        double latitude = ip.optDouble("latitude", Double.NaN);
        double longitude = ip.optDouble("longitude", Double.NaN);
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            throw new IOException("No location");
        }
        return new Location(latitude, longitude, city(ip));
    }

    private static String city(JSONObject json) {
        String city = json.optString("city", "").trim();
        if (city.isEmpty()) {
            city = json.optString("region", "").trim();
        }
        return city;
    }

    static JSONObject getJson(String address) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "MinimalHome/1.0 (Android TV)");

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    body.append(buffer, 0, read);
                    if (body.length() > 131072) {
                        break;
                    }
                }
            }
            return new JSONObject(body.toString());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
