package com.raafi.cyberhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the screensaver configuration. It lives in app-private storage so notes and to-dos never
 * leave the TV and the dream service reads exactly what the web page saved.
 */
final class ScreensaverStore {

    interface Listener {
        void onScreensaverChanged();
    }

    private static final String TAG = "ScreensaverStore";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static ScreensaverStore instance;

    static synchronized ScreensaverStore get(Context context) {
        if (instance == null) {
            instance = new ScreensaverStore(context.getApplicationContext());
        }
        return instance;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final File configFile;
    private ScreensaverConfig config;
    private long revision;

    private ScreensaverStore(Context context) {
        configFile = new File(context.getFilesDir(), "screensaver.json");
        config = read();
        revision = 1L;
    }

    synchronized ScreensaverConfig getConfig() {
        return config;
    }

    synchronized long getRevision() {
        return revision;
    }

    synchronized boolean save(ScreensaverConfig next) {
        if (next == null) {
            return false;
        }
        next.normalize();
        try {
            writeAtomically(configFile, next.toJson().toString().getBytes(UTF_8));
        } catch (IOException exception) {
            Log.w(TAG, "Unable to save screensaver", exception);
            return false;
        }
        config = next;
        revision++;
        notifyChanged();
        return true;
    }

    void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private ScreensaverConfig read() {
        if (!configFile.isFile()) {
            return new ScreensaverConfig();
        }
        try {
            return ScreensaverConfig.fromJson(new JSONObject(new String(readFully(configFile), UTF_8)));
        } catch (Exception exception) {
            Log.w(TAG, "Unable to read screensaver.json, using defaults", exception);
            return new ScreensaverConfig();
        }
    }

    private void notifyChanged() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) {
                    try {
                        listener.onScreensaverChanged();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private static void writeAtomically(File target, byte[] bytes) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create " + parent);
        }
        File temporary = new File(parent, target.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(temporary);
        try {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!temporary.renameTo(target)) {
            if (target.exists() && !target.delete()) {
                temporary.delete();
                throw new IOException("Unable to replace " + target);
            }
            if (!temporary.renameTo(target)) {
                temporary.delete();
                throw new IOException("Unable to move screensaver into place");
            }
        }
    }

    private static byte[] readFully(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            long length = file.length();
            if (length > 512L * 1024L) {
                throw new IOException("File too large");
            }
            byte[] buffer = new byte[(int) length];
            int offset = 0;
            while (offset < buffer.length) {
                int read = in.read(buffer, offset, buffer.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset != buffer.length) {
                byte[] shorter = new byte[offset];
                System.arraycopy(buffer, 0, shorter, 0, offset);
                return shorter;
            }
            return buffer;
        } finally {
            in.close();
        }
    }
}
