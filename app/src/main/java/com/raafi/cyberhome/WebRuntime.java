package com.raafi.cyberhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.util.Collections;

/**
 * Owns everything the embedded web remote needs: the HTTP server, PIN/session state, the layout
 * store and the loopback ADB bridge. All of it lives in the app process, so the TV needs no PC.
 */
final class WebRuntime {

    private static final String TAG = "WebRuntime";
    static final int DEFAULT_PORT = 8787;

    private static WebRuntime instance;

    static synchronized WebRuntime get(Context context) {
        if (instance == null) {
            instance = new WebRuntime(context.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final LayoutStore layoutStore;
    private final ScreensaverStore screensaverStore;
    private final AuthManager auth;
    private final AdbBridge adb;

    private volatile ControlServer server;
    private volatile int port;
    private volatile String lastError = "";
    private volatile long startedAt;

    private WebRuntime(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences(AuthManager.PREFS, Context.MODE_PRIVATE);
        layoutStore = LayoutStore.get(context);
        screensaverStore = ScreensaverStore.get(context);
        auth = new AuthManager(context);
        adb = new AdbBridge(context);
        port = clampPort(prefs.getInt("port", DEFAULT_PORT));
    }

    Context getContext() {
        return context;
    }

    LayoutStore getLayoutStore() {
        return layoutStore;
    }

    ScreensaverStore getScreensaverStore() {
        return screensaverStore;
    }

    AuthManager getAuth() {
        return auth;
    }

    AdbBridge getAdb() {
        return adb;
    }

    String getPin() {
        return auth.getPin();
    }

    synchronized boolean isRunning() {
        return server != null;
    }

    int getPort() {
        return port;
    }

    String getLastError() {
        return lastError;
    }

    long getStartedAt() {
        return startedAt;
    }

    String getUrl() {
        return "http://" + lanIp() + ":" + port;
    }

    synchronized boolean startServer() {
        if (server != null) {
            return true;
        }
        int preferred = clampPort(prefs.getInt("port", DEFAULT_PORT));
        IOException lastException = null;
        for (int offset = 0; offset < 10; offset++) {
            int candidate = preferred + offset;
            if (candidate > 65535) {
                break;
            }
            try {
                ControlServer next = new ControlServer(this, candidate);
                next.start(15_000, false);
                server = next;
                port = candidate;
                startedAt = System.currentTimeMillis();
                lastError = "";
                if (candidate != preferred) {
                    prefs.edit().putInt("port", candidate).apply();
                }
                Log.i(TAG, "Web remote listening on " + candidate);
                return true;
            } catch (IOException exception) {
                lastException = exception;
            }
        }
        lastError = lastException == null ? "Port tidak tersedia" : lastException.getMessage();
        Log.e(TAG, "Unable to start web server", lastException);
        return false;
    }

    synchronized void stopServer() {
        ControlServer current = server;
        server = null;
        startedAt = 0L;
        if (current != null) {
            try {
                current.stop();
            } catch (Exception ignored) {
            }
        }
    }

    boolean setPort(int requested) {
        if (requested < 1024 || requested > 65535 || requested == port) {
            return false;
        }
        if (!canBind(requested)) {
            return false;
        }
        prefs.edit().putInt("port", requested).apply();
        port = requested;
        return true;
    }

    void restartSoon(final long delayMs) {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stopServer();
                startServer();
                WebService.refreshNotification(context);
            }
        }, delayMs);
    }

    void restartWithPort(int newPort, long delayMs) {
        prefs.edit().putInt("port", newPort).apply();
        port = newPort;
        restartSoon(delayMs);
    }

    boolean canBind(int candidate) {
        ServerSocket socket = null;
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new java.net.InetSocketAddress(candidate));
            return true;
        } catch (Exception exception) {
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    static int clampPort(int value) {
        return value < 1024 || value > 65535 ? DEFAULT_PORT : value;
    }

    static String lanIp() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (network == null || !network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (java.net.InterfaceAddress address : network.getInterfaceAddresses()) {
                    InetAddress inet = address.getAddress();
                    if (inet instanceof Inet4Address && inet.isSiteLocalAddress()) {
                        return inet.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    static String deviceModel() {
        return Build.MANUFACTURER + " " + Build.MODEL;
    }

    static String androidVersion() {
        return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
    }

    static String buildId() {
        return Build.ID;
    }
}
