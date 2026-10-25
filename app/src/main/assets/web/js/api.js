/*
 * CyberHome web client — transport layer.
 * Every request carries the CSRF marker header and the session cookie.
 * Mutations are rejected by the TV unless both are present.
 */
(function () {
  "use strict";

  var CLIENT_HEADER = "X-Cyber-Client";

  function ApiError(message, status, data) {
    this.name = "ApiError";
    this.message = message;
    this.status = status || 0;
    this.data = data || null;
  }
  ApiError.prototype = Object.create(Error.prototype);
  ApiError.prototype.constructor = ApiError;

  function fallbackMessage(status) {
    switch (status) {
      case 0: return "TV tidak bisa dihubungi. Pastikan HP dan TV di Wi-Fi yang sama.";
      case 400: return "Permintaan tidak valid.";
      case 401: return "Sesi berakhir. Masuk lagi dengan PIN.";
      case 403: return "Akses ditolak.";
      case 404: return "Data tidak ditemukan.";
      case 409: return "TV sedang sibuk atau ADB belum tersambung.";
      case 413: return "Data terlalu besar.";
      case 429: return "Terlalu banyak percobaan. Tunggu sebentar.";
      case 500: return "Terjadi kesalahan di TV.";
      case 503: return "Fitur ini belum tersedia.";
      default: return "Permintaan gagal (" + status + ").";
    }
  }

  function withTimeout(controller, timeout) {
    if (!timeout || typeof window.AbortController === "undefined") {
      return null;
    }
    return window.setTimeout(function () {
      try { controller.abort(); } catch (ignored) {}
    }, timeout);
  }

  async function request(path, options) {
    var opts = options || {};
    var init = {
      method: opts.method || "GET",
      credentials: "same-origin",
      cache: "no-store",
      headers: {}
    };

    var controller = null;
    if (typeof window.AbortController !== "undefined") {
      controller = new AbortController();
      init.signal = controller.signal;
    }
    var timer = withTimeout(controller, opts.timeout || 20000);

    if (opts.body !== undefined && opts.body !== null) {
      init.body = opts.body;
    }
    if (opts.json !== undefined) {
      init.headers["Content-Type"] = "application/json; charset=utf-8";
      init.body = JSON.stringify(opts.json);
    }
    if (opts.contentType) {
      init.headers["Content-Type"] = opts.contentType;
    }
    init.headers[CLIENT_HEADER] = "1";
    if (opts.headers) {
      Object.keys(opts.headers).forEach(function (key) {
        init.headers[key] = opts.headers[key];
      });
    }

    var response;
    try {
      response = await fetch(path, init);
    } catch (error) {
      if (timer) window.clearTimeout(timer);
      if (error && error.name === "AbortError") {
        throw new ApiError("TV tidak menjawab. Coba lagi.", 0, null);
      }
      throw new ApiError(fallbackMessage(0), 0, null);
    }
    if (timer) window.clearTimeout(timer);

    var contentType = response.headers.get("Content-Type") || "";
    if (opts.binary && response.ok) {
      return await response.blob();
    }

    var data = null;
    if (contentType.indexOf("application/json") >= 0) {
      try { data = await response.json(); } catch (ignored) { data = null; }
    }

    if (!response.ok) {
      var message = (data && (data.error || data.message)) || fallbackMessage(response.status);
      if (response.status === 401 && path !== "/api/login" && path !== "/api/settings/pin") {
        window.dispatchEvent(new CustomEvent("cyber:unauthorized"));
      }
      throw new ApiError(message, response.status, data);
    }

    return data;
  }

  var api = {
    ApiError: ApiError,

    ping: function () {
      return request("/api/ping", { timeout: 6000 });
    },

    login: function (pin) {
      return request("/api/login", {
        method: "POST",
        json: { pin: String(pin) },
        timeout: 15000
      });
    },

    logout: function () {
      return request("/api/logout", { method: "POST", timeout: 8000 });
    },

    state: function () {
      return request("/api/state", { timeout: 12000 });
    },

    saveLayout: function (layout) {
      return request("/api/layout", {
        method: "POST",
        json: layout,
        timeout: 12000
      });
    },

    resetLayout: function () {
      return request("/api/layout/reset", { method: "POST", timeout: 10000 });
    },

    saveScreensaver: function (screensaver) {
      return request("/api/screensaver", {
        method: "POST",
        json: screensaver,
        timeout: 15000
      });
    },

    apps: function () {
      return request("/api/apps", { timeout: 15000 });
    },

    launchApp: function (pkg) {
      return request("/api/apps/launch", {
        method: "POST",
        json: { pkg: pkg },
        timeout: 12000
      });
    },

    appInfo: function (pkg) {
      return request("/api/apps/info", {
        method: "POST",
        json: { pkg: pkg },
        timeout: 12000
      });
    },

    iconUrl: function (pkg) {
      return "/api/icon?pkg=" + encodeURIComponent(pkg);
    },

    previewUrl: function (stamp) {
      return "/api/preview?t=" + (stamp || Date.now());
    },

    screenshot: function () {
      return request("/api/screenshot", {
        binary: true,
        timeout: 45000,
        headers: { "Accept": "image/jpeg" }
      });
    },

    remote: function (key) {
      return request("/api/remote", {
        method: "POST",
        json: { key: key },
        timeout: 20000
      });
    },

    text: function (value) {
      return request("/api/text", {
        method: "POST",
        json: { text: String(value) },
        timeout: 20000
      });
    },

    action: function (action) {
      return request("/api/action", {
        method: "POST",
        json: { action: action },
        timeout: 12000
      });
    },

    wallpaperUrl: function (stamp) {
      return "/api/wallpaper?t=" + (stamp || Date.now());
    },

    uploadWallpaper: function (blob) {
      return request("/api/wallpaper", {
        method: "POST",
        body: blob,
        contentType: blob.type || "image/jpeg",
        timeout: 90000
      });
    },

    deleteWallpaper: function () {
      return request("/api/wallpaper", { method: "DELETE", timeout: 15000 });
    },

    probeAdb: function () {
      return request("/api/adb/probe", { method: "POST", timeout: 30000 });
    },

    changePin: function (current, next) {
      return request("/api/settings/pin", {
        method: "POST",
        json: { current: String(current), next: String(next) },
        timeout: 15000
      });
    },

    changePort: function (port) {
      return request("/api/settings/port", {
        method: "POST",
        json: { port: Number(port) },
        timeout: 15000
      });
    },

    restartServer: function () {
      return request("/api/server/restart", { method: "POST", timeout: 10000 });
    }
  };

  window.CyberApi = api;
})();
