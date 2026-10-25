package com.raafi.cyberhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the screensaver shows and the small notes / to-do lists behind it. The shape is kept
 * deliberately flat and bounded: the web page can only choose a known mode, write plain text and
 * flip two visibility switches.
 */
final class ScreensaverConfig {

    static final int VERSION = 1;

    static final String MODE_PRAYER = "prayer";
    static final String MODE_NOTES = "notes";
    static final String MODE_TODOS = "todos";

    static final int MAX_NOTES = 30;
    static final int MAX_TODOS = 60;
    static final int MAX_NOTE_TITLE = 80;
    static final int MAX_NOTE_BODY = 600;
    static final int MAX_TODO_TEXT = 160;

    String mode = MODE_PRAYER;
    boolean showClock = true;
    boolean showDate = true;
    final List<Note> notes = new ArrayList<>();
    final List<Todo> todos = new ArrayList<>();

    static final class Note {
        String id = "";
        String title = "";
        String body = "";
    }

    static final class Todo {
        String id = "";
        String text = "";
        boolean done;
    }

    static ScreensaverConfig fromJson(JSONObject source) {
        ScreensaverConfig config = new ScreensaverConfig();
        if (source == null) {
            return config;
        }
        config.mode = source.optString("mode", config.mode);
        config.showClock = source.optBoolean("show_clock", config.showClock);
        config.showDate = source.optBoolean("show_date", config.showDate);

        JSONArray notes = source.optJSONArray("notes");
        if (notes != null) {
            for (int i = 0; i < notes.length() && config.notes.size() < MAX_NOTES; i++) {
                JSONObject item = notes.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Note note = new Note();
                note.id = item.optString("id", "");
                note.title = item.optString("title", "");
                note.body = item.optString("body", "");
                config.notes.add(note);
            }
        }

        JSONArray todos = source.optJSONArray("todos");
        if (todos != null) {
            for (int i = 0; i < todos.length() && config.todos.size() < MAX_TODOS; i++) {
                JSONObject item = todos.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Todo todo = new Todo();
                todo.id = item.optString("id", "");
                todo.text = item.optString("text", "");
                todo.done = item.optBoolean("done", false);
                config.todos.add(todo);
            }
        }

        config.normalize();
        return config;
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("version", VERSION);
            json.put("mode", mode);
            json.put("show_clock", showClock);
            json.put("show_date", showDate);

            JSONArray noteArray = new JSONArray();
            for (Note note : notes) {
                JSONObject item = new JSONObject();
                item.put("id", note.id);
                item.put("title", note.title);
                item.put("body", note.body);
                noteArray.put(item);
            }
            json.put("notes", noteArray);

            JSONArray todoArray = new JSONArray();
            for (Todo todo : todos) {
                JSONObject item = new JSONObject();
                item.put("id", todo.id);
                item.put("text", todo.text);
                item.put("done", todo.done);
                todoArray.put(item);
            }
            json.put("todos", todoArray);
        } catch (JSONException ignored) {
        }
        return json;
    }

    void normalize() {
        String requested = mode == null ? "" : mode.trim().toLowerCase(Locale.US);
        if (!MODE_NOTES.equals(requested) && !MODE_TODOS.equals(requested)) {
            requested = MODE_PRAYER;
        }
        mode = requested;

        Set<String> ids = new HashSet<>();
        List<Note> cleanNotes = new ArrayList<>();
        for (int i = 0; i < notes.size() && cleanNotes.size() < MAX_NOTES; i++) {
            Note note = notes.get(i);
            if (note == null) {
                continue;
            }
            String title = clean(note.title, MAX_NOTE_TITLE, false);
            String body = clean(note.body, MAX_NOTE_BODY, true);
            if (title.isEmpty() && body.isEmpty()) {
                continue;
            }
            Note cleaned = new Note();
            cleaned.id = uniqueId(note.id, "n", i, ids);
            cleaned.title = title;
            cleaned.body = body;
            cleanNotes.add(cleaned);
        }
        notes.clear();
        notes.addAll(cleanNotes);

        List<Todo> cleanTodos = new ArrayList<>();
        for (int i = 0; i < todos.size() && cleanTodos.size() < MAX_TODOS; i++) {
            Todo todo = todos.get(i);
            if (todo == null) {
                continue;
            }
            String text = clean(todo.text, MAX_TODO_TEXT, false);
            if (text.isEmpty()) {
                continue;
            }
            Todo cleaned = new Todo();
            cleaned.id = uniqueId(todo.id, "t", i, ids);
            cleaned.text = text;
            cleaned.done = todo.done;
            cleanTodos.add(cleaned);
        }
        todos.clear();
        todos.addAll(cleanTodos);
    }

    private static String uniqueId(String raw, String prefix, int index, Set<String> used) {
        String candidate = raw == null ? "" : raw.trim();
        if (!candidate.matches("[A-Za-z0-9_-]{1,48}") || used.contains(candidate)) {
            String base = prefix + Long.toString(System.currentTimeMillis(), 36)
                    + Integer.toString(index, 36);
            candidate = base;
            int suffix = 0;
            while (used.contains(candidate)) {
                suffix++;
                candidate = base + Integer.toString(suffix, 36);
            }
        }
        used.add(candidate);
        return candidate;
    }

    private static String clean(String raw, int maxLength, boolean multiline) {
        if (raw == null) {
            return "";
        }
        String value = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        StringBuilder builder = new StringBuilder(Math.min(value.length(), maxLength));
        for (int i = 0; i < value.length() && builder.length() < maxLength; i++) {
            char character = value.charAt(i);
            if (character == '\n') {
                if (multiline && builder.length() > 0
                        && builder.charAt(builder.length() - 1) != '\n') {
                    builder.append('\n');
                }
            } else if (character == '\t') {
                builder.append(' ');
            } else if (character >= 0x20 && character != 0x7F) {
                builder.append(character);
            }
        }
        return builder.toString().trim();
    }
}
