package com.raafi.cyberhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;
import dadb.SyncResult;

/**
 * Talks to the TV's own adbd over loopback. ADB is what makes the browser a full remote instead
 * of a view of the Home screen: D-pad keys, app screenshots and text input all go through it.
 *
 * The RSA key is generated once and kept in app-private storage. The TV shows its normal
 * "Allow USB debugging?" prompt the first time; the person accepts it with the remote.
 */
final class AdbBridge {

    private static final String TAG = "AdbBridge";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;

    private final Context context;
    private final Object ioLock = new Object();

    private Dadb dadb;
    private AdbKeyPair keyPair;
    private volatile boolean connected;
    private volatile String message = "Belum tersambung";
    private volatile long lastProbeAt;

    AdbBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean isConnected() {
        return connected;
    }

    String getMessage() {
        return message;
    }

    long getLastProbeAt() {
        return lastProbeAt;
    }

    boolean probe() {
        try {
            String output = runShell("echo cyberhome-ok");
            boolean ok = output != null && output.contains("cyberhome-ok");
            connected = ok;
            message = ok ? "ADB tersambung" : "ADB tidak merespons";
        } catch (Exception exception) {
            markFailure(exception);
        }
        lastProbeAt = System.currentTimeMillis();
        return connected;
    }

    void key(int keycode) throws Exception {
        runShell("input keyevent " + keycode);
        markConnected();
    }

    void text(String value) throws Exception {
        String command = buildTextCommand(value);
        runShell(command);
        markConnected();
    }

    byte[] screenshotJpeg() throws Exception {
        synchronized (ioLock) {
            boolean existed = dadb != null;
            try {
                return screenshotOnce();
            } catch (Exception first) {
                closeLocked();
                if (!existed) {
                    markFailure(first);
                    throw first;
                }
                try {
                    return screenshotOnce();
                } catch (Exception second) {
                    markFailure(second);
                    throw second;
                }
            }
        }
    }

    private byte[] screenshotOnce() throws Exception {
        Dadb connection = connectionLocked();
        String remote = "/data/local/tmp/cyberhome-" + System.nanoTime() + ".png";
        try {
            AdbShellResponse capture = connection.shell("screencap -p " + remote);
            if (capture.getExitCode() != 0) {
                throw new IOException("screencap gagal: " + capture.getAllOutput().trim());
            }
            File local = new File(context.getCacheDir(), "web-screenshot.png");
            SyncResult result = connection.pull(local, remote);
            if (!(result instanceof SyncResult.Success)) {
                throw new IOException("Gagal mengambil tangkapan layar");
            }
            markConnected();
            return toJpeg(local);
        } finally {
            try {
                connection.shell("rm -f " + remote);
            } catch (Exception ignored) {
            }
        }
    }

    private byte[] toJpeg(File file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= 1280) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) {
            throw new IOException("Tangkapan layar tidak bisa dibaca");
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output);
            return output.toByteArray();
        } finally {
            bitmap.recycle();
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private String runShell(String command) throws Exception {
        synchronized (ioLock) {
            boolean existed = dadb != null;
            try {
                return shellOnce(command);
            } catch (Exception first) {
                closeLocked();
                if (!existed) {
                    markFailure(first);
                    throw first;
                }
                try {
                    return shellOnce(command);
                } catch (Exception second) {
                    markFailure(second);
                    throw second;
                }
            }
        }
    }

    private String shellOnce(String command) throws Exception {
        Dadb connection = connectionLocked();
        AdbShellResponse response = connection.shell(command);
        String error = response.getErrorOutput().trim();
        if (response.getExitCode() != 0 && !error.isEmpty()) {
            throw new IOException(error);
        }
        markConnected();
        return response.getOutput();
    }

    private Dadb connectionLocked() throws Exception {
        if (dadb == null) {
            File directory = new File(context.getFilesDir(), "adb");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Tidak bisa membuat folder kunci ADB");
            }
            File privateKey = new File(directory, "web_adb_key");
            File publicKey = new File(directory, "web_adb_key.pub");
            if (!privateKey.isFile() || privateKey.length() == 0L) {
                AdbKeyPair.generate(privateKey, publicKey);
            }
            if (keyPair == null) {
                keyPair = AdbKeyPair.read(privateKey, publicKey);
            }
            dadb = Dadb.create(HOST, PORT, keyPair, 4000, 10_000, false);
        }
        return dadb;
    }

    private void markConnected() {
        connected = true;
        message = "ADB tersambung";
    }

    private void markFailure(Exception exception) {
        connected = false;
        message = friendlyMessage(exception);
        Log.w(TAG, "ADB unavailable: " + message);
    }

    private String friendlyMessage(Exception exception) {
        String type = exception.getClass().getSimpleName();
        String raw = exception.getMessage() == null ? "" : exception.getMessage();
        String lower = (type + " " + raw).toLowerCase(Locale.US);
        if (lower.contains("authexception") || lower.contains("unauthorized")
                || lower.contains("rejected authentication")) {
            return "Izin ADB belum diterima. Terima dialog di TV, lalu coba lagi.";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "Tidak ada respons. Pastikan ADB aktif dan izinnya sudah diterima di TV.";
        }
        if (lower.contains("connect") || lower.contains("refused")) {
            return "ADB lokal tidak aktif. Nyalakan debugging ADB di TV.";
        }
        return "ADB belum siap" + (raw.isEmpty() ? "." : ": " + raw);
    }

    private void closeLocked() {
        if (dadb != null) {
            try {
                dadb.close();
            } catch (Exception ignored) {
            }
            dadb = null;
        }
    }

    private static String buildTextCommand(String value) throws IOException {
        if (value == null) {
            return "input text ''";
        }
        if (value.length() > 500) {
            throw new IOException("Teks terlalu panjang");
        }
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\n' || character == '\r') {
                continue;
            }
            if (character < 0x20 || character > 0x7E) {
                throw new IOException("Keyboard remote hanya mendukung teks Latin sederhana");
            }
            ascii.append(character);
        }
        String escaped = ascii.toString().replace(" ", "%s");
        escaped = escaped.replace("'", "'\\''");
        return "input text '" + escaped + "'";
    }

    static String base64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private AdbBridge() {
        throw new AssertionError();
    }

    static byte[] readFile(File file) throws IOException {
        if (file == null || !file.isFile()) {
            return new byte[0];
        }
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] data = new byte[(int) Math.min(file.length(), 16L * 1024L * 1024L)];
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset == data.length) {
                return data;
            }
            byte[] shorter = new byte[offset];
            System.arraycopy(data, 0, shorter, 0, offset);
            return shorter;
        } finally {
            input.close();
        }
    }
}
