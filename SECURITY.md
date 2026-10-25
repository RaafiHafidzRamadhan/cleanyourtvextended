# Security policy

## Supported versions

Only the latest commit on the `main` branch is supported. This is a personal, experimental project;
older builds may not receive security fixes.

## Reporting a vulnerability

Please do **not** open a public issue for a security problem. Use GitHub's private reporting flow:

1. Open the repository's **Security** tab.
2. Choose **Report a vulnerability** (GitHub Security Advisories).

Include the affected file or feature, a description of the impact, and reproduction steps. Avoid
including real IP addresses, Wi-Fi names, PINs, ADB keys or other personal data. If private
reporting is unavailable, contact the maintainer through their GitHub profile.

## Security model

- The web remote is meant for a trusted home LAN, not the public internet.
- The embedded server listens on port **8787** by default (falls back to the next free ports) and
  serves plain **HTTP**. There is no TLS.
- Requests are accepted only from private, loopback, link-local, CGNAT and IPv6 ULA addresses.
  This gate is not a substitute for a firewall: never port-forward the TV.
- Login uses a 6-digit PIN stored on the TV. Sessions are random tokens held in memory, expire
  after 7 days, are capped at 16, and are cleared by an app/TV restart or a PIN change.
- Login attempts are limited to 8 failures per client address in a 5-minute window.
- State-changing API calls require a session, an `X-Cyber-Client: 1` header and a same-origin
  check.
- ADB is used only over the TV's own loopback interface (`127.0.0.1:5555`). The app generates its
  own RSA key in app-private storage. The TV must still show and accept its normal ADB
  authorization prompt.
- The app has `allowBackup="false"`; notes, tasks, PIN, wallpaper and the ADB key are not included
  in Android backups.
- No analytics, accounts, cloud service or telemetry are used. Prayer, weather and approximate
  location calls go directly to the public APIs listed in the README.

## Known limitations

- Anyone on the same LAN who obtains the PIN can control the TV.
- ADB on port 5555 is plaintext and grants powerful control. Disable network ADB when you are done.
- Release builds in this repository currently use the debug signing key and are not suitable for
  public APK distribution.
- The project has been tested on a limited set of hardware. Treat it as experimental.
