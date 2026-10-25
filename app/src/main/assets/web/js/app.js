/*
 * CyberHome web deck — application layer.
 * No framework, no network beyond this TV: the page is served by the launcher itself.
 */
(function () {
  "use strict";

  var api = window.CyberApi;
  var ROUTES = {
    home: { title: "Beranda", eyebrow: "CONTROL DECK", template: "tpl-home" },
    remote: { title: "Remote", eyebrow: "NAVIGASI", template: "tpl-remote" },
    layout: { title: "Layout", eyebrow: "PERSONALISASI", template: "tpl-layout" },
    screensaver: { title: "Layar", eyebrow: "SCREENSAVER", template: "tpl-screensaver" },
    apps: { title: "Aplikasi", eyebrow: "HOME GRID", template: "tpl-apps" },
    settings: { title: "Setelan", eyebrow: "SERVER & KEAMANAN", template: "tpl-settings" }
  };
  var MONTHS = ["Januari", "Februari", "Maret", "April", "Mei", "Juni", "Juli",
    "Agustus", "September", "Oktober", "November", "Desember"];
  var DAYS = ["Minggu", "Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu"];
  var DAYS_SHORT = ["Min", "Sen", "Sel", "Rab", "Kam", "Jum", "Sab"];
  var MONTHS_SHORT = ["Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu",
    "Sep", "Okt", "Nov", "Des"];
  var PRAYER_NAMES = ["Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya"];
  var SCREEN_MODES = {
    prayer: { label: "Jadwal sholat", scene: "JADWAL SHOLAT" },
    notes: { label: "Catatan", scene: "CATATAN" },
    todos: { label: "To-do list", scene: "TO-DO LIST" }
  };

  var state = {
    authenticated: false,
    booted: false,
    route: null,
    layout: null,
    device: null,
    server: null,
    adb: null,
    apps: { visible: [], hidden: [] },
    appsLoaded: false,
    appTab: "visible",
    appQuery: "",
    wallpaper: false,
    wallpaperVersion: Date.now(),
    previewReload: null,
    saveTimer: null,
    saveSeq: 0,
    dirty: false,
    screensaver: null,
    screensaverStatus: null,
    prayer: null,
    screenSaveTimer: null,
    screenSaveSeq: 0,
    screenDirty: false,
    noteEditId: null,
    lastShotUrl: null,
    screenshotAuto: false,
    adbPromptShown: false,
    focusWallpaper: false
  };

  var pageCleanup = null;
  var keyQueue = Promise.resolve();
  var modalResolve = null;
  var initialized = false;

  var el = {};

  function $(selector, root) {
    return (root || document).querySelector(selector);
  }

  function $$(selector, root) {
    return Array.prototype.slice.call((root || document).querySelectorAll(selector));
  }

  function byId(id) {
    return document.getElementById(id);
  }

  function init() {
    if (initialized) return;
    initialized = true;
    el.boot = byId("boot");
    el.login = byId("login");
    el.app = byId("app");
    el.view = byId("view");
    el.pin = byId("pin");
    el.loginForm = byId("loginForm");
    el.loginError = byId("loginError");
    el.loginSubmit = byId("loginSubmit");
    el.loginRetry = byId("loginRetry");
    el.modal = byId("modal");
    el.modalTitle = byId("modalTitle");
    el.modalText = byId("modalText");
    el.modalEyebrow = byId("modalEyebrow");
    el.modalOk = byId("modalOk");
    el.modalCancel = byId("modalCancel");
    el.lightbox = byId("lightbox");
    el.lightboxImage = byId("lightboxImage");
    el.lightboxLoader = byId("lightboxLoader");
    el.navAppsBadge = byId("navAppsBadge");
    el.sideUrl = byId("sideUrl");
    el.footUrl = byId("footUrl");
    el.pageTitle = byId("pageTitle");
    el.pageEyebrow = byId("pageEyebrow");

    bindGlobal();
    startClock();
    routeFromHash();
    boot();
  }

  // ------------------------------------------------------------- boot / auth

  async function boot() {
    try {
      var data = await api.state();
      hideBoot();
      enterApp(data);
    } catch (error) {
      hideBoot();
      showLogin(error.status === 401 ? "" : error.message);
    }
  }

  function hideBoot() {
    if (!el.boot || state.booted) return;
    state.booted = true;
    el.boot.classList.add("is-done");
    window.setTimeout(function () {
      if (el.boot && el.boot.parentNode) el.boot.parentNode.removeChild(el.boot);
    }, 700);
  }

  function showLogin(message) {
    state.authenticated = false;
    stopPage();
    closeModal(false);
    closeLightbox();
    if (el.app) el.app.classList.add("hidden");
    if (el.login) el.login.classList.remove("hidden");
    hideBoot();
    if (el.loginError) el.loginError.textContent = message || "";
    if (el.loginRetry) el.loginRetry.classList.toggle("hidden", !message);
    if (el.pin) {
      el.pin.value = "";
    }
    setLoginBusy(false);
    window.setTimeout(function () {
      if (el.pin && !el.login.classList.contains("hidden")) el.pin.focus();
    }, 260);
  }

  function enterApp(data) {
    state.authenticated = true;
    applyState(data);
    state.dirty = false;
    state.saveSeq = 0;
    state.screenDirty = false;
    state.screenSaveSeq = 0;
    state.noteEditId = null;
    window.clearTimeout(state.screenSaveTimer);
    el.login.classList.add("hidden");
    el.app.classList.remove("hidden");
    hideBoot();
    hydrateChrome();
    applyAccent(state.layout.accent);
    var route = parseRoute();
    if (!window.location.hash) {
      history.replaceState(null, "", "#/home");
    }
    state.route = null;
    renderRoute(route);
    window.scrollTo(0, 0);
  }

  function applyState(data) {
    state.layout = data.layout;
    state.device = data.device || state.device || {};
    state.server = data.server || state.server || {};
    state.adb = data.adb || state.adb || { connected: false, message: "Belum tersambung" };
    state.wallpaper = !!data.wallpaper;
    if (data.apps) state.appCounts = data.apps;
    if (data.screensaver && !state.screenDirty) {
      mergeScreensaver(data.screensaver);
    } else if (!state.screensaver && data.screensaver) {
      state.screensaver = data.screensaver;
    }
    state.screensaverStatus = data.screensaver_status || state.screensaverStatus || null;
    state.prayer = data.prayer || null;
  }

  function mergeScreensaver(next) {
    if (!next) return;
    if (!state.screensaver) {
      state.screensaver = next;
      return;
    }
    // Keep the same object alive so the open editor keeps its reference.
    state.screensaver.version = next.version || 1;
    state.screensaver.mode = next.mode || "prayer";
    state.screensaver.show_clock = next.show_clock !== false;
    state.screensaver.show_date = next.show_date !== false;
    state.screensaver.notes = Array.isArray(next.notes) ? next.notes : [];
    state.screensaver.todos = Array.isArray(next.todos) ? next.todos : [];
  }

  async function passwordLogin(event) {
    event.preventDefault();
    if (!el.pin) return;
    var pin = el.pin.value.replace(/\D/g, "");
    if (pin.length !== 6) {
      el.loginError.textContent = "PIN terdiri dari 6 angka.";
      el.loginRetry.classList.add("hidden");
      el.pin.focus();
      return;
    }
    setLoginBusy(true);
    el.loginError.textContent = "";
    el.loginRetry.classList.add("hidden");
    try {
      await api.login(pin);
      var data = await api.state();
      setLoginBusy(false);
      enterApp(data);
      window.setTimeout(function () {
        toast("Terhubung ke " + shortModel() + ".", "success");
      }, 320);
    } catch (error) {
      setLoginBusy(false);
      el.loginError.textContent = error.message;
      el.loginRetry.classList.remove("hidden");
      el.pin.select();
    }
  }

  function setLoginBusy(busy) {
    if (!el.loginSubmit) return;
    el.loginSubmit.disabled = busy;
    var label = $(".btn-label", el.loginSubmit);
    if (label) label.textContent = busy ? "Memeriksa PIN…" : "Buka kendali";
  }

  async function logout(skipRequest) {
    stopPage();
    if (!skipRequest) {
      if (state.screenDirty && state.screensaver) {
        try { await saveScreenNow(state.screenSaveSeq); } catch (ignored) {}
      }
      try { await api.logout(); } catch (ignored) {}
    }
    state.route = null;
    history.replaceState(null, "", window.location.pathname);
    showLogin("");
  }

  function onUnauthorized() {
    if (state.authenticated) {
      showLogin("Sesi berakhir. Masukkan PIN lagi.");
    }
  }

  // ------------------------------------------------------------------ router

  function parseRoute() {
    var raw = (window.location.hash || "").replace(/^#\/?/, "").trim();
    return ROUTES[raw] ? raw : "home";
  }

  function routeFromHash() {
    if (!state.authenticated) return;
    var route = parseRoute();
    if (route !== state.route || !el.view.firstElementChild) {
      renderRoute(route);
    }
  }

  function go(route) {
    if (!ROUTES[route]) return;
    if (parseRoute() === route) {
      renderRoute(route);
      window.scrollTo({ top: 0, behavior: "smooth" });
      return;
    }
    window.location.hash = "#/" + route;
  }

  function renderRoute(route) {
    if (!state.authenticated) return;
    if (!ROUTES[route]) route = "home";
    stopPage();

    var config = ROUTES[route];
    var template = byId(config.template);
    if (!template) return;

    state.route = route;
    el.pageTitle.textContent = config.title;
    el.pageEyebrow.textContent = config.eyebrow;
    document.title = config.title + " · CyberHome";

    $$("[data-route]").forEach(function (item) {
      item.classList.toggle("active", item.getAttribute("data-route") === route);
    });

    el.view.innerHTML = "";
    el.view.appendChild(template.content.cloneNode(true));
    el.view.style.animation = "none";
    void el.view.offsetWidth;
    el.view.style.animation = "";

    hydrateChrome();

    if (route === "home") initHome();
    else if (route === "remote") initRemote();
    else if (route === "layout") initLayout();
    else if (route === "screensaver") initScreensaver();
    else if (route === "apps") initApps();
    else if (route === "settings") initSettings();
  }

  function stopPage() {
    if (pageCleanup) {
      try { pageCleanup(); } catch (ignored) {}
      pageCleanup = null;
    }
    state.previewReload = null;
    state.screenshotAuto = false;
  }

  function registerCleanup(fn) {
    var previous = pageCleanup;
    pageCleanup = function () {
      if (previous) previous();
      fn();
    };
  }

  // ------------------------------------------------------------------ chrome

  function hydrateChrome() {
    var server = state.server || {};
    var url = server.url || window.location.origin;
    if (el.sideUrl) el.sideUrl.textContent = url.replace(/^https?:\/\//, "");
    if (el.footUrl) el.footUrl.textContent = url.replace(/^https?:\/\//, "");
    if (el.navAppsBadge && state.appCounts) {
      el.navAppsBadge.textContent = String(state.appCounts.visible);
    }
    updateNetPill();
    updateAdbUI();
    if (state.layout) applyAccent(state.layout.accent);
  }

  function updateNetPill() {
    var pill = byId("netPill");
    if (!pill) return;
    var text = $("span:last-child", pill);
    var online = navigator.onLine !== false;
    pill.classList.toggle("is-offline", !online);
    if (text) text.textContent = online ? "LAN" : "OFFLINE";
  }

  function applyAccent(hex) {
    if (!/^#[0-9a-fA-F]{6}$/.test(hex || "")) hex = "#4C8DFF";
    var rgb = hexToRgb(hex);
    var rootStyle = document.documentElement.style;
    rootStyle.setProperty("--accent", hex);
    rootStyle.setProperty("--accent-soft", "rgba(" + rgb.r + "," + rgb.g + "," + rgb.b + ",.13)");
    rootStyle.setProperty("--accent-line", "rgba(" + rgb.r + "," + rgb.g + "," + rgb.b + ",.4)");
    rootStyle.setProperty("--accent-ink", luminance(hex) > 0.62 ? "#07101c" : "#ffffff");
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute("content", "#05080f");
  }

  function hexToRgb(hex) {
    var value = hex.replace("#", "");
    return {
      r: parseInt(value.substring(0, 2), 16),
      g: parseInt(value.substring(2, 4), 16),
      b: parseInt(value.substring(4, 6), 16)
    };
  }

  function luminance(hex) {
    var c = hexToRgb(hex);
    var channels = [c.r, c.g, c.b].map(function (value) {
      var v = value / 255;
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
  }

  function shortModel() {
    var model = (state.device && state.device.model) || "TV";
    return model.length > 24 ? "TV" : model;
  }

  function updateAdbUI() {
    var adb = state.adb || { connected: false, message: "Belum tersambung" };
    var connected = !!adb.connected;
    [byId("adbBadge"), byId("settingsAdbBadge")].forEach(function (badge) {
      if (!badge) return;
      badge.className = "pill " + (connected ? "pill-ok" : "pill-warn");
      badge.innerHTML = '<span class="dot"></span>' + (connected ? "Tersambung" : "Belum");
    });

    var message = byId("adbMessage");
    if (message) {
      message.textContent = connected
        ? (adb.message || "ADB lokal aktif. Remote, keyboard, dan tangkapan layar siap.")
        : (adb.message && adb.message !== "ADB tersambung"
          ? adb.message
          : "Hubungkan ADB sekali untuk membuka remote tombol, keyboard, dan tangkapan layar.");
    }
    var settingsMessage = byId("settingsAdbMessage");
    if (settingsMessage) {
      settingsMessage.textContent = adb.message || (connected ? "ADB tersambung." : "Belum tersambung.");
    }
    var card = $(".adb-card");
    if (card) card.classList.toggle("is-connected", connected);

    [byId("adbConnect"), byId("settingsAdbConnect")].forEach(function (btn) {
      if (!btn) return;
      if (btn.dataset.busy === "1") return;
      btn.innerHTML = connected
        ? '<svg class="ic"><use href="#i-refresh"></use></svg>Periksa ulang ADB'
        : '<svg class="ic"><use href="#i-terminal"></use></svg>Sambungkan ADB';
    });
  }

  async function connectAdb(button) {
    if (button) {
      if (!button.dataset.originalHtml) button.dataset.originalHtml = button.innerHTML;
      button.dataset.busy = "1";
      button.disabled = true;
      button.innerHTML = '<span class="loader-ring" style="width:14px;height:14px;border-width:1.5px;border-top-color:currentColor"></span><span>Menunggu izin di TV…</span>';
    }
    var pending = state.adb || {};
    state.adb = { connected: false, message: "Menunggu izin di TV. Tekan Allow dengan remote." };
    updateAdbUI();
    try {
      var result = await api.probeAdb();
      state.adb = result.adb || state.adb;
      updateAdbUI();
      if (state.adb.connected) {
        toast("ADB tersambung. Remote penuh aktif.", "success");
      } else {
        toast(state.adb.message || "ADB belum tersambung.", "warn");
      }
    } catch (error) {
      if (error.data && error.data.adb) state.adb = error.data.adb;
      else state.adb = pending;
      updateAdbUI();
      toast(error.message, "error");
    } finally {
      if (button) {
        button.dataset.busy = "0";
        button.disabled = false;
        if (button.dataset.originalHtml) button.innerHTML = button.dataset.originalHtml;
        updateAdbUI();
      }
    }
  }

  // ------------------------------------------------------------------- clock

  function startClock() {
    tickClock();
    window.setInterval(tickClock, 10000);
  }

  function tickClock() {
    var now = new Date();
    var time = pad(now.getHours()) + ":" + pad(now.getMinutes());
    var clock = byId("liveClock");
    if (clock) clock.textContent = time;
    var date = byId("liveDate");
    if (date) date.textContent = DAYS_SHORT[now.getDay()] + ", " + now.getDate() + " " + MONTHS_SHORT[now.getMonth()];
    var previewClock = byId("previewClock");
    if (previewClock) previewClock.textContent = time;
    updateSleepPreviewClock();
  }

  function pad(value) {
    return value < 10 ? "0" + value : String(value);
  }

  function greetingDate() {
    var now = new Date();
    return DAYS[now.getDay()] + ", " + now.getDate() + " " + MONTHS[now.getMonth()];
  }

  function themeLabel(theme) {
    if (theme === "light") return "Terang";
    if (theme === "dark") return "Gelap";
    var hour = new Date().getHours();
    var resolved = hour < 6 || hour >= 18 ? "Gelap" : "Terang";
    return "Otomatis · " + resolved;
  }

  function formatUptime(startedAt) {
    if (!startedAt) return "baru aktif";
    var minutes = Math.max(0, Math.floor((Date.now() - startedAt) / 60000));
    if (minutes < 1) return "baru aktif";
    if (minutes < 60) return minutes + " mnt";
    var hours = Math.floor(minutes / 60);
    var rest = minutes % 60;
    return hours + " j" + (rest ? " " + rest + " mnt" : "");
  }

  function formatClockTime(stamp) {
    if (!stamp) return "—";
    var date = new Date(stamp);
    return pad(date.getHours()) + ":" + pad(date.getMinutes());
  }

  // -------------------------------------------------------------------- home

  function initHome() {
    var layout = state.layout;
    var device = state.device || {};
    var server = state.server || {};

    setText("deviceName", shortModel());
    setText("deviceModel", device.model || "—");
    setText("deviceAddress", window.location.host);
    setText("deviceRes", device.width && device.height ? device.width + " × " + device.height : "—");
    setText("androidChip", device.android ? "Android " + device.android : "Android TV");
    setText("themeNow", themeLabel(layout.theme));

    var visible = state.appCounts ? state.appCounts.visible : 0;
    var hidden = state.appCounts ? state.appCounts.hidden : 0;
    setText("statApps", visible);
    setText("statHidden", hidden + " disembunyikan");
    setText("statColumns", layout.columns);
    setText("statRows", Math.ceil(visible / layout.columns) + " baris");
    setText("statTheme", layout.theme === "auto" ? "Otomatis" : themeLabel(layout.theme));
    setText("statPort", ":" + (server.port || "—"));
    setText("statUptime", "aktif · " + formatUptime(server.startedAt));

    updateAdbUI();

    var preview = bindPreview("homePreview", "previewEmpty");
    state.previewReload = preview.reload;
    registerCleanup(preview.stop);

    var adbButton = byId("adbConnect");
    if (adbButton) {
      adbButton.addEventListener("click", function () { connectAdb(adbButton); });
    }
    var previewButton = byId("previewRefresh");
    if (previewButton) {
      previewButton.addEventListener("click", function () {
        previewButton.classList.add("is-spinning");
        preview.reload();
        window.setTimeout(function () { previewButton.classList.remove("is-spinning"); }, 700);
      });
    }

    $$(".quick-key[data-key]").forEach(function (button) {
      button.addEventListener("click", function () {
        sendKey(button.getAttribute("data-key"), button);
      });
    });
    var quickShot = byId("quickScreenshot");
    if (quickShot) {
      quickShot.addEventListener("click", function () {
        closeLightbox();
        openLightbox();
      });
    }

    $$(".shortcut[data-action]").forEach(function (button) {
      button.addEventListener("click", function () {
        openSettingsAction(button.getAttribute("data-action"), button.textContent.trim());
      });
    });
    var wallpaperShortcut = byId("shortcutWallpaper");
    if (wallpaperShortcut) {
      wallpaperShortcut.addEventListener("click", function () {
        state.focusWallpaper = true;
        go("layout");
      });
    }
  }

  function setText(id, value) {
    var node = byId(id);
    if (node) node.textContent = value === null || value === undefined ? "—" : String(value);
  }

  // --------------------------------------------------------------- preview

  function bindPreview(imageId, emptyId) {
    var img = byId(imageId);
    var empty = byId(emptyId);
    if (!img) return { reload: function () {}, stop: function () {} };
    var emptyLabel = empty ? $("span:last-child", empty) : null;
    var loader = empty ? $(".loader-ring", empty) : null;
    var busy = false;
    var stopped = false;

    function reload() {
      if (busy || stopped) return;
      busy = true;
      var probe = new Image();
      probe.onload = function () {
        busy = false;
        if (stopped) return;
        img.src = probe.src;
        img.classList.add("is-loaded");
        if (empty) empty.classList.add("hidden");
      };
      probe.onerror = function () {
        busy = false;
        if (stopped) return;
        if (!img.classList.contains("is-loaded")) {
          if (empty) empty.classList.remove("hidden");
          if (loader) loader.classList.add("hidden");
          if (emptyLabel) emptyLabel.textContent = "Pratinjau belum siap. Coba segarkan.";
        }
      };
      probe.src = api.previewUrl();
    }

    reload();
    var timer = window.setInterval(function () {
      if (document.visibilityState === "visible") reload();
    }, 9000);

    return {
      reload: reload,
      stop: function () {
        stopped = true;
        window.clearInterval(timer);
      }
    };
  }

  // ------------------------------------------------------------------ remote

  function initRemote() {
    var layout = state.layout;

    if (!state.adb || !state.adb.connected) {
      var notice = document.createElement("div");
      notice.className = "notice page-notice";
      notice.innerHTML = '<svg class="ic"><use href="#i-terminal"></use></svg><p>ADB belum tersambung. Tombol remote dan tangkapan layar butuh izin ADB sekali di TV.</p><button type="button" class="btn btn-ghost btn-sm" id="remoteAdbConnect">Sambungkan</button>';
      el.view.insertBefore(notice, el.view.firstElementChild);
      var noticeBtn = byId("remoteAdbConnect");
      if (noticeBtn) {
        noticeBtn.addEventListener("click", function () {
          connectAdb(noticeBtn);
        });
      }
    }

    $$("button[data-key]", el.view).forEach(function (button) {
      button.addEventListener("click", function () {
        sendKey(button.getAttribute("data-key"), button);
      });
    });

    var textForm = byId("textForm");
    if (textForm) {
      textForm.addEventListener("submit", function (event) {
        event.preventDefault();
        var input = byId("textInput");
        var value = input.value;
        if (!value.length) {
          input.focus();
          return;
        }
        var sendButton = byId("textSend");
        setButtonBusy(sendButton, true, "");
        keyQueue = keyQueue.then(function () {
          return api.text(value).then(function () {
            input.value = "";
            toast("Teks dikirim ke TV.", "success");
          });
        }).catch(function (error) {
          handleRemoteError(error);
        }).finally(function () {
          setButtonBusy(sendButton, false, "");
        });
      });
    }

    var shotButton = byId("refreshScreenshot");
    var shotImage = byId("tvScreenshot");
    var shotFrame = byId("screenshotFrame");
    var captureBusy = false;

    function capture(silent) {
      if (captureBusy) return;
      captureBusy = true;
      if (shotFrame) shotFrame.classList.add("is-loading");
      api.screenshot().then(function (blob) {
        if (state.route !== "remote") return;
        var url = URL.createObjectURL(blob);
        if (shotImage.dataset.url) URL.revokeObjectURL(shotImage.dataset.url);
        shotImage.dataset.url = url;
        shotImage.src = url;
        if (shotFrame) shotFrame.classList.add("has-shot");
        if (!silent) toast("Layar TV diperbarui.", "success");
      }).catch(function (error) {
        if (!silent) toast(error.message, "error");
        if (error.data && error.data.adb) {
          state.adb = error.data.adb;
          updateAdbUI();
        }
      }).finally(function () {
        captureBusy = false;
        if (shotFrame) shotFrame.classList.remove("is-loading");
      });
    }

    if (shotButton) shotButton.addEventListener("click", function () { capture(false); });

    var autoToggle = byId("screenshotAuto");
    var shotTimer = null;
    function setAuto(enabled) {
      state.screenshotAuto = enabled;
      if (shotTimer) {
        window.clearInterval(shotTimer);
        shotTimer = null;
      }
      if (enabled) {
        capture(true);
        shotTimer = window.setInterval(function () {
          if (document.visibilityState === "visible") capture(true);
        }, 6000);
      }
    }
    if (autoToggle) {
      autoToggle.checked = state.screenshotAuto;
      autoToggle.addEventListener("change", function () {
        setAuto(autoToggle.checked);
        if (autoToggle.checked) toast("Tangkapan layar otomatis aktif.", "info");
      });
      if (state.screenshotAuto) setAuto(true);
    }

    document.addEventListener("keydown", onRemoteKeyboard);
    registerCleanup(function () {
      if (shotTimer) window.clearInterval(shotTimer);
      document.removeEventListener("keydown", onRemoteKeyboard);
      if (shotImage && shotImage.dataset.url) {
        URL.revokeObjectURL(shotImage.dataset.url);
        shotImage.dataset.url = "";
      }
    });

    if (!autoToggle && layout) { /* layout kept for future checks */ }
  }

  function onRemoteKeyboard(event) {
    if (state.route !== "remote") return;
    if (!el.modal.classList.contains("hidden") || !el.lightbox.classList.contains("hidden")) return;
    var target = event.target;
    if (target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA" ||
        target.tagName === "SELECT" || target.isContentEditable)) {
      return;
    }
    var map = {
      ArrowUp: "up", ArrowDown: "down", ArrowLeft: "left", ArrowRight: "right",
      Enter: "ok", Escape: "back", " ": "play_pause"
    };
    var key = map[event.key];
    if (!key) return;
    event.preventDefault();
    sendKey(key, null);
  }

  function sendKey(key, button) {
    if (button) {
      button.classList.add("is-pressed");
      window.setTimeout(function () { button.classList.remove("is-pressed"); }, 150);
    }
    if (!state.adb || !state.adb.connected) {
      if (!state.adbPromptShown) {
        state.adbPromptShown = true;
        toast("Mencoba menyambungkan ADB. Jika muncul dialog di TV, pilih Allow.", "info");
      }
    }
    keyQueue = keyQueue.then(function () {
      return api.remote(key).then(function (result) {
        if (result && result.adb) {
          state.adb = result.adb;
          updateAdbUI();
        }
      });
    }).catch(function (error) {
      handleRemoteError(error);
    });
  }

  function handleRemoteError(error) {
    toast(error.message, "error");
    if (error.data && error.data.adb) {
      state.adb = error.data.adb;
      updateAdbUI();
    }
  }

  // ------------------------------------------------------------------ layout

  function initLayout() {
    var layout = state.layout;

    applyLayoutToControls(layout);

    var preview = bindPreview("layoutPreview", "layoutPreviewEmpty");
    state.previewReload = preview.reload;
    registerCleanup(preview.stop);

    var refresh = byId("layoutRefresh");
    if (refresh) {
      refresh.addEventListener("click", function () {
        refresh.classList.add("is-spinning");
        preview.reload();
        window.setTimeout(function () { refresh.classList.remove("is-spinning"); }, 700);
      });
    }
    var appsLink = byId("layoutAppsLink");
    if (appsLink) appsLink.addEventListener("click", function () { go("apps"); });

    $$("[data-config]").forEach(function (input) {
      var key = input.getAttribute("data-config");
      var type = input.getAttribute("type");
      if (type === "range") {
        input.addEventListener("input", function () {
          state.layout[key] = Number(input.value);
          updateOutput(key, input.value);
          paintRange(input);
          scheduleSave();
        });
      } else if (type === "checkbox") {
        input.addEventListener("change", function () {
          state.layout[key] = input.checked;
          scheduleSave();
        });
      } else if (type === "text") {
        input.addEventListener("input", function () {
          state.layout[key] = input.value.substring(0, 24);
          scheduleSave();
        });
        input.addEventListener("blur", function () {
          input.value = state.layout.greeting_name;
        });
      }
    });

    $$("#themeSegment button").forEach(function (button) {
      button.addEventListener("click", function () {
        state.layout.theme = button.getAttribute("data-theme-value");
        markTheme(state.layout.theme);
        scheduleSave();
      });
    });

    $$("#accentSwatches .swatch[data-accent]").forEach(function (button) {
      button.addEventListener("click", function () {
        setAccent(button.getAttribute("data-accent"));
      });
    });
    var custom = byId("accentCustom");
    if (custom) {
      custom.addEventListener("input", function () { setAccent(custom.value); });
    }

    setupWallpaper();

    var saveState = byId("saveState");
    if (saveState) {
      saveState.style.cursor = "pointer";
      saveState.title = "Klik untuk menyimpan ulang";
      saveState.addEventListener("click", function () {
        state.saveSeq++;
        saveNow(state.saveSeq);
      });
    }

    var reset = byId("layoutReset");
    if (reset) {
      reset.addEventListener("click", function () { resetLayoutFlow(); });
    }

    if (state.focusWallpaper) {
      state.focusWallpaper = false;
      window.setTimeout(function () {
        var dropzone = byId("wallpaperDrop");
        if (dropzone) {
          dropzone.focus();
          dropzone.scrollIntoView({ behavior: "smooth", block: "center" });
        }
      }, 220);
    }
    if (state.dirty) setSaveState("dirty", "Menyimpan…");
    else setSaveState("saved", "Tersimpan");
  }

  function applyLayoutToControls(layout) {
    $$("[data-config]").forEach(function (input) {
      var key = input.getAttribute("data-config");
      if (input.type === "checkbox") {
        input.checked = !!layout[key];
      } else if (input.type === "range") {
        input.value = layout[key];
        updateOutput(key, layout[key]);
        paintRange(input);
      } else {
        input.value = layout[key] || "";
      }
    });
    markTheme(layout.theme);
    markAccent(layout.accent);
    var custom = byId("accentCustom");
    if (custom) custom.value = /^#[0-9a-fA-F]{6}$/.test(layout.accent) ? layout.accent : "#4C8DFF";
  }

  function updateOutput(key, value) {
    var output = document.querySelector('[data-for="' + key + '"]');
    if (output) output.textContent = value;
  }

  function paintRange(input) {
    var min = Number(input.min || 0);
    var max = Number(input.max || 100);
    var value = Number(input.value);
    var percent = ((value - min) / (max - min)) * 100;
    input.style.setProperty("--fill", Math.max(0, Math.min(100, percent)) + "%");
  }

  function markTheme(theme) {
    $$("#themeSegment button").forEach(function (button) {
      button.classList.toggle("active", button.getAttribute("data-theme-value") === theme);
    });
  }

  function markAccent(accent) {
    var found = false;
    var normalized = (accent || "").toUpperCase();
    $$("#accentSwatches .swatch[data-accent]").forEach(function (button) {
      var active = button.getAttribute("data-accent").toUpperCase() === normalized;
      button.classList.toggle("is-active", active);
      if (active) found = true;
    });
    var custom = $("#accentSwatches .swatch.custom");
    if (custom) custom.classList.toggle("is-active", !found);
  }

  function setAccent(hex) {
    if (!/^#[0-9a-fA-F]{6}$/.test(hex || "")) return;
    var accent = hex.toUpperCase();
    state.layout.accent = accent;
    var custom = byId("accentCustom");
    if (custom) custom.value = accent;
    markAccent(accent);
    applyAccent(accent);
    scheduleSave();
  }

  function scheduleSave() {
    state.dirty = true;
    state.saveSeq++;
    var seq = state.saveSeq;
    setSaveState("dirty", "Menyimpan…");
    window.clearTimeout(state.saveTimer);
    state.saveTimer = window.setTimeout(function () {
      saveNow(seq);
    }, 320);
  }

  async function saveNow(seq) {
    if (!state.dirty && seq !== state.saveSeq) return;
    var snapshot = state.layout;
    try {
      var result = await api.saveLayout(snapshot);
      if (seq !== state.saveSeq) return;
      var saved = result.layout || snapshot;
      if (state.layout && saved) {
        // Keep the same object alive: the controls of the open page hold a reference to it.
        Object.keys(saved).forEach(function (key) {
          state.layout[key] = saved[key];
        });
      } else {
        state.layout = saved;
      }
      state.dirty = false;
      setSaveState("saved", "Tersimpan");
      if (state.previewReload) window.setTimeout(state.previewReload, 250);
      syncAppCounts();
    } catch (error) {
      if (seq !== state.saveSeq) return;
      setSaveState("error", "Gagal menyimpan");
      toast(error.message, "error");
    }
  }

  function setSaveState(kind, text) {
    var node = byId("saveState");
    if (!node) return;
    node.className = "save-state" + (kind === "dirty" ? " is-dirty" : kind === "saved" ? " is-saved" : kind === "error" ? " is-error" : "");
    var label = byId("saveStateText");
    if (label) label.textContent = text;
  }

  async function resetLayoutFlow() {
    var confirmed = await ask({
      eyebrow: "RESET LAYOUT",
      title: "Kembalikan layout awal?",
      text: "Kolom, ukuran, warna, chip header, dan visibilitas aplikasi kembali ke bawaan. Wallpaper tidak dihapus.",
      okLabel: "Reset layout",
      danger: true
    });
    if (!confirmed) return;
    try {
      var result = await api.resetLayout();
      state.layout = result.layout;
      state.dirty = false;
      state.saveSeq++;
      window.clearTimeout(state.saveTimer);
      applyAccent(state.layout.accent);
      hydrateChrome();
      toast("Layout kembali ke bawaan.", "success");
      renderRoute("layout");
    } catch (error) {
      toast(error.message, "error");
    }
  }

  // ------------------------------------------------------------ screensaver

  function initScreensaver() {
    if (!state.screensaver) {
      state.screensaver = {
        version: 1,
        mode: "prayer",
        show_clock: true,
        show_date: true,
        notes: [],
        todos: []
      };
    }
    var config = state.screensaver;
    if (!SCREEN_MODES[config.mode]) config.mode = "prayer";
    config.show_clock = config.show_clock !== false;
    config.show_date = config.show_date !== false;
    if (!Array.isArray(config.notes)) config.notes = [];
    if (!Array.isArray(config.todos)) config.todos = [];
    if (state.noteEditId && !findNote(state.noteEditId)) state.noteEditId = null;

    $$("#screenModeGrid .mode-card").forEach(function (card) {
      card.addEventListener("click", function () {
        selectScreenMode(card.getAttribute("data-mode"));
      });
    });

    $$("[data-screen-toggle]").forEach(function (input) {
      input.addEventListener("change", function () {
        var key = input.getAttribute("data-screen-toggle");
        state.screensaver[key] = input.checked;
        renderSleepPreview();
        scheduleScreenSave();
      });
    });

    bindNoteEditor();
    bindTodoEditor();

    var tvButton = byId("openDreamSettings");
    if (tvButton) {
      tvButton.addEventListener("click", function () {
        openSettingsAction("dream", "Setelan screensaver");
      });
    }
    var saveState = byId("screenSaveState");
    if (saveState) {
      saveState.style.cursor = "pointer";
      saveState.title = "Klik untuk menyimpan ulang";
      saveState.addEventListener("click", function () {
        state.screenSaveSeq++;
        saveScreenNow(state.screenSaveSeq);
      });
    }

    renderScreensaver();
    setScreenSaveState(state.screenDirty ? "dirty" : "saved",
      state.screenDirty ? "Menyimpan…" : "Tersimpan");

    // Refresh the status badge only, in case the TV settings changed meanwhile.
    api.state().then(function (data) {
      if (state.route !== "screensaver") return;
      if (data.screensaver_status) state.screensaverStatus = data.screensaver_status;
      if (data.prayer) state.prayer = data.prayer;
      renderScreenStatus();
      renderSleepPreview();
    }).catch(function () {});
  }

  function selectScreenMode(mode) {
    if (!SCREEN_MODES[mode] || !state.screensaver || state.screensaver.mode === mode) return;
    state.screensaver.mode = mode;
    renderScreensaver();
    scheduleScreenSave();
  }

  function renderScreensaver() {
    if (!state.screensaver) return;
    renderScreenModes();
    renderScreenToggles();
    renderScreenStatus();
    renderNoteList();
    renderTodoList();
    renderSleepPreview();
  }

  function renderScreenModes() {
    var mode = state.screensaver ? state.screensaver.mode : "prayer";
    $$("#screenModeGrid .mode-card").forEach(function (card) {
      var active = card.getAttribute("data-mode") === mode;
      card.classList.toggle("active", active);
      card.setAttribute("aria-checked", active ? "true" : "false");
    });
  }

  function renderScreenToggles() {
    if (!state.screensaver) return;
    $$("[data-screen-toggle]").forEach(function (input) {
      var key = input.getAttribute("data-screen-toggle");
      input.checked = !!state.screensaver[key];
    });
  }

  function renderScreenStatus() {
    var title = byId("screenStatusTitle");
    var copy = byId("screenStatusText");
    var pill = byId("screenStatusPill");
    var pillText = byId("screenStatusPillText");
    if (!title || !copy || !pill) return;
    var status = state.screensaverStatus;
    var mode = state.screensaver ? SCREEN_MODES[state.screensaver.mode] : null;
    var pillClass = "pill-warn";
    var pillLabel = "Memeriksa";

    if (!status) {
      title.textContent = "Memeriksa layar TV…";
      copy.textContent = "Status screensaver dibaca dari Setelan TV.";
    } else if (!status.readable) {
      title.textContent = "Status belum terbaca";
      copy.textContent = "Pengaturan tetap tersimpan di TV. Pilih screensaver CyberHome dari Setelan TV bila perlu.";
      pillLabel = "Tidak terbaca";
    } else if (!status.active) {
      title.textContent = "Belum dipilih di TV";
      copy.textContent = "Screensaver CyberHome belum menjadi screensaver aktif. Pilih dari Setelan TV.";
      pillLabel = "Belum aktif";
    } else if (!status.enabled) {
      title.textContent = "Screensaver dimatikan";
      copy.textContent = "Aktifkan screensaver di Setelan TV agar mode " +
        (mode ? mode.label : "layar") + " muncul saat TV diam.";
      pillLabel = "Nonaktif";
    } else {
      title.textContent = "Screensaver aktif";
      copy.textContent = "Mode " + (mode ? mode.label : "layar") +
        " tampil saat TV diam. Setiap perubahan langsung tersimpan.";
      pillClass = "pill-ok";
      pillLabel = "Aktif";
    }
    pill.className = "pill " + pillClass;
    if (pillText) pillText.textContent = pillLabel;
  }

  function scheduleScreenSave() {
    if (!state.screensaver) return;
    state.screenDirty = true;
    state.screenSaveSeq++;
    var seq = state.screenSaveSeq;
    setScreenSaveState("dirty", "Menyimpan…");
    window.clearTimeout(state.screenSaveTimer);
    state.screenSaveTimer = window.setTimeout(function () {
      saveScreenNow(seq);
    }, 320);
  }

  async function saveScreenNow(seq) {
    if (!state.screensaver) return;
    if (!state.screenDirty && seq !== state.screenSaveSeq) return;
    window.clearTimeout(state.screenSaveTimer);
    var snapshot = {
      mode: state.screensaver.mode,
      show_clock: !!state.screensaver.show_clock,
      show_date: !!state.screensaver.show_date,
      notes: state.screensaver.notes.map(function (note) {
        return { id: note.id, title: note.title || "", body: note.body || "" };
      }),
      todos: state.screensaver.todos.map(function (todo) {
        return { id: todo.id, text: todo.text || "", done: !!todo.done };
      })
    };
    try {
      var result = await api.saveScreensaver(snapshot);
      if (seq !== state.screenSaveSeq) return;
      mergeScreensaver(result.screensaver || snapshot);
      state.screenDirty = false;
      setScreenSaveState("saved", "Tersimpan");
      if (state.route === "screensaver") renderScreensaver();
    } catch (error) {
      if (seq !== state.screenSaveSeq) return;
      setScreenSaveState("error", "Gagal menyimpan");
      toast(error.message, "error");
    }
  }

  function setScreenSaveState(kind, text) {
    var node = byId("screenSaveState");
    if (!node) return;
    node.className = "save-state" + (kind === "dirty" ? " is-dirty"
      : kind === "saved" ? " is-saved" : kind === "error" ? " is-error" : "");
    var label = byId("screenSaveStateText");
    if (label) label.textContent = text;
  }

  // notes

  function bindNoteEditor() {
    var form = byId("noteForm");
    var title = byId("noteTitle");
    var body = byId("noteBody");
    if (!form || !title || !body) return;

    body.addEventListener("input", updateNoteChars);
    updateNoteChars();

    form.addEventListener("submit", function (event) {
      event.preventDefault();
      var titleValue = title.value.trim();
      var bodyValue = body.value.trim();
      var notes = state.screensaver.notes;
      if (!titleValue && !bodyValue) {
        toast("Isi judul atau catatan dulu.", "warn");
        title.focus();
        return;
      }
      if (!state.noteEditId) {
        if (notes.length >= 30) {
          toast("Maksimal 30 catatan.", "warn");
          return;
        }
        notes.unshift({
          id: makeLocalId("n"),
          title: titleValue.slice(0, 80),
          body: bodyValue.slice(0, 600)
        });
        resetNoteForm(true);
        toast("Catatan ditambahkan.", "success");
      } else {
        var note = findNote(state.noteEditId);
        if (!note) {
          resetNoteForm(false);
          toast("Catatan itu sudah tidak ada.", "warn");
          return;
        }
        note.title = titleValue.slice(0, 80);
        note.body = bodyValue.slice(0, 600);
        resetNoteForm(true);
        toast("Catatan diperbarui.", "success");
      }
      renderNoteList();
      renderSleepPreview();
      scheduleScreenSave();
    });

    var cancel = byId("noteCancel");
    if (cancel) {
      cancel.addEventListener("click", function () {
        resetNoteForm(true);
      });
    }
  }

  function updateNoteChars() {
    var body = byId("noteBody");
    if (!body) return;
    setText("noteChars", body.value.length + " / 600");
  }

  function resetNoteForm(focus) {
    state.noteEditId = null;
    var title = byId("noteTitle");
    var body = byId("noteBody");
    if (title) title.value = "";
    if (body) body.value = "";
    setText("noteSubmitLabel", "Tambah catatan");
    var icon = $("#noteSubmit use");
    if (icon) icon.setAttribute("href", "#i-plus");
    var cancel = byId("noteCancel");
    if (cancel) cancel.classList.add("hidden");
    updateNoteChars();
    if (focus && title) title.focus();
  }

  function editNote(id) {
    var note = findNote(id);
    if (!note) return;
    state.noteEditId = id;
    var title = byId("noteTitle");
    var body = byId("noteBody");
    if (title) title.value = note.title;
    if (body) body.value = note.body;
    updateNoteChars();
    setText("noteSubmitLabel", "Simpan perubahan");
    var icon = $("#noteSubmit use");
    if (icon) icon.setAttribute("href", "#i-check");
    var cancel = byId("noteCancel");
    if (cancel) cancel.classList.remove("hidden");
    if (title) {
      title.focus();
      title.scrollIntoView({ behavior: "smooth", block: "center" });
    }
  }

  async function deleteNoteFlow(id) {
    var note = findNote(id);
    if (!note) return;
    var label = (note.title || note.body || "Catatan tanpa judul").slice(0, 90);
    var confirmed = await ask({
      eyebrow: "HAPUS CATATAN",
      title: "Hapus catatan ini?",
      text: "“" + label + "” akan dihapus dari screensaver.",
      okLabel: "Hapus",
      danger: true
    });
    if (!confirmed) return;
    state.screensaver.notes = state.screensaver.notes.filter(function (item) {
      return item.id !== id;
    });
    if (state.noteEditId === id) resetNoteForm(false);
    renderNoteList();
    renderSleepPreview();
    scheduleScreenSave();
    toast("Catatan dihapus.", "success");
  }

  function renderNoteList() {
    if (!state.screensaver) return;
    var list = byId("noteList");
    if (!list) return;
    var notes = state.screensaver.notes;
    setText("noteCountPill", notes.length);
    var empty = byId("noteEmpty");
    if (empty) empty.classList.toggle("hidden", notes.length > 0);

    list.innerHTML = "";
    notes.forEach(function (note, index) {
      var row = document.createElement("div");
      row.className = "note-row" + (index < 6 ? " is-on-screen" : "");

      var order = document.createElement("span");
      order.className = "note-order";
      order.textContent = pad(index + 1);

      var copy = document.createElement("div");
      copy.className = "note-copy";
      var title = document.createElement("strong");
      title.textContent = note.title || "Tanpa judul";
      if (!note.title) title.classList.add("is-placeholder");
      var body = document.createElement("p");
      body.textContent = note.body || "Catatan tanpa isi";
      if (!note.body) body.classList.add("is-placeholder");
      copy.appendChild(title);
      copy.appendChild(body);

      var actions = document.createElement("div");
      actions.className = "note-actions";
      if (index < 6) {
        var badge = document.createElement("span");
        badge.className = "on-screen-badge";
        badge.textContent = "LAYAR";
        actions.appendChild(badge);
      }
      actions.appendChild(miniButton("pencil", "Edit catatan", false, function () {
        editNote(note.id);
      }));
      actions.appendChild(miniButton("trash", "Hapus catatan", false, function () {
        deleteNoteFlow(note.id);
      }));

      row.appendChild(order);
      row.appendChild(copy);
      row.appendChild(actions);
      list.appendChild(row);
    });
  }

  function findNote(id) {
    if (!state.screensaver) return null;
    for (var i = 0; i < state.screensaver.notes.length; i++) {
      if (state.screensaver.notes[i].id === id) return state.screensaver.notes[i];
    }
    return null;
  }

  // to-dos

  function bindTodoEditor() {
    var form = byId("todoForm");
    var input = byId("todoInput");
    if (!form || !input) return;

    form.addEventListener("submit", function (event) {
      event.preventDefault();
      var value = input.value.trim();
      if (!value) {
        input.focus();
        return;
      }
      if (state.screensaver.todos.length >= 60) {
        toast("Maksimal 60 tugas.", "warn");
        return;
      }
      state.screensaver.todos.push({
        id: makeLocalId("t"),
        text: value.slice(0, 160),
        done: false
      });
      input.value = "";
      input.focus();
      renderTodoList();
      renderSleepPreview();
      scheduleScreenSave();
    });

    var clear = byId("todoClearDone");
    if (clear) clear.addEventListener("click", clearDoneTodos);
  }

  function orderedTodos() {
    if (!state.screensaver) return [];
    var pending = [];
    var done = [];
    state.screensaver.todos.forEach(function (todo) {
      (todo.done ? done : pending).push(todo);
    });
    return pending.concat(done);
  }

  function renderTodoList() {
    if (!state.screensaver) return;
    var list = byId("todoList");
    if (!list) return;
    var todos = state.screensaver.todos;
    var ordered = orderedTodos();
    var done = todos.length - ordered.filter(function (todo) { return !todo.done; }).length;

    setText("todoCountPill", todos.length);
    setText("todoMeterText", done + " dari " + todos.length + " selesai");
    setText("todoMeterPct", todos.length ? Math.round((done / todos.length) * 100) + "%" : "0%");
    var fill = byId("todoMeterFill");
    if (fill) fill.style.width = (todos.length ? (done / todos.length) * 100 : 0) + "%";
    var empty = byId("todoEmpty");
    if (empty) empty.classList.toggle("hidden", todos.length > 0);
    var clear = byId("todoClearDone");
    if (clear) clear.classList.toggle("hidden", done === 0);

    var onScreen = {};
    ordered.slice(0, 8).forEach(function (todo) { onScreen[todo.id] = true; });

    list.innerHTML = "";
    ordered.forEach(function (todo) {
      var row = document.createElement("div");
      row.className = "todo-row" + (todo.done ? " is-done" : "");

      var check = document.createElement("button");
      check.type = "button";
      check.className = "todo-check" + (todo.done ? " is-checked" : "");
      check.setAttribute("role", "checkbox");
      check.setAttribute("aria-checked", todo.done ? "true" : "false");
      check.setAttribute("aria-label", todo.done ? "Tandai belum selesai" : "Tandai selesai");
      check.innerHTML = '<svg class="ic"><use href="#i-check"></use></svg>';
      check.addEventListener("click", function () {
        toggleTodo(todo.id);
      });

      var text = document.createElement("span");
      text.className = "todo-text";
      text.textContent = todo.text;

      var actions = document.createElement("div");
      actions.className = "todo-actions";
      if (onScreen[todo.id]) {
        var badge = document.createElement("span");
        badge.className = "on-screen-badge";
        badge.textContent = "LAYAR";
        actions.appendChild(badge);
      }
      actions.appendChild(miniButton("trash", "Hapus tugas", false, function () {
        removeTodo(todo.id);
      }));

      row.appendChild(check);
      row.appendChild(text);
      row.appendChild(actions);
      list.appendChild(row);
    });
  }

  function toggleTodo(id) {
    if (!state.screensaver) return;
    for (var i = 0; i < state.screensaver.todos.length; i++) {
      if (state.screensaver.todos[i].id === id) {
        state.screensaver.todos[i].done = !state.screensaver.todos[i].done;
        break;
      }
    }
    renderTodoList();
    renderSleepPreview();
    scheduleScreenSave();
  }

  function removeTodo(id) {
    if (!state.screensaver) return;
    state.screensaver.todos = state.screensaver.todos.filter(function (todo) {
      return todo.id !== id;
    });
    renderTodoList();
    renderSleepPreview();
    scheduleScreenSave();
    toast("Tugas dihapus.", "success");
  }

  async function clearDoneTodos() {
    if (!state.screensaver) return;
    var done = state.screensaver.todos.filter(function (todo) { return todo.done; }).length;
    if (!done) return;
    var confirmed = await ask({
      eyebrow: "BERSIHKAN TO-DO",
      title: "Hapus " + done + " tugas selesai?",
      text: "Tugas yang belum selesai tetap tersimpan dan tampil di layar.",
      okLabel: "Hapus",
      danger: true
    });
    if (!confirmed) return;
    state.screensaver.todos = state.screensaver.todos.filter(function (todo) {
      return !todo.done;
    });
    renderTodoList();
    renderSleepPreview();
    scheduleScreenSave();
    toast(done + " tugas selesai dihapus.", "success");
  }

  function makeLocalId(prefix) {
    return prefix + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
  }

  // dream preview

  function updateSleepPreviewClock() {
    if (state.route !== "screensaver" || !state.screensaver) return;
    var clock = byId("sleepClock");
    if (!clock) return;
    var now = new Date();
    clock.textContent = pad(now.getHours()) + ":" + pad(now.getMinutes());
    if (!state.screensaver.show_date) return;
    var date = byId("sleepDate");
    if (date) {
      date.textContent = DAYS[now.getDay()] + ", " + now.getDate() + " " +
        MONTHS[now.getMonth()] + " " + now.getFullYear();
    }
    var hijri = byId("sleepHijri");
    if (!hijri) return;
    if (state.screensaver.mode !== "prayer") {
      hijri.textContent = "";
    } else if (state.prayer && state.prayer.hijri) {
      hijri.textContent = state.prayer.today
        ? state.prayer.hijri
        : "Jadwal sholat belum diperbarui";
    } else {
      hijri.textContent = "";
    }
  }

  function renderSleepPreview() {
    var preview = byId("sleepPreview");
    if (!preview || !state.screensaver) return;
    var config = state.screensaver;
    var mode = SCREEN_MODES[config.mode] ? config.mode : "prayer";
    preview.setAttribute("data-sleep-mode", mode);
    preview.classList.toggle("hide-clock", !config.show_clock);
    preview.classList.toggle("hide-date", !config.show_date);
    setText("sleepModeLabel", SCREEN_MODES[mode].label);
    setText("sleepScene", SCREEN_MODES[mode].scene);
    var pill = byId("sleepPill");
    if (pill) pill.textContent = sleepHeaderPill(mode);
    updateSleepPreviewClock();

    var content = byId("sleepContent");
    if (!content) return;
    content.innerHTML = "";
    if (mode === "notes") content.appendChild(buildSleepNotes());
    else if (mode === "todos") content.appendChild(buildSleepTodos());
    else content.appendChild(buildSleepPrayer());
  }

  function sleepHeaderPill(mode) {
    if (mode === "prayer") {
      var city = state.prayer && state.prayer.city ? state.prayer.city.trim() : "";
      return city ? city.toUpperCase() : "LOKASI ANDA";
    }
    if (mode === "notes") {
      var count = state.screensaver.notes.length;
      return count === 0 ? "TANPA CATATAN" : count + " CATATAN";
    }
    var total = state.screensaver.todos.length;
    var done = state.screensaver.todos.filter(function (todo) { return todo.done; }).length;
    if (!total) return "TANPA TUGAS";
    if (done === total) return "SEMUA SELESAI";
    return (total - done) + " TERSISA";
  }

  function buildSleepPrayer() {
    var wrap = document.createElement("div");
    wrap.className = "sleep-prayer-scene";
    var times = state.prayer && Array.isArray(state.prayer.times) ? state.prayer.times : null;
    if (!times || times.length < 5) {
      wrap.appendChild(sleepEmpty("moon", "Jadwal sholat belum siap",
        "Buka jadwal sholat di TV sekali agar waktu tersimpan."));
      return wrap;
    }

    var now = new Date();
    var minute = now.getHours() * 60 + now.getMinutes();
    var next = -1;
    for (var i = 0; i < 5; i++) {
      if (Number(times[i]) > minute) {
        next = i;
        break;
      }
    }
    var stale = state.prayer.today === false;

    var countdown = document.createElement("div");
    countdown.className = "sleep-countdown" + (stale ? " is-stale" : "");
    var dot = document.createElement("span");
    dot.className = "sleep-countdown-dot";
    var label = document.createElement("span");
    if (stale) {
      label.textContent = "Jadwal sholat belum diperbarui";
    } else if (next >= 0) {
      label.textContent = "Menuju " + PRAYER_NAMES[next] + " · " +
        durationLabel(Number(times[next]) - minute) + " lagi";
    } else {
      label.textContent = "Besok Subuh · " +
        durationLabel(1440 - minute + Number(times[0])) + " lagi";
    }
    countdown.appendChild(dot);
    countdown.appendChild(label);
    wrap.appendChild(countdown);

    var strip = document.createElement("div");
    strip.className = "sleep-prayers";
    for (var j = 0; j < 5; j++) {
      var card = document.createElement("div");
      card.className = "sleep-prayer" + (!stale && j === next ? " is-next" : "");
      var name = document.createElement("span");
      name.className = "sleep-prayer-name";
      name.textContent = PRAYER_NAMES[j].toUpperCase();
      var value = document.createElement("strong");
      value.className = "sleep-prayer-time";
      value.textContent = prayerTime(Number(times[j]));
      card.appendChild(name);
      card.appendChild(value);
      strip.appendChild(card);
    }
    wrap.appendChild(strip);

    var bar = document.createElement("div");
    bar.className = "sleep-progress";
    var fill = document.createElement("span");
    fill.style.width = prayerProgress(times, minute, next, stale) + "%";
    bar.appendChild(fill);
    wrap.appendChild(bar);
    return wrap;
  }

  function prayerProgress(times, minute, next, stale) {
    if (stale || !times) return 0;
    var previous;
    var target;
    if (next >= 0) {
      previous = next === 0 ? Number(times[0]) - 300 : Number(times[next - 1]);
      target = Number(times[next]);
    } else {
      previous = Number(times[4]);
      target = 1440 + Number(times[0]);
    }
    var span = target - previous;
    if (span <= 0) return 0;
    return Math.max(0, Math.min(100, ((minute - previous) / span) * 100));
  }

  function buildSleepNotes() {
    var wrap = document.createElement("div");
    wrap.className = "sleep-note-scene";
    var notes = state.screensaver.notes;
    if (!notes.length) {
      wrap.appendChild(sleepEmpty("note", "Belum ada catatan",
        "Tambahkan dari editor Catatan di samping."));
      return wrap;
    }
    var visible = notes.slice(0, 6);
    var grid = document.createElement("div");
    grid.className = "sleep-notes" + (visible.length <= 2 ? " roomy" : "");
    visible.forEach(function (note) {
      var card = document.createElement("div");
      card.className = "sleep-note";
      var title = document.createElement("strong");
      title.className = "sleep-note-title";
      title.textContent = note.title || "Tanpa judul";
      var body = document.createElement("p");
      body.className = "sleep-note-body";
      body.textContent = note.body || "Catatan tanpa isi";
      if (!note.body) body.classList.add("is-placeholder");
      card.appendChild(title);
      card.appendChild(body);
      grid.appendChild(card);
    });
    wrap.appendChild(grid);
    return wrap;
  }

  function buildSleepTodos() {
    var wrap = document.createElement("div");
    wrap.className = "sleep-todo-scene";
    var todos = state.screensaver.todos;
    if (!todos.length) {
      wrap.appendChild(sleepEmpty("todo", "Belum ada tugas",
        "Tambahkan to-do dari editor di samping."));
      return wrap;
    }
    var done = todos.filter(function (todo) { return todo.done; }).length;
    var ordered = orderedTodos();
    var visible = ordered.slice(0, 8);

    var summary = document.createElement("div");
    summary.className = "sleep-todo-summary";
    var left = document.createElement("span");
    left.textContent = done === todos.length
      ? "SEMUA TUGAS SELESAI"
      : (todos.length - done) + " TUGAS TERSISA";
    var right = document.createElement("strong");
    right.textContent = done + " / " + todos.length + " SELESAI";
    summary.appendChild(left);
    summary.appendChild(right);
    wrap.appendChild(summary);

    var bar = document.createElement("div");
    bar.className = "sleep-progress";
    var fill = document.createElement("span");
    fill.style.width = (done / todos.length) * 100 + "%";
    bar.appendChild(fill);
    wrap.appendChild(bar);

    var grid = document.createElement("div");
    grid.className = "sleep-todos" + (visible.length > 4 ? " two-col" : "");
    visible.forEach(function (todo) {
      var row = document.createElement("div");
      row.className = "sleep-todo" + (todo.done ? " is-done" : "");
      var tick = document.createElement("span");
      tick.className = "sleep-todo-tick";
      tick.innerHTML = '<svg class="ic"><use href="#i-check"></use></svg>';
      var text = document.createElement("span");
      text.className = "sleep-todo-text";
      text.textContent = todo.text;
      row.appendChild(tick);
      row.appendChild(text);
      grid.appendChild(row);
    });
    wrap.appendChild(grid);
    return wrap;
  }

  function sleepEmpty(icon, title, copy) {
    var box = document.createElement("div");
    box.className = "sleep-empty";
    var badge = document.createElement("span");
    badge.className = "sleep-empty-badge";
    badge.innerHTML = '<svg class="ic"><use href="#i-' + icon + '"></use></svg>';
    var strong = document.createElement("strong");
    strong.textContent = title;
    var span = document.createElement("span");
    span.textContent = copy;
    box.appendChild(badge);
    box.appendChild(strong);
    box.appendChild(span);
    return box;
  }

  function prayerTime(minutes) {
    if (typeof minutes !== "number" || !isFinite(minutes)) return "--:--";
    var value = ((Math.round(minutes) % 1440) + 1440) % 1440;
    return pad(Math.floor(value / 60)) + ":" + pad(value % 60);
  }

  function durationLabel(totalMinutes) {
    var minutes = Math.max(0, Math.round(totalMinutes));
    var hours = Math.floor(minutes / 60);
    var rest = minutes % 60;
    if (hours > 0) return hours + " j " + rest + " mnt";
    return rest + " mnt";
  }

  // -------------------------------------------------------------- wallpaper

  function setupWallpaper() {
    var dropzone = byId("wallpaperDrop");
    var input = byId("wallpaperInput");
    var remove = byId("wallpaperRemove");
    var progress = byId("wallpaperProgress");
    if (!dropzone || !input) return;

    syncWallpaper();

    dropzone.addEventListener("click", function () { input.click(); });
    dropzone.addEventListener("keydown", function (event) {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        input.click();
      }
    });
    input.addEventListener("change", function () {
      var file = input.files && input.files[0];
      input.value = "";
      if (file) uploadWallpaper(file, progress);
    });
    ["dragenter", "dragover"].forEach(function (name) {
      dropzone.addEventListener(name, function (event) {
        event.preventDefault();
        dropzone.classList.add("is-dragging");
      });
    });
    ["dragleave", "dragend"].forEach(function (name) {
      dropzone.addEventListener(name, function () {
        dropzone.classList.remove("is-dragging");
      });
    });
    dropzone.addEventListener("drop", function (event) {
      event.preventDefault();
      dropzone.classList.remove("is-dragging");
      var file = event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0];
      if (file) uploadWallpaper(file, progress);
    });

    if (remove) {
      remove.addEventListener("click", function () { deleteWallpaperFlow(); });
    }
  }

  function syncWallpaper() {
    var dropzone = byId("wallpaperDrop");
    var thumb = byId("wallpaperThumb");
    var meta = byId("wallpaperMeta");
    var remove = byId("wallpaperRemove");
    if (!dropzone || !thumb) return;
    if (state.wallpaper) {
      dropzone.classList.add("has-image");
      thumb.onerror = function () {
        if (meta) meta.textContent = "Wallpaper tidak bisa dimuat";
      };
      thumb.src = api.wallpaperUrl(state.wallpaperVersion);
      if (meta) meta.textContent = "Wallpaper aktif · tersimpan di TV";
      if (remove) remove.disabled = false;
    } else {
      dropzone.classList.remove("has-image");
      thumb.removeAttribute("src");
      if (meta) meta.textContent = "Belum ada wallpaper";
      if (remove) remove.disabled = true;
    }
  }

  function readImage(file) {
    return new Promise(function (resolve, reject) {
      var url = URL.createObjectURL(file);
      var image = new Image();
      image.onload = function () {
        resolve({ image: image, url: url });
      };
      image.onerror = function () {
        URL.revokeObjectURL(url);
        reject(new Error("Gambar tidak bisa dibaca."));
      };
      image.src = url;
    });
  }

  async function prepareWallpaper(file) {
    if (!/^image\/(jpeg|png|webp)$/.test(file.type)) {
      throw new Error("Gunakan gambar JPG, PNG, atau WebP.");
    }
    if (file.size > 25 * 1024 * 1024) {
      throw new Error("Ukuran gambar maksimal 25 MB.");
    }
    var decoded = await readImage(file);
    var image = decoded.image;
    var width = image.naturalWidth;
    var height = image.naturalHeight;
    URL.revokeObjectURL(decoded.url);
    if (width < 320 || height < 180) {
      throw new Error("Gambar terlalu kecil. Minimal 320 × 180.");
    }

    var targetW = 1920;
    var targetH = 1080;
    var targetRatio = targetW / targetH;
    var sourceRatio = width / height;
    var sourceW = width;
    var sourceH = height;
    var sourceX = 0;
    var sourceY = 0;
    if (sourceRatio > targetRatio) {
      sourceW = Math.round(height * targetRatio);
      sourceX = Math.round((width - sourceW) / 2);
    } else if (sourceRatio < targetRatio) {
      sourceH = Math.round(width / targetRatio);
      sourceY = Math.round((height - sourceH) / 2);
    }

    var canvas = document.createElement("canvas");
    canvas.width = targetW;
    canvas.height = targetH;
    var context = canvas.getContext("2d");
    context.fillStyle = "#05080f";
    context.fillRect(0, 0, targetW, targetH);
    context.imageSmoothingEnabled = true;
    context.imageSmoothingQuality = "high";
    context.drawImage(image, sourceX, sourceY, sourceW, sourceH, 0, 0, targetW, targetH);

    var blob = await new Promise(function (resolve) {
      canvas.toBlob(resolve, "image/jpeg", 0.9);
    });
    if (!blob) throw new Error("Gambar gagal diproses.");
    return blob;
  }

  async function uploadWallpaper(file, progress) {
    if (progress) progress.classList.remove("hidden");
    try {
      var blob = await prepareWallpaper(file);
      await api.uploadWallpaper(blob);
      state.wallpaper = true;
      state.wallpaperVersion = Date.now();
      syncWallpaper();
      if (state.previewReload) window.setTimeout(state.previewReload, 400);
      toast("Wallpaper diterapkan ke TV.", "success");
    } catch (error) {
      toast(error.message, "error");
    } finally {
      if (progress) progress.classList.add("hidden");
    }
  }

  async function deleteWallpaperFlow() {
    var confirmed = await ask({
      eyebrow: "HAPUS WALLPAPER",
      title: "Hapus wallpaper TV?",
      text: "Home akan kembali memakai latar gelap polos. Layout dan aplikasi tidak berubah.",
      okLabel: "Hapus",
      danger: true
    });
    if (!confirmed) return;
    try {
      await api.deleteWallpaper();
      state.wallpaper = false;
      syncWallpaper();
      if (state.previewReload) window.setTimeout(state.previewReload, 300);
      if (byId("settingsResetWallpaper")) updateSettingsCounters();
      toast("Wallpaper dihapus.", "success");
    } catch (error) {
      toast(error.message, "error");
    }
  }

  // -------------------------------------------------------------------- apps

  async function initApps() {
    var search = byId("appSearch");
    if (search) {
      search.value = state.appQuery;
      search.addEventListener("input", function () {
        state.appQuery = search.value.trim().toLowerCase();
        renderAppList();
      });
    }
    $$("#appTabs .tab").forEach(function (tab) {
      tab.addEventListener("click", function () {
        state.appTab = tab.getAttribute("data-tab");
        $$("#appTabs .tab").forEach(function (other) {
          other.classList.toggle("active", other === tab);
        });
        renderAppList();
      });
    });

    var reload = byId("appsReload");
    if (reload) {
      reload.addEventListener("click", function () {
        loadApps(true);
      });
    }
    var layoutLink = byId("appsLayoutLink");
    if (layoutLink) layoutLink.addEventListener("click", function () { go("layout"); });

    if (state.appsLoaded) {
      renderAppList();
    } else {
      showAppLoading();
      await loadApps(false);
    }
  }

  async function loadApps(notify) {
    showAppLoading();
    try {
      var data = await api.apps();
      if (state.route !== "apps") return;
      state.apps.visible = data.visible || [];
      state.apps.hidden = data.hidden || [];
      state.appsLoaded = true;
      state.appCounts = { visible: state.apps.visible.length, hidden: state.apps.hidden.length };
      renderAppList();
      syncAppCounts();
      if (notify) toast("Daftar aplikasi diperbarui.", "success");
    } catch (error) {
      if (state.route !== "apps") return;
      showAppError(error.message);
      toast(error.message, "error");
    }
  }

  function showAppLoading() {
    var list = byId("appList");
    if (!list) return;
    list.innerHTML = '<div class="empty-state"><span class="loader-ring"></span><strong>Membaca aplikasi…</strong><span>Sebentar.</span></div>';
    var empty = byId("appEmpty");
    if (empty) empty.classList.add("hidden");
    var hint = byId("reorderHint");
    if (hint) hint.classList.add("hidden");
  }

  function showAppError(message) {
    var list = byId("appList");
    if (!list) return;
    list.innerHTML = "";
    var empty = byId("appEmpty");
    var text = byId("appEmptyText");
    if (empty) empty.classList.remove("hidden");
    if (text) text.textContent = message || "Coba muat ulang daftar.";
    var hint = byId("reorderHint");
    if (hint) hint.classList.add("hidden");
  }

  function renderAppList() {
    var list = byId("appList");
    if (!list) return;
    var isVisibleTab = state.appTab === "visible";
    var source = isVisibleTab ? state.apps.visible : state.apps.hidden;
    var query = state.appQuery;
    var filtered = source.filter(function (app) {
      if (!query) return true;
      return app.label.toLowerCase().indexOf(query) >= 0 || app.pkg.toLowerCase().indexOf(query) >= 0;
    });

    setText("countVisible", state.apps.visible.length);
    setText("countHidden", state.apps.hidden.length);
    var hint = byId("reorderHint");
    if (hint) {
      hint.classList.toggle("hidden", !isVisibleTab);
      hint.textContent = query
        ? "Hapus pencarian untuk mengubah urutan."
        : "Urutan sama dengan di TV. Geser baris, atau pakai tombol panah.";
    }

    list.innerHTML = "";
    var empty = byId("appEmpty");
    if (empty) empty.classList.toggle("hidden", filtered.length > 0);
    var emptyText = byId("appEmptyText");
    if (emptyText) {
      emptyText.textContent = query ? "Tidak ada aplikasi yang cocok." : isVisibleTab ? "Semua aplikasi disembunyikan." : "Tidak ada aplikasi tersembunyi.";
    }

    var canReorder = isVisibleTab && !query;
    var dragIndex = -1;

    filtered.forEach(function (app) {
      var realIndex = source.indexOf(app);
      var row = document.createElement("div");
      row.className = "app-row" + (isVisibleTab ? "" : " is-hidden");
      row.draggable = canReorder;
      row.innerHTML = '<span class="drag-handle" aria-hidden="true"><svg class="ic"><use href="#i-grip"></use></svg></span>' +
        '<span class="app-icon"><img alt="" loading="lazy"></span>' +
        '<span class="app-meta"><strong></strong><small></small></span>' +
        '<span class="app-actions"></span>';
      var img = $("img", row);
      img.src = api.iconUrl(app.pkg);
      img.alt = app.label;
      $("strong", row).textContent = app.label;
      $("small", row).textContent = app.pkg;
      var handle = $(".drag-handle", row);
      handle.style.visibility = canReorder ? "" : "hidden";
      var actions = $(".app-actions", row);

      if (canReorder) {
        actions.appendChild(miniButton("arrow-up", "Naikkan", realIndex === 0, function () {
          moveApp(realIndex, realIndex - 1);
        }));
        actions.appendChild(miniButton("arrow-down", "Turunkan", realIndex === filtered.length - 1, function () {
          moveApp(realIndex, realIndex + 1);
        }));
      }
      actions.appendChild(miniButton("external", "Buka di TV", false, function () {
        launchApp(app);
      }));
      var hideBtn = miniButton(isVisibleTab ? "eye-off" : "eye", isVisibleTab ? "Sembunyikan" : "Tampilkan", false, function () {
        toggleAppVisibility(app, isVisibleTab);
      });
      hideBtn.classList.add("app-hide");
      actions.appendChild(hideBtn);

      if (canReorder) {
        row.addEventListener("dragstart", function (event) {
          dragIndex = realIndex;
          row.classList.add("dragging");
          if (event.dataTransfer) {
            event.dataTransfer.effectAllowed = "move";
            try { event.dataTransfer.setData("text/plain", app.pkg); } catch (ignored) {}
          }
        });
        row.addEventListener("dragend", function () {
          dragIndex = -1;
          $$(".app-row", list).forEach(function (item) {
            item.classList.remove("dragging", "drag-over");
          });
        });
        row.addEventListener("dragover", function (event) {
          event.preventDefault();
          if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
          row.classList.add("drag-over");
        });
        row.addEventListener("dragleave", function () {
          row.classList.remove("drag-over");
        });
        row.addEventListener("drop", function (event) {
          event.preventDefault();
          row.classList.remove("drag-over");
          if (dragIndex >= 0 && dragIndex !== realIndex) {
            moveApp(dragIndex, realIndex);
          }
        });
      }

      list.appendChild(row);
    });
  }

  function miniButton(icon, label, disabled, onClick) {
    var button = document.createElement("button");
    button.type = "button";
    button.className = "mini-icon-btn";
    button.title = label;
    button.setAttribute("aria-label", label);
    button.disabled = !!disabled;
    button.innerHTML = '<svg class="ic"><use href="#i-' + icon + '"></use></svg>';
    button.addEventListener("click", onClick);
    return button;
  }

  function moveApp(from, to) {
    var list = state.apps.visible;
    if (from < 0 || to < 0 || from >= list.length || to >= list.length) return;
    var item = list.splice(from, 1)[0];
    list.splice(to, 0, item);
    commitApps();
    renderAppList();
  }

  function toggleAppVisibility(app, wasVisible) {
    if (wasVisible) {
      var visibleIndex = state.apps.visible.indexOf(app);
      if (visibleIndex < 0) return;
      state.apps.visible.splice(visibleIndex, 1);
      state.apps.hidden.push(app);
      toast(app.label + " disembunyikan dari Home.", "info");
    } else {
      var hiddenIndex = state.apps.hidden.indexOf(app);
      if (hiddenIndex < 0) return;
      state.apps.hidden.splice(hiddenIndex, 1);
      state.apps.visible.push(app);
      toast(app.label + " ditampilkan di Home.", "info");
    }
    commitApps();
    renderAppList();
  }

  function commitApps() {
    var layout = state.layout;
    layout.hidden_apps = state.apps.hidden.map(function (app) { return app.pkg; });
    layout.order = state.apps.visible.map(function (app) { return app.pkg; })
      .concat(state.apps.hidden.map(function (app) { return app.pkg; }));
    state.appCounts = { visible: state.apps.visible.length, hidden: state.apps.hidden.length };
    syncAppCounts();
    scheduleSave();
  }

  function syncAppCounts() {
    var counts = state.appCounts || { visible: state.apps.visible.length, hidden: state.apps.hidden.length };
    if (el.navAppsBadge) el.navAppsBadge.textContent = String(counts.visible);
    setText("countVisible", counts.visible);
    setText("countHidden", counts.hidden);
    setText("statApps", counts.visible);
    setText("statHidden", counts.hidden + " disembunyikan");
    setText("statRows", Math.ceil(counts.visible / (state.layout ? state.layout.columns : 5)) + " baris");
  }

  async function launchApp(app) {
    try {
      await api.launchApp(app.pkg);
      toast("Membuka " + app.label + " di TV.", "success");
    } catch (error) {
      toast(error.message, "error");
    }
  }

  // ---------------------------------------------------------------- settings

  function initSettings() {
    var server = state.server || {};
    var device = state.device || {};
    var port = server.port || (window.location.port ? Number(window.location.port) : 8787);
    var url = server.url || window.location.origin;

    setText("serverUrl", url);
    setText("serverStatus", server.running === false ? "Berhenti" : "Aktif · hanya LAN");
    setText("serverStarted", formatClockTime(server.startedAt));
    setText("settingsModel", device.model || "—");
    setText("settingsAndroid", device.android || "—");
    setText("settingsBuild", device.build || "—");
    setText("settingsRes", device.width && device.height ? device.width + " × " + device.height : "—");
    updateSettingsCounters();
    updateAdbUI();

    var portInput = byId("portInput");
    if (portInput) portInput.value = port;
    var portForm = byId("portForm");
    if (portForm) {
      portForm.addEventListener("submit", async function (event) {
        event.preventDefault();
        var requested = Number(portInput.value);
        if (!requested || requested < 1024 || requested > 65535) {
          toast("Port harus antara 1024 dan 65535.", "warn");
          portInput.focus();
          return;
        }
        if (requested === port) {
          toast("Itu port yang sedang dipakai.", "warn");
          return;
        }
        var submit = $('button[type="submit"]', portForm);
        setButtonBusy(submit, true, "Memindah…");
        try {
          var result = await api.changePort(requested);
          toast("Server pindah ke port " + result.port + ". Membuka alamat baru…", "success");
          window.setTimeout(function () {
            window.location.href = window.location.protocol + "//" + window.location.hostname + ":" + requested + "/";
          }, 1900);
        } catch (error) {
          toast(error.message, "error");
          setButtonBusy(submit, false, "");
        }
      });
    }

    var copy = byId("copyUrl");
    if (copy) {
      copy.addEventListener("click", async function () {
        var ok = await copyText(url);
        toast(ok ? "Alamat disalin." : "Tidak bisa menyalin. Salin manual: " + url, ok ? "success" : "warn");
      });
    }

    var restart = byId("restartServer");
    if (restart) {
      restart.addEventListener("click", async function () {
        var confirmed = await ask({
          eyebrow: "MULAI ULANG",
          title: "Mulai ulang server?",
          text: "Halaman ini akan kehilangan koneksi sebentar lalu dimuat ulang otomatis.",
          okLabel: "Mulai ulang"
        });
        if (!confirmed) return;
        try {
          await api.restartServer();
          toast("Server dimulai ulang. Menyambung kembali…", "info");
          window.setTimeout(function () { window.location.reload(); }, 2400);
        } catch (error) {
          toast(error.message, "error");
        }
      });
    }

    var adbButton = byId("settingsAdbConnect");
    if (adbButton) {
      adbButton.addEventListener("click", function () { connectAdb(adbButton); });
    }
    var developer = byId("openDeveloper");
    if (developer) {
      developer.addEventListener("click", function () {
        openSettingsAction("developer", "Setelan pengembang");
      });
    }

    var pinForm = byId("pinForm");
    if (pinForm) {
      pinForm.addEventListener("submit", async function (event) {
        event.preventDefault();
        var current = byId("pinCurrent").value.replace(/\D/g, "");
        var next = byId("pinNext").value.replace(/\D/g, "");
        var confirm = byId("pinConfirm").value.replace(/\D/g, "");
        var errorNode = byId("pinError");
        errorNode.textContent = "";
        if (current.length !== 6 || next.length !== 6) {
          errorNode.textContent = "PIN lama dan PIN baru harus 6 angka.";
          return;
        }
        if (next !== confirm) {
          errorNode.textContent = "Ulangi PIN baru dengan tepat.";
          return;
        }
        if (next === current) {
          errorNode.textContent = "PIN baru sama dengan PIN lama.";
          return;
        }
        var submit = byId("pinSubmit");
        setButtonBusy(submit, true, "Menyimpan…");
        try {
          await api.changePin(current, next);
          byId("pinCurrent").value = "";
          byId("pinNext").value = "";
          byId("pinConfirm").value = "";
          toast("PIN berhasil diperbarui. Sesi ini tetap aktif.", "success");
        } catch (error) {
          errorNode.textContent = error.message;
        } finally {
          setButtonBusy(submit, false, "");
        }
      });
    }

    var logoutButton = byId("settingsLogout");
    if (logoutButton) {
      logoutButton.addEventListener("click", function () { logout(false); });
    }
    var resetLayout = byId("settingsResetLayout");
    if (resetLayout) resetLayout.addEventListener("click", function () { resetLayoutFlow(); });
    var resetWallpaper = byId("settingsResetWallpaper");
    if (resetWallpaper) resetWallpaper.addEventListener("click", function () { deleteWallpaperFlow(); });
  }

  function updateSettingsCounters() {
    var counts = state.appCounts || { visible: state.apps.visible.length, hidden: state.apps.hidden.length };
    setText("settingsApps", counts.visible + " tampil · " + counts.hidden + " tersembunyi");
  }

  async function openSettingsAction(action, label) {
    try {
      await api.action(action);
      toast("Membuka " + (label || action) + " di TV.", "success");
    } catch (error) {
      toast(error.message, "error");
    }
  }

  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text).then(function () { return true; }).catch(function () {
        return legacyCopy(text);
      });
    }
    return Promise.resolve(legacyCopy(text));
  }

  function legacyCopy(text) {
    var area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.top = "-1000px";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    var ok = false;
    try { ok = document.execCommand("copy"); } catch (ignored) {}
    area.remove();
    return ok;
  }

  // -------------------------------------------------------------- global UI

  function bindGlobal() {
    el.loginForm.addEventListener("submit", passwordLogin);
    el.pin.addEventListener("input", function () {
      el.pin.value = el.pin.value.replace(/\D/g, "").slice(0, 6);
    });
    el.loginRetry.addEventListener("click", function () {
      el.loginError.textContent = "";
      el.loginRetry.classList.add("hidden");
      el.pin.focus();
    });

    $$("[data-route]").forEach(function (button) {
      button.addEventListener("click", function () {
        var route = button.getAttribute("data-route");
        if (button.tagName === "A") return;
        go(route);
      });
    });

    var refresh = byId("refreshBtn");
    if (refresh) refresh.addEventListener("click", function () { refreshAll(refresh); });

    var power = byId("powerBtn");
    if (power) power.addEventListener("click", function () { powerFlow(power); });

    var logoutButton = byId("logoutBtn");
    if (logoutButton) logoutButton.addEventListener("click", function () { logout(false); });

    var modalOk = byId("modalOk");
    if (modalOk) modalOk.addEventListener("click", function () { closeModal(true); });
    var modalCancel = byId("modalCancel");
    if (modalCancel) modalCancel.addEventListener("click", function () { closeModal(false); });
    $$("[data-modal-cancel]").forEach(function (node) {
      node.addEventListener("click", function () { closeModal(false); });
    });

    $$("[data-lightbox-close]").forEach(function (node) {
      node.addEventListener("click", closeLightbox);
    });

    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape") {
        if (!el.lightbox.classList.contains("hidden")) {
          closeLightbox();
          event.preventDefault();
        } else if (!el.modal.classList.contains("hidden")) {
          closeModal(false);
          event.preventDefault();
        }
      }
    });

    window.addEventListener("hashchange", routeFromHash);
    window.addEventListener("cyber:unauthorized", onUnauthorized);
    window.addEventListener("online", updateNetPill);
    window.addEventListener("offline", updateNetPill);
    window.addEventListener("beforeunload", function (event) {
      if ((state.dirty || state.screenDirty) && state.authenticated) {
        event.preventDefault();
        event.returnValue = "";
      }
    });
  }

  async function refreshAll(button) {
    if (button) {
      button.disabled = true;
      button.classList.add("is-spinning");
    }
    try {
      var data = await api.state();
      applyState(data);
      hydrateChrome();
      renderRoute(state.route || parseRoute());
      toast("Data TV diperbarui.", "success");
    } catch (error) {
      toast(error.message, "error");
    } finally {
      if (button) {
        button.disabled = false;
        button.classList.remove("is-spinning");
      }
    }
  }

  async function powerFlow(button) {
    var confirmed = await ask({
      eyebrow: "DAYA TV",
      title: "Kirim tombol daya?",
      text: "TV akan tidur atau bangun, tergantung kondisinya sekarang. Pastikan kamu masih bisa menyalakannya dengan remote.",
      okLabel: "Kirim daya"
    });
    if (!confirmed) return;
    sendKey("power", button);
  }

  function ask(options) {
    if (modalResolve) closeModal(false);
    el.modalEyebrow.textContent = options.eyebrow || "KONFIRMASI";
    el.modalTitle.textContent = options.title || "Lanjutkan?";
    el.modalText.textContent = options.text || "";
    el.modalOk.textContent = options.okLabel || "Lanjut";
    el.modalOk.className = options.danger ? "btn btn-danger-ghost" : "btn btn-primary";
    el.modalCancel.textContent = options.cancelLabel || "Batal";
    el.modal.classList.remove("hidden");
    window.setTimeout(function () { el.modalOk.focus(); }, 60);
    return new Promise(function (resolve) {
      modalResolve = resolve;
    });
  }

  function closeModal(result) {
    if (el.modal.classList.contains("hidden")) return;
    el.modal.classList.add("hidden");
    var resolve = modalResolve;
    modalResolve = null;
    if (resolve) resolve(!!result);
  }

  function openLightbox() {
    el.lightboxImage.removeAttribute("src");
    el.lightboxImage.style.display = "none";
    el.lightboxLoader.classList.remove("hidden");
    var hint = $(".lightbox-body .hint");
    if (hint) hint.textContent = "Mengambil layar TV…";
    el.lightbox.classList.remove("hidden");
    api.screenshot().then(function (blob) {
      var url = URL.createObjectURL(blob);
      if (state.lastShotUrl) URL.revokeObjectURL(state.lastShotUrl);
      state.lastShotUrl = url;
      el.lightboxImage.src = url;
      el.lightboxImage.style.display = "block";
      el.lightboxLoader.classList.add("hidden");
      if (hint) hint.textContent = "Gambar diambil langsung dari layar TV saat ini.";
    }).catch(function (error) {
      el.lightboxLoader.classList.add("hidden");
      if (hint) hint.textContent = error.message;
      toast(error.message, "error");
    });
  }

  function closeLightbox() {
    if (!el.lightbox || el.lightbox.classList.contains("hidden")) return;
    el.lightbox.classList.add("hidden");
    el.lightboxImage.removeAttribute("src");
    if (state.lastShotUrl) {
      URL.revokeObjectURL(state.lastShotUrl);
      state.lastShotUrl = null;
    }
  }

  function setButtonBusy(button, busy, label) {
    if (!button) return;
    if (!button.dataset.originalHtml) button.dataset.originalHtml = button.innerHTML;
    button.disabled = busy;
    if (busy) {
      button.innerHTML = '<span class="loader-ring" style="width:14px;height:14px;border-width:1.5px;border-top-color:currentColor"></span>' +
        (label ? "<span>" + label + "</span>" : "");
    } else {
      button.innerHTML = button.dataset.originalHtml;
    }
  }

  function toast(message, type) {
    var stack = byId("toasts");
    if (!stack) return;
    var kind = type || "info";
    var icons = { success: "check", error: "x", warn: "info", info: "info" };
    var node = document.createElement("div");
    node.className = "toast " + kind;
    node.innerHTML = '<span class="toast-icon"><svg class="ic"><use href="#i-' + (icons[kind] || "info") + '"></use></svg></span><span class="toast-msg"></span>';
    $(".toast-msg", node).textContent = message;
    stack.appendChild(node);
    while (stack.children.length > 3) {
      stack.removeChild(stack.firstElementChild);
    }
    window.setTimeout(function () {
      node.classList.add("is-out");
      window.setTimeout(function () {
        if (node.parentNode) node.parentNode.removeChild(node);
      }, 260);
    }, 4200);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
