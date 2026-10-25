# Third-party notices

This project builds on the work below. Full license texts are in the [`licenses/`](licenses/)
directory.

| Component | Version | License | Copyright / author | Used in |
|---|---|---|---|---|
| [CleanYourTV](https://github.com/Gathin23/cleanyourtv) | upstream reference | MIT | Copyright (c) 2026 Gathin | Project inspiration and lineage; see [`ATTRIBUTION.md`](ATTRIBUTION.md) |
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | 2.3.1 | BSD-3-Clause | Copyright (c) 2012-2013 Paul S. Hawke; 2001, 2005-2013 Jarno Elonen; 2010 Konstantinos Togias | Embedded HTTP server |
| [Dadb](https://github.com/mobile-dev-inc/dadb) | 2.0.0 | Apache-2.0 | mobile-dev-inc | Loopback ADB client |
| [Outfit](https://github.com/Outfitio/Outfit-Fonts) | font file in `app/src/main/assets/web/` | SIL OFL 1.1 | Copyright 2021 The Outfit Project Authors | Web interface typography |

## License texts

- [`licenses/CleanYourTV-MIT.txt`](licenses/CleanYourTV-MIT.txt) — MIT license of the original project
- [`licenses/NanoHTTPD-BSD-3-Clause.txt`](licenses/NanoHTTPD-BSD-3-Clause.txt) — NanoHTTPD BSD-3-Clause
- [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) — Apache License 2.0 (Dadb)
- [`licenses/Dadb-Apache-2.0.txt`](licenses/Dadb-Apache-2.0.txt) — Apache License 2.0 as distributed by Dadb
- [`licenses/OFL-1.1.txt`](licenses/OFL-1.1.txt) — SIL Open Font License 1.1, including the Outfit copyright line

## Build tooling

Gradle, the Gradle wrapper and the Android Gradle Plugin are build-time tooling and are not part of
the application's runtime notices. Android SDK components are used under their own Android SDK
license terms.

## Notes

- Outfit is distributed unmodified as `outfit.ttf`; it is not sold separately.
- NanoHTTPD and Dadb are pulled from Maven Central at build time. Their versions are declared in
  [`app/build.gradle`](app/build.gradle).
- No analytics, advertising, crash-reporting or telemetry SDK is bundled.
