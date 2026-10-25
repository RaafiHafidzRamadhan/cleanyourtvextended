package com.raafi.cyberhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PIN login plus short-lived signed-in sessions. Sessions live in memory only: a restart of the
 * TV or the app asks for the PIN again, while the PIN itself is remembered on the TV.
 */
final class AuthManager {

    static final String PREFS = "web_remote";
    static final String COOKIE = "ch_session";
    static final long SESSION_TTL_MS = 7L * 24L * 60L * 60L * 1000L;

    private static final int MAX_SESSIONS = 16;
    private static final int MAX_FAILURES = 8;
    private static final long FAILURE_WINDOW_MS = 5L * 60L * 1000L;

    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> sessions = new LinkedHashMap<>();
    private final Map<String, Deque<Long>> failures = new HashMap<>();

    AuthManager(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ensurePin();
    }

    synchronized String getPin() {
        return ensurePin();
    }

    synchronized String regeneratePin() {
        String pin = generatePin();
        prefs.edit().putString("pin", pin).apply();
        invalidateAllSessionsLocked();
        return pin;
    }

    synchronized boolean checkPin(String candidate) {
        if (candidate == null) {
            return false;
        }
        String pin = ensurePin();
        byte[] expected = pin.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] actual = candidate.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    synchronized boolean changePin(String currentPin, String nextPin) {
        if (!checkPin(currentPin) || !isValidPin(nextPin)) {
            return false;
        }
        prefs.edit().putString("pin", nextPin.trim()).apply();
        invalidateAllSessionsLocked();
        return true;
    }

    static boolean isValidPin(String value) {
        return value != null && value.trim().matches("^\\d{6}$");
    }

    synchronized String createSession() {
        purgeSessions();
        byte[] token = new byte[32];
        random.nextBytes(token);
        String value = Base64.encodeToString(token,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        while (sessions.containsKey(value)) {
            random.nextBytes(token);
            value = Base64.encodeToString(token,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        }
        sessions.put(value, System.currentTimeMillis() + SESSION_TTL_MS);
        while (sessions.size() > MAX_SESSIONS) {
            Iterator<String> iterator = sessions.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        return value;
    }

    synchronized boolean isValidSession(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        purgeSessions();
        Long expiresAt = sessions.get(token);
        return expiresAt != null && expiresAt > System.currentTimeMillis();
    }

    synchronized void invalidateSession(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    synchronized void invalidateAllSessions() {
        invalidateAllSessionsLocked();
    }

    synchronized boolean allowAttempt(String address) {
        pruneFailures(address);
        Deque<Long> list = failures.get(address);
        return list == null || list.size() < MAX_FAILURES;
    }

    synchronized void recordFailure(String address) {
        if (address == null) {
            address = "unknown";
        }
        Deque<Long> list = failures.get(address);
        if (list == null) {
            list = new ArrayDeque<>();
            failures.put(address, list);
        }
        list.addLast(System.currentTimeMillis());
        pruneFailures(address);
    }

    synchronized void clearFailures(String address) {
        if (address != null) {
            failures.remove(address);
        }
    }

    private void invalidateAllSessionsLocked() {
        sessions.clear();
    }

    private String ensurePin() {
        String pin = prefs.getString("pin", "");
        if (!isValidPin(pin)) {
            pin = generatePin();
            prefs.edit().putString("pin", pin).apply();
        }
        return pin;
    }

    private String generatePin() {
        return String.format(Locale.US, "%06d", 100000 + random.nextInt(900000));
    }

    private void purgeSessions() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= now) {
                iterator.remove();
            }
        }
    }

    private void pruneFailures(String address) {
        Deque<Long> list = failures.get(address);
        if (list == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - FAILURE_WINDOW_MS;
        while (!list.isEmpty() && list.peekFirst() < cutoff) {
            list.removeFirst();
        }
        if (list.isEmpty()) {
            failures.remove(address);
        }
    }

    List<String> activeSessions() {
        synchronized (this) {
            purgeSessions();
            return new ArrayList<>(sessions.keySet());
        }
    }
}
