# Validation — Android 0.2.0 development alpha

Verified locally on 13 November 2026:

- `:app:assembleDebug` succeeded: native development APK compiled and packaged.
- `:app:lintDebug` succeeded with no errors. Remaining warnings are an unnecessary SDK-version guard and a widget-background overdraw heuristic; no baseline or required-check suppression was added.
- `:app:testDebugUnitTest`: three JUnit regression suites passed, containing 63 finance, 75 notification-policy and 157 simulated Google Sheets checks.
- Root `npm test`: 12 tests passed.
- APK signature verification passed.
- Gradle 8.9 distribution SHA-256 verified and pinned in wrapper properties.
- Review fixes include Android 11-compatible money thresholds, source isolation/replacement confirmation, lifecycle guards, off-thread encrypted reads and report calculations, bounded presentation lists, export interruption handling and stale-coaching checks.

Not yet verified:

- A real Android phone launch/install: adb currently reports no connected devices.
- Live Google authorization/selected-Sheet retrieval: dedicated Google Cloud project setup is authorized but Cloud Console access is unavailable in this environment. The exact APK signing certificate and package must be registered before connection works.
- Native encrypted-data restoration, widget rendering, permission dialogs and background scheduling on a real device.
- Optional provider AI call: no user API key or paid request was used during implementation.
- Public GitHub publication: connector cannot create repositories and browser sign-in was rejected by GitHub. Source is committed locally, not claimed published.

Google Sheets tests use only simulated HTTPS responses. They do not substitute for live OAuth or device acceptance. This artifact is a development alpha, not a production-release certification. Source rows that change under an existing transaction ID currently stop sync for review; an explicit changed-row reconciliation UI remains future work.
