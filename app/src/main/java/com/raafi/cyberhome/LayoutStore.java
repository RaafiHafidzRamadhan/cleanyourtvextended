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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single source of truth for the Home layout. Persisted as JSON in app-private storage so the
 * launcher and the web server always read the same values.
 */
final class LayoutStore {

    interface Listener {
        void onLayoutChanged();

        void onWallpaperChanged();
    }

    private static final String TAG = "LayoutStore";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static LayoutStore instance;

    static synchronized LayoutStore get(Context context) {
        if (instance == null) {
            instance = new LayoutStore(context.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final File layoutFile;
    private final File wallpaperFile;
    private LayoutConfig config;
    private long revision;
    private long wallpaperRevision;

    private LayoutStore(Context context) {
        this.context = context;
        File dir = context.getFilesDir();
        layoutFile = new File(dir, "layout.json");
        wallpaperFile = new File(dir, "wallpaper.img");
        config = read();
        revision = 1L;
    }

    synchronized LayoutConfig getConfig() {
        return config;
    }

    synchronized long getRevision() {
        return revision;
    }

    synchronized long getWallpaperRevision() {
        return wallpaperRevision;
    }

    synchronized boolean save(LayoutConfig next) {
        if (next == null) {
            return false;
        }
        next.normalize();
        try {
            writeAtomically(layoutFile, next.toJson().toString().getBytes(UTF_8));
        } catch (IOException exception) {
            Log.w(TAG, "Unable to save layout", exception);
            return false;
        }
        config = next;
        revision++;
        notifyLayout();
        return true;
    }

    synchronized boolean reset() {
        if (layoutFile.exists() && !layoutFile.delete()) {
            return false;
        }
        config = new LayoutConfig();
        revision++;
        notifyLayout();
        return true;
    }

    synchronized File getWallpaperFile() {
        return wallpaperFile;
    }

    synchronized boolean hasWallpaper() {
        return wallpaperFile.isFile() && wallpaperFile.length() > 0;
    }

    boolean saveWallpaper(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        try {
            writeAtomically(wallpaperFile, bytes);
        } catch (IOException exception) {
            Log.w(TAG, "Unable to save wallpaper", exception);
            return false;
        }
        synchronized (this) {
            wallpaperRevision++;
        }
        notifyWallpaper();
        return true;
    }

    /**
     * The wallpaper is the only user file kept by the app; it stays in internal storage so no
     * storage permission is ever needed.
     */
    boolean clearWallpaper() {
        boolean deleted;
        synchronized (this) {
            deleted = !wallpaperFile.exists() || wallpaperFile.delete();
            if (deleted) {
                wallpaperRevision++;
            }
        }
        if (deleted) {
            notifyWallpaper();
        }
        return deleted;
    }

    void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private LayoutConfig read() {
        if (!layoutFile.isFile()) {
            return new LayoutConfig();
        }
        try {
            byte[] bytes = readFully(layoutFile);
            return LayoutConfig.fromJson(new JSONObject(new String(bytes, UTF_8)));
        } catch (Exception exception) {
            Log.w(TAG, "Unable to read layout.json, using defaults", exception);
            return new LayoutConfig();
        }
    }

    private void notifyLayout() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) {
                    try {
                        listener.onLayoutChanged();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private void notifyWallpaper() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) {
                    try {
                        listener.onWallpaperChanged();
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
                throw new IOException("Unable to move layout into place");
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

    List<Listener> listenersSnapshot() {
        return new CopyOnWriteArrayList<>(listeners);
    }
}
