package com.raafi.cyberhome;

import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;

/**
 * The embedded HTTP API and the static web app. Deliberately close to the metal: no generic
 * shell endpoint, every command is a named whitelist entry, and all requests must come from a
 * private network address with a valid session + CSRF header.
 */
final class ControlServer extends NanoHTTPD {

    private static final String TAG = "ControlServer";
    private static final int MAX_JSON_BYTES = 64 * 1024;
    private static final int MAX_WALLPAPER_BYTES = 14 * 1024 * 1024;
    private static final long APP_CACHE_MS = 30_000L;

    private static final Map<String, Integer> REMOTE_KEYS = new HashMap<>();
    private static final Map<String, String> SETTINGS_ACTIONS = new HashMap<>();

    static {
        REMOTE_KEYS.put("up", 19);
        REMOTE_KEYS.put("down", 20);
        REMOTE_KEYS.put("left", 21);
        REMOTE_KEYS.put("right", 22);
        REMOTE_KEYS.put("ok", 23);
        REMOTE_KEYS.put("back", 4);
        REMOTE_KEYS.put("home", 3);
        REMOTE_KEYS.put("menu", 82);
        REMOTE_KEYS.put("info", 165);
        REMOTE_KEYS.put("search", 84);
        REMOTE_KEYS.put("play_pause", 85);
        REMOTE_KEYS.put("play", 126);
        REMOTE_KEYS.put("pause", 127);
        REMOTE_KEYS.put("next", 87);
        REMOTE_KEYS.put("previous", 88);
        REMOTE_KEYS.put("rewind", 89);
        REMOTE_KEYS.put("forward", 90);
        REMOTE_KEYS.put("volume_up", 24);
        REMOTE_KEYS.put("volume_down", 25);
        REMOTE_KEYS.put("mute", 164);
        REMOTE_KEYS.put("power", 26);
        REMOTE_KEYS.put("sleep", 223);
        REMOTE_KEYS.put("wake", 224);
        REMOTE_KEYS.put("enter", 66);
        REMOTE_KEYS.put("delete", 67);
        REMOTE_KEYS.put("tab", 61);
        REMOTE_KEYS.put("escape", 111);
        REMOTE_KEYS.put("space", 62);

        SETTINGS_ACTIONS.put("settings", Settings.ACTION_SETTINGS);
        SETTINGS_ACTIONS.put("wifi", Settings.ACTION_WIFI_SETTINGS);
        SETTINGS_ACTIONS.put("display", Settings.ACTION_DISPLAY_SETTINGS);
        SETTINGS_ACTIONS.put("sound", Settings.ACTION_SOUND_SETTINGS);
        SETTINGS_ACTIONS.put("apps", Settings.ACTION_APPLICATION_SETTINGS);
        SETTINGS_ACTIONS.put("accounts", Settings.ACTION_SYNC_SETTINGS);
        SETTINGS_ACTIONS.put("bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS);
        SETTINGS_ACTIONS.put("date", Settings.ACTION_DATE_SETTINGS);
        SETTINGS_ACTIONS.put("developer", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        SETTINGS_ACTIONS.put("about", Settings.ACTION_DEVICE_INFO_SETTINGS);
        SETTINGS_ACTIONS.put("keyboard", Settings.ACTION_INPUT_METHOD_SETTINGS);
        SETTINGS_ACTIONS.put("dream", Settings.ACTION_DREAM_SETTINGS);
    }

    private final WebRuntime runtime;
    private final Map<String, byte[]> iconCache = new ConcurrentHashMap<>();
    private final Object iconLock = new Object();

    private volatile List<AppEntry> cachedApps;
    private volatile long cachedAppsAt;

    ControlServer(WebRuntime runtime, int port) {
        super(port);
        this.runtime = runtime;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String address = session.getRemoteIpAddress();
        if (!isPrivateAddress(address)) {
            return json(Response.Status.FORBIDDEN,
                    errorBody("Kendali web hanya tersedia dari jaringan lokal"));
        }

        try {
            String uri = decodeUri(session.getUri());
            if (uri == null) {
                return json(Response.Status.BAD_REQUEST, errorBody("Alamat tidak valid"));
            }
            if (uri.startsWith("/api/")) {
                return serveApi(session, uri);
            }
            return serveAsset(session, uri);
        } catch (ApiError error) {
            Response response = json(error.status, errorBody(error.getMessage()));
            if (error.closeConnection) {
                response.closeConnection(true);
            }
            return response;
        } catch (Exception exception) {
            Log.e(TAG, "Request failed: " + session.getUri(), exception);
            return json(Response.Status.INTERNAL_ERROR,
                    errorBody("Terjadi kesalahan di TV"));
        }
    }

    // ---------------------------------------------------------------- API

    private Response serveApi(IHTTPSession session, String uri) throws Exception {
        Method method = session.getMethod();

        if ("/api/ping".equals(uri)) {
            requireMethod(method, Method.GET);
            JSONObject body = new JSONObject();
            body.put("ok", true);
            body.put("app", "CyberHome");
            body.put("version", "3.0");
            body.put("port", runtime.getPort());
            return json(Response.Status.OK, body);
        }

        if ("/api/login".equals(uri)) {
            requireMethod(method, Method.POST);
            return login(session);
        }

        String token = cookie(session, AuthManager.COOKIE);
        if (!runtime.getAuth().isValidSession(token)) {
            return json(Response.Status.UNAUTHORIZED, errorBody("Sesi berakhir. Masuk lagi."));
        }

        if (method == Method.POST || method == Method.PUT || method == Method.DELETE) {
            if (!"1".equals(session.getHeaders().get("x-cyber-client"))) {
                return json(Response.Status.FORBIDDEN, errorBody("Permintaan tidak dikenal"));
            }
            if (!originMatches(session)) {
                return json(Response.Status.FORBIDDEN, errorBody("Asal permintaan ditolak"));
            }
        }

        if ("/api/logout".equals(uri)) {
            requireMethod(method, Method.POST);
            runtime.getAuth().invalidateSession(token);
            Response response = json(Response.Status.OK, okBody());
            expireCookie(response);
            return response;
        }

        if ("/api/state".equals(uri)) {
            requireMethod(method, Method.GET);
            return state();
        }

        if ("/api/layout".equals(uri)) {
            requireMethod(method, Method.POST);
            JSONObject body = readJson(session, MAX_JSON_BYTES);
            LayoutConfig next = LayoutConfig.fromJson(body);
            if (!runtime.getLayoutStore().save(next)) {
                return json(Response.Status.INTERNAL_ERROR, errorBody("Gagal menyimpan layout"));
            }
            JSONObject result = okBody();
            result.put("layout", next.toJson());
            return json(Response.Status.OK, result);
        }

        if ("/api/layout/reset".equals(uri)) {
            requireMethod(method, Method.POST);
            if (!runtime.getLayoutStore().reset()) {
                return json(Response.Status.INTERNAL_ERROR, errorBody("Gagal mereset layout"));
            }
            JSONObject result = okBody();
            result.put("layout", runtime.getLayoutStore().getConfig().toJson());
            return json(Response.Status.OK, result);
        }

        if ("/api/screensaver".equals(uri)) {
            requireMethod(method, Method.POST);
            JSONObject body = readJson(session, MAX_JSON_BYTES);
            ScreensaverConfig next = ScreensaverConfig.fromJson(body);
            if (!runtime.getScreensaverStore().save(next)) {
                return json(Response.Status.INTERNAL_ERROR,
                        errorBody("Gagal menyimpan screensaver"));
            }
            JSONObject result = okBody();
            result.put("screensaver", runtime.getScreensaverStore().getConfig().toJson());
            return json(Response.Status.OK, result);
        }

        if ("/api/apps".equals(uri)) {
            requireMethod(method, Method.GET);
            return appsResponse();
        }

        if ("/api/icon".equals(uri)) {
            requireMethod(method, Method.GET);
            String pkg = session.getParms().get("pkg");
            return iconResponse(pkg);
        }

        if ("/api/apps/launch".equals(uri)) {
            requireMethod(method, Method.POST);
            return launchApp(session);
        }

        if ("/api/apps/info".equals(uri)) {
            requireMethod(method, Method.POST);
            return appInfo(session);
        }

        if ("/api/preview".equals(uri)) {
            requireMethod(method, Method.GET);
            byte[] jpeg = PreviewBridge.captureJpeg(1100, 82);
            if (jpeg == null) {
                return json(Response.Status.SERVICE_UNAVAILABLE,
                        errorBody("Pratinjau Home belum tersedia"));
            }
            return image(Response.Status.OK, "image/jpeg", jpeg, false);
        }

        if ("/api/screenshot".equals(uri)) {
            requireMethod(method, Method.GET);
            try {
                byte[] jpeg = runtime.getAdb().screenshotJpeg();
                return image(Response.Status.OK, "image/jpeg", jpeg, false);
            } catch (Exception exception) {
                JSONObject body = errorBody(runtime.getAdb().getMessage());
                body.put("adb", adbJson());
                return json(Response.Status.SERVICE_UNAVAILABLE, body);
            }
        }

        if ("/api/remote".equals(uri)) {
            requireMethod(method, Method.POST);
            return remoteKey(session);
        }

        if ("/api/text".equals(uri)) {
            requireMethod(method, Method.POST);
            return sendText(session);
        }

        if ("/api/action".equals(uri)) {
            requireMethod(method, Method.POST);
            return openSettingsAction(session);
        }

        if ("/api/wallpaper".equals(uri)) {
            if (method == Method.GET) {
                return wallpaperResponse();
            }
            if (method == Method.POST) {
                return uploadWallpaper(session);
            }
            if (method == Method.DELETE) {
                if (!runtime.getLayoutStore().clearWallpaper()) {
                    return json(Response.Status.INTERNAL_ERROR, errorBody("Gagal menghapus wallpaper"));
                }
                return json(Response.Status.OK, okBody());
            }
            throw new ApiError(Response.Status.METHOD_NOT_ALLOWED, "Metode tidak didukung", false);
        }

        if ("/api/adb/probe".equals(uri)) {
            requireMethod(method, Method.POST);
            runtime.getAdb().probe();
            JSONObject body = okBody();
            body.put("adb", adbJson());
            return json(Response.Status.OK, body);
        }

        if ("/api/settings/pin".equals(uri)) {
            requireMethod(method, Method.POST);
            return changePin(session, token);
        }

        if ("/api/settings/port".equals(uri)) {
            requireMethod(method, Method.POST);
            return changePort(session);
        }

        if ("/api/server/restart".equals(uri)) {
            requireMethod(method, Method.POST);
            runtime.restartSoon(700L);
            JSONObject body = okBody();
            body.put("message", "Server dimulai ulang");
            return json(Response.Status.OK, body);
        }

        return json(Response.Status.NOT_FOUND, errorBody("Endpoint tidak ditemukan"));
    }

    private Response login(IHTTPSession session) throws Exception {
        String address = session.getRemoteIpAddress();
        AuthManager auth = runtime.getAuth();
        if (!auth.allowAttempt(address)) {
            return json(Response.Status.TOO_MANY_REQUESTS,
                    errorBody("Terlalu banyak percobaan. Tunggu beberapa menit."));
        }
        JSONObject body = readJson(session, 4096);
        String pin = body.optString("pin", "");
        if (!auth.checkPin(pin)) {
            auth.recordFailure(address);
            try {
                Thread.sleep(320L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return json(Response.Status.UNAUTHORIZED, errorBody("PIN salah"));
        }
        auth.clearFailures(address);
        String token = auth.createSession();
        JSONObject result = okBody();
        result.put("message", "Masuk");
        Response response = json(Response.Status.OK, result);
        addSessionCookie(response, token);
        return response;
    }

    private Response state() throws JSONException {
        WebRuntime web = runtime;
        JSONObject body = okBody();
        body.put("layout", web.getLayoutStore().getConfig().toJson());
        body.put("wallpaper", web.getLayoutStore().hasWallpaper());
        body.put("screensaver", web.getScreensaverStore().getConfig().toJson());
        body.put("screensaver_status", screensaverStatus());
        JSONObject prayer = prayerPreview();
        body.put("prayer", prayer == null ? JSONObject.NULL : prayer);

        JSONObject device = new JSONObject();
        device.put("model", WebRuntime.deviceModel());
        device.put("android", WebRuntime.androidVersion());
        device.put("build", WebRuntime.buildId());
        android.util.DisplayMetrics metrics = web.getContext().getResources().getDisplayMetrics();
        device.put("width", metrics.widthPixels);
        device.put("height", metrics.heightPixels);
        body.put("device", device);

        JSONObject server = new JSONObject();
        server.put("port", web.getPort());
        server.put("url", web.getUrl());
        server.put("running", web.isRunning());
        server.put("lanOnly", true);
        server.put("tls", false);
        server.put("startedAt", web.getStartedAt());
        body.put("server", server);

        body.put("adb", adbJson());

        List<AppEntry> scanned = apps();
        LayoutConfig config = web.getLayoutStore().getConfig();
        List<AppEntry> visible = AppScanner.order(scanned, config, false);
        List<AppEntry> hidden = AppScanner.hidden(scanned, config);
        JSONObject apps = new JSONObject();
        apps.put("visible", visible.size());
        apps.put("hidden", hidden.size());
        body.put("apps", apps);

        body.put("greeting", config.greetingName);
        body.put("now", System.currentTimeMillis());
        return json(Response.Status.OK, body);
    }

    private JSONObject screensaverStatus() throws JSONException {
        JSONObject status = new JSONObject();
        String shortComponent = runtime.getContext().getPackageName()
                + "/.PrayerDreamService";
        String fullComponent = runtime.getContext().getPackageName()
                + "/" + PrayerDreamService.class.getName();
        String configured = null;
        boolean enabled = true;
        try {
            configured = Settings.Secure.getString(
                    runtime.getContext().getContentResolver(), "screensaver_components");
            enabled = Settings.Secure.getInt(
                    runtime.getContext().getContentResolver(), "screensaver_enabled", 1) != 0;
        } catch (Exception ignored) {
        }

        boolean active = false;
        if (configured != null) {
            for (String part : configured.split(":")) {
                String value = part.trim();
                if (value.equals(shortComponent) || value.equals(fullComponent)) {
                    active = true;
                    break;
                }
            }
        }
        status.put("active", active);
        status.put("enabled", enabled);
        status.put("readable", configured != null);
        status.put("component", shortComponent);
        return status;
    }

    private JSONObject prayerPreview() throws JSONException {
        PrayerTimes times = PrayerTimes.load(runtime.getContext());
        if (times == null) {
            return null;
        }
        JSONObject prayer = new JSONObject();
        prayer.put("city", times.city);
        prayer.put("hijri", times.hijri);
        prayer.put("today", times.isForToday());
        JSONArray minutes = new JSONArray();
        for (int value : times.minutes) {
            minutes.put(value);
        }
        prayer.put("times", minutes);
        return prayer;
    }

    private Response appsResponse() throws JSONException {
        List<AppEntry> scanned = apps();
        LayoutConfig config = runtime.getLayoutStore().getConfig();
        List<AppEntry> visible = AppScanner.order(scanned, config, false);
        List<AppEntry> hidden = AppScanner.hidden(scanned, config);

        JSONObject body = okBody();
        body.put("visible", appArray(visible));
        body.put("hidden", appArray(hidden));
        return json(Response.Status.OK, body);
    }

    private JSONArray appArray(List<AppEntry> apps) throws JSONException {
        JSONArray array = new JSONArray();
        for (AppEntry entry : apps) {
            JSONObject app = new JSONObject();
            app.put("pkg", entry.pkg);
            app.put("label", entry.label);
            app.put("leanback", entry.leanback);
            array.put(app);
        }
        return array;
    }

    private Response iconResponse(String pkg) throws Exception {
        if (!LayoutConfig.isPackageName(pkg)) {
            return json(Response.Status.BAD_REQUEST, errorBody("Nama paket tidak valid"));
        }
        byte[] cached = iconCache.get(pkg);
        if (cached != null) {
            return image(Response.Status.OK, "image/png", cached, true);
        }
        AppEntry target = null;
        for (AppEntry entry : apps()) {
            if (entry.pkg.equals(pkg)) {
                target = entry;
                break;
            }
        }
        if (target == null) {
            return json(Response.Status.NOT_FOUND, errorBody("Aplikasi tidak ditemukan"));
        }

        synchronized (iconLock) {
            cached = iconCache.get(pkg);
            if (cached == null) {
                cached = drawIcon(target.icon == null
                        ? runtime.getContext().getPackageManager().getDefaultActivityIcon()
                        : target.icon);
                if (cached != null) {
                    iconCache.put(pkg, cached);
                }
            }
        }
        if (cached == null) {
            return json(Response.Status.INTERNAL_ERROR, errorBody("Ikon tidak tersedia"));
        }
        return image(Response.Status.OK, "image/png", cached, true);
    }

    private byte[] drawIcon(Drawable drawable) {
        try {
            int size = 112;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xFF101C2E);
            canvas.drawRoundRect(new RectF(0, 0, size, size), 26, 26, paint);
            int inset = 15;
            drawable.setBounds(inset, inset, size - inset, size - inset);
            drawable.draw(canvas);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            bitmap.recycle();
            return output.toByteArray();
        } catch (Exception | OutOfMemoryError exception) {
            return null;
        }
    }

    private Response launchApp(IHTTPSession session) throws Exception {
        JSONObject body = readJson(session, 4096);
        String pkg = body.optString("pkg", "");
        if (!findApp(pkg)) {
            return json(Response.Status.NOT_FOUND, errorBody("Aplikasi tidak ditemukan"));
        }
        Intent intent = AppScanner.launchIntent(runtime.getContext(), pkg);
        if (intent == null) {
            return json(Response.Status.CONFLICT, errorBody("Aplikasi ini tidak bisa dibuka"));
        }
        try {
            runtime.getContext().startActivity(intent);
        } catch (Exception exception) {
            return json(Response.Status.CONFLICT, errorBody("Gagal membuka aplikasi"));
        }
        JSONObject result = okBody();
        result.put("message", "Membuka aplikasi");
        return json(Response.Status.OK, result);
    }

    private Response appInfo(IHTTPSession session) throws Exception {
        JSONObject body = readJson(session, 4096);
        String pkg = body.optString("pkg", "");
        if (!findApp(pkg)) {
            return json(Response.Status.NOT_FOUND, errorBody("Aplikasi tidak ditemukan"));
        }
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            runtime.getContext().startActivity(intent);
            return json(Response.Status.OK, okBody());
        } catch (Exception exception) {
            return json(Response.Status.CONFLICT, errorBody("Info aplikasi tidak tersedia"));
        }
    }

    private boolean findApp(String pkg) {
        if (!LayoutConfig.isPackageName(pkg)) {
            return false;
        }
        for (AppEntry entry : apps()) {
            if (entry.pkg.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    private Response remoteKey(IHTTPSession session) throws Exception {
        JSONObject body = readJson(session, 2048);
        String key = body.optString("key", "");
        Integer keycode = REMOTE_KEYS.get(key);
        if (keycode == null) {
            return json(Response.Status.BAD_REQUEST, errorBody("Tombol tidak dikenal"));
        }
        try {
            runtime.getAdb().key(keycode);
            JSONObject result = okBody();
            result.put("adb", adbJson());
            return json(Response.Status.OK, result);
        } catch (Exception exception) {
            JSONObject error = errorBody(runtime.getAdb().getMessage());
            error.put("adb", adbJson());
            return json(Response.Status.CONFLICT, error);
        }
    }

    private Response sendText(IHTTPSession session) throws Exception {
        JSONObject body = readJson(session, 8192);
        String text = body.optString("text", "");
        if (text.length() > 500) {
            return json(Response.Status.BAD_REQUEST, errorBody("Teks terlalu panjang"));
        }
        try {
            runtime.getAdb().text(text);
            return json(Response.Status.OK, okBody());
        } catch (IllegalArgumentException exception) {
            return json(Response.Status.BAD_REQUEST, errorBody(exception.getMessage()));
        } catch (Exception exception) {
            JSONObject error = errorBody(runtime.getAdb().getMessage());
            error.put("adb", adbJson());
            return json(Response.Status.CONFLICT, error);
        }
    }

    private Response openSettingsAction(IHTTPSession session) throws Exception {
        JSONObject body = readJson(session, 2048);
        String action = body.optString("action", "");
        String intentAction = SETTINGS_ACTIONS.get(action);
        if (intentAction == null) {
            return json(Response.Status.BAD_REQUEST, errorBody("Aksi tidak dikenal"));
        }
        try {
            Intent intent = new Intent(intentAction);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            runtime.getContext().startActivity(intent);
            return json(Response.Status.OK, okBody());
        } catch (Exception exception) {
            return json(Response.Status.CONFLICT, errorBody("Halaman setelan tidak tersedia"));
        }
    }

    private Response wallpaperResponse() throws Exception {
        File file = runtime.getLayoutStore().getWallpaperFile();
        if (!file.isFile() || file.length() == 0L) {
            return json(Response.Status.NOT_FOUND, errorBody("Belum ada wallpaper"));
        }
        byte[] bytes = AdbBridge.readFile(file);
        if (bytes.length == 0) {
            return json(Response.Status.INTERNAL_ERROR, errorBody("Wallpaper tidak bisa dibaca"));
        }
        String type = detectImageType(bytes);
        if (type == null) {
            return json(Response.Status.INTERNAL_ERROR, errorBody("Wallpaper rusak"));
        }
        return image(Response.Status.OK, type, bytes, false);
    }

    private Response uploadWallpaper(IHTTPSession session) throws Exception {
        String type = session.getHeaders().get("content-type");
        if (type != null && !type.toLowerCase(Locale.US).startsWith("image/")) {
            return json(Response.Status.UNSUPPORTED_MEDIA_TYPE,
                    errorBody("Gunakan file gambar"));
        }
        byte[] bytes = readBody(session, MAX_WALLPAPER_BYTES);
        String detected = detectImageType(bytes);
        if (detected == null) {
            return json(Response.Status.BAD_REQUEST,
                    errorBody("Format harus JPG, PNG, atau WebP"));
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth < 320 || bounds.outHeight < 180) {
            return json(Response.Status.BAD_REQUEST, errorBody("Gambar terlalu kecil"));
        }
        if ((long) bounds.outWidth * bounds.outHeight > 7680L * 4320L) {
            return json(Response.Status.BAD_REQUEST, errorBody("Gambar terlalu besar"));
        }
        if (!runtime.getLayoutStore().saveWallpaper(bytes)) {
            return json(Response.Status.INTERNAL_ERROR, errorBody("Gagal menyimpan wallpaper"));
        }
        JSONObject result = okBody();
        result.put("width", bounds.outWidth);
        result.put("height", bounds.outHeight);
        result.put("size", bytes.length);
        return json(Response.Status.OK, result);
    }

    private Response changePin(IHTTPSession session, String token) throws Exception {
        JSONObject body = readJson(session, 4096);
        String current = body.optString("current", "");
        String next = body.optString("next", "");
        if (!AuthManager.isValidPin(next)) {
            return json(Response.Status.BAD_REQUEST, errorBody("PIN baru harus 6 angka"));
        }
        if (!runtime.getAuth().changePin(current, next)) {
            return json(Response.Status.UNAUTHORIZED, errorBody("PIN lama salah"));
        }
        String replacement = runtime.getAuth().createSession();
        JSONObject result = okBody();
        result.put("message", "PIN diperbarui");
        Response response = json(Response.Status.OK, result);
        addSessionCookie(response, replacement);
        return response;
    }

    private Response changePort(IHTTPSession session) throws Exception {
        JSONObject body = readJson(session, 2048);
        int requested = body.optInt("port", -1);
        if (requested < 1024 || requested > 65535) {
            return json(Response.Status.BAD_REQUEST,
                    errorBody("Port harus antara 1024 dan 65535"));
        }
        if (requested == runtime.getPort()) {
            return json(Response.Status.BAD_REQUEST, errorBody("Itu port yang sedang dipakai"));
        }
        if (!runtime.canBind(requested)) {
            return json(Response.Status.CONFLICT, errorBody("Port sedang dipakai aplikasi lain"));
        }
        runtime.restartWithPort(requested, 900L);
        JSONObject result = okBody();
        result.put("port", requested);
        result.put("url", "http://" + WebRuntime.lanIp() + ":" + requested);
        result.put("message", "Server pindah ke port " + requested);
        return json(Response.Status.OK, result);
    }

    private JSONObject adbJson() throws JSONException {
        JSONObject adb = new JSONObject();
        adb.put("connected", runtime.getAdb().isConnected());
        adb.put("message", runtime.getAdb().getMessage());
        adb.put("checkedAt", runtime.getAdb().getLastProbeAt());
        adb.put("port", 5555);
        return adb;
    }

    // ---------------------------------------------------------------- helpers

    private List<AppEntry> apps() {
        List<AppEntry> local = cachedApps;
        long now = System.currentTimeMillis();
        if (local == null || now - cachedAppsAt > APP_CACHE_MS) {
            local = AppScanner.scan(runtime.getContext());
            cachedApps = local;
            cachedAppsAt = now;
        }
        return local;
    }

    private JSONObject readJson(IHTTPSession session, int maxBytes) throws Exception {
        byte[] bytes = readBody(session, maxBytes);
        if (bytes.length == 0) {
            return new JSONObject();
        }
        try {
            return new JSONObject(new String(bytes, "UTF-8"));
        } catch (JSONException exception) {
            throw new ApiError(Response.Status.BAD_REQUEST, "JSON tidak valid", false);
        }
    }

    private byte[] readBody(IHTTPSession session, int maxBytes) throws Exception {
        String header = session.getHeaders().get("content-length");
        if (header == null) {
            return new byte[0];
        }
        int length;
        try {
            length = Integer.parseInt(header.trim());
        } catch (NumberFormatException exception) {
            throw new ApiError(Response.Status.BAD_REQUEST, "Content-Length tidak valid", true);
        }
        if (length <= 0) {
            return new byte[0];
        }
        if (length > maxBytes) {
            throw new ApiError(Response.Status.PAYLOAD_TOO_LARGE, "Data terlalu besar", true);
        }
        byte[] buffer = new byte[length];
        InputStream input = session.getInputStream();
        int offset = 0;
        while (offset < length) {
            int read = input.read(buffer, offset, length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        if (offset == 0) {
            return new byte[0];
        }
        if (offset != length) {
            throw new ApiError(Response.Status.BAD_REQUEST, "Data terpotong", true);
        }
        return buffer;
    }

    private static String decodeUri(String uri) {
        if (uri == null) {
            return null;
        }
        try {
            String decoded = java.net.URLDecoder.decode(uri, "UTF-8");
            if (decoded.indexOf('\0') >= 0 || decoded.contains("..")) {
                return null;
            }
            return decoded;
        } catch (Exception exception) {
            return null;
        }
    }

    private Response serveAsset(IHTTPSession session, String uri) {
        if (session.getMethod() != Method.GET && session.getMethod() != Method.HEAD) {
            return json(Response.Status.METHOD_NOT_ALLOWED, errorBody("Metode tidak didukung"));
        }
        String path = "/".equals(uri) ? "/index.html" : uri;
        String mime = assetMime(path);
        if (mime == null) {
            return json(Response.Status.NOT_FOUND, errorBody("Halaman tidak ditemukan"));
        }
        AssetManager assets = runtime.getContext().getAssets();
        InputStream input = null;
        try {
            input = assets.open("web" + path);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) >= 0) {
                output.write(chunk, 0, read);
            }
            byte[] bytes = output.toByteArray();
            Response response = newFixedLengthResponse(Response.Status.OK, mime,
                    new ByteArrayInputStream(bytes), bytes.length);
            addSecurityHeaders(response);
            if (path.endsWith(".html")) {
                response.addHeader("Cache-Control", "no-cache");
                response.addHeader("Content-Security-Policy",
                        "default-src 'self'; img-src 'self' data: blob:; "
                                + "style-src 'self' 'unsafe-inline'; script-src 'self'; "
                                + "font-src 'self'; connect-src 'self'; "
                                + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
            } else if (path.endsWith(".ttf")) {
                response.addHeader("Cache-Control", "public, max-age=86400");
            } else {
                response.addHeader("Cache-Control", "no-cache");
            }
            return response;
        } catch (IOException exception) {
            return json(Response.Status.NOT_FOUND, errorBody("Aset tidak ditemukan"));
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String assetMime(String path) {
        switch (path) {
            case "/index.html":
                return "text/html; charset=utf-8";
            case "/css/app.css":
                return "text/css; charset=utf-8";
            case "/js/app.js":
                return "application/javascript; charset=utf-8";
            case "/js/api.js":
                return "application/javascript; charset=utf-8";
            case "/outfit.ttf":
                return "font/ttf";
            case "/favicon.svg":
                return "image/svg+xml";
            case "/manifest.webmanifest":
                return "application/manifest+json";
            default:
                return null;
        }
    }

    private Response json(Response.Status status, JSONObject body) {
        Response response = newFixedLengthResponse(status, "application/json; charset=utf-8",
                body.toString());
        addSecurityHeaders(response);
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private Response image(Response.Status status, String mime, byte[] bytes, boolean cache) {
        Response response = newFixedLengthResponse(status, mime,
                new ByteArrayInputStream(bytes), bytes.length);
        addSecurityHeaders(response);
        response.addHeader("Cache-Control", cache ? "private, max-age=1800" : "no-store");
        return response;
    }

    private void addSecurityHeaders(Response response) {
        response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("Referrer-Policy", "no-referrer");
        response.addHeader("X-Frame-Options", "DENY");
        response.addHeader("Cross-Origin-Resource-Policy", "same-origin");
    }

    private JSONObject okBody() {
        JSONObject body = new JSONObject();
        try {
            body.put("ok", true);
        } catch (JSONException ignored) {
        }
        return body;
    }

    private JSONObject errorBody(String message) {
        JSONObject body = new JSONObject();
        try {
            body.put("ok", false);
            body.put("error", message == null ? "Terjadi kesalahan" : message);
        } catch (JSONException ignored) {
        }
        return body;
    }

    private void requireMethod(Method actual, Method expected) throws ApiError {
        if (actual != expected) {
            throw new ApiError(Response.Status.METHOD_NOT_ALLOWED, "Metode tidak didukung", false);
        }
    }

    private String cookie(IHTTPSession session, String name) {
        String header = session.getHeaders().get("cookie");
        if (header == null) {
            return null;
        }
        for (String part : header.split(";")) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            if (part.substring(0, equals).trim().equals(name)) {
                return part.substring(equals + 1).trim();
            }
        }
        return null;
    }

    private void addSessionCookie(Response response, String token) {
        response.addHeader("Set-Cookie", AuthManager.COOKIE + "=" + token
                + "; Max-Age=604800; Path=/; HttpOnly; SameSite=Lax");
    }

    private void expireCookie(Response response) {
        response.addHeader("Set-Cookie", AuthManager.COOKIE
                + "=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax");
    }

    private boolean originMatches(IHTTPSession session) {
        String origin = session.getHeaders().get("origin");
        if (origin == null || origin.isEmpty()) {
            return true;
        }
        String host = session.getHeaders().get("host");
        if (host == null) {
            return false;
        }
        try {
            URI originUri = new URI(origin);
            String originHost = originUri.getHost();
            int originPort = originUri.getPort() > 0 ? originUri.getPort()
                    : ("https".equalsIgnoreCase(originUri.getScheme()) ? 443 : 80);
            String requestHost = host;
            int requestPort = getListeningPort();
            int colon = host.lastIndexOf(':');
            if (colon > 0 && host.indexOf(']') < colon) {
                requestHost = host.substring(0, colon);
                requestPort = Integer.parseInt(host.substring(colon + 1));
            }
            return originHost != null
                    && originHost.equalsIgnoreCase(requestHost)
                    && originPort == requestPort;
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean isPrivateAddress(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(raw);
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress()) {
                return true;
            }
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) {
                int first = bytes[0] & 0xFF;
                int second = bytes[1] & 0xFF;
                return first == 100 && second >= 64 && second <= 127;
            }
            return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String detectImageType(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static final class ApiError extends Exception {
        final Response.Status status;
        final boolean closeConnection;

        ApiError(Response.Status status, String message, boolean closeConnection) {
            super(message);
            this.status = status;
            this.closeConnection = closeConnection;
        }
    }
}
