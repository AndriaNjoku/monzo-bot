# Monzo Bot (working name)

An independent, unofficial, open-source budgeting app for Monzo transaction exports. Not affiliated with or endorsed by Monzo Bank. No bank access, payments or account changes.

## Android app

The native Android application is in [android/](android/README.md). It includes Google Sheets read-only authorization and selected-sheet retrieval, encrypted on-device persistence, period-specific budgets, charts, category corrections, proactive local notifications, quiet hours, a home-screen widget, private export/delete controls, and optional aggregate-only AI coaching with a user-supplied API key.

**Status:** development alpha. Compilation and deterministic tests are separate from live Google authorization and device acceptance. Google requires a configured Cloud project and an Android OAuth registration matching the APK package and signing certificate. Installing an unregistered APK will not make Google connectivity work. See [Android setup](android/README.md) and [privacy](docs/PRIVACY.md).

Financial data is stored locally using Android Keystore AES-GCM and atomic writes; Android backups are disabled. Access tokens stay in memory and are obtained through Google Identity Services. Source spreadsheets stay private. The app does not list Drive or write source cells. The current permission is `spreadsheets.readonly`, which grants broad Sheets read permission at Google; the app accesses only the file explicitly selected by the user.

## Features and boundaries

- Exact integer-pence calculations; GBP account currency; original source evidence preserved separately from category corrections.
- Strict CSV and native-Sheets imports; idempotent transaction IDs; conflicting source edits fail atomically for review rather than silently replacing evidence.
- Calendar-month or custom pay-cycle windows, category charts, money in/out and net movement. Net movement is not an account balance.
- Period-specific category budgets, explicit previous-month template copy, configurable threshold and over-budget alerts.
- Hourly Android WorkManager sync when permitted by network/battery/OS. This is local background scheduling, not a remote push server or real-time feed.
- Generic notifications with private lock-screen version, six-hour global cooldown, three-per-day cap, stable event deduplication and quiet hours. Future/unknown balances and commitments are never claimed.
- Home-screen widget deliberately hides financial amounts and opens the private dashboard.
- Source coverage remains unknown: successful retrieval and latest transaction do not prove current bank data. The app shows retrieval age and uncertainty.
- Optional AI sends only previewed period totals and alert count to the fixed OpenAI endpoint after explicit in-app consent. No names, IDs, raw transactions, categories, notes, receipts or addresses. Key is used in memory for that request and never saved. Model choice and provider charges belong to the user.
- No recurring-payment guarantee, payment execution, connected bank balance, native Mac app, Firebase service, paid hosted CI or cloud financial database.

Only synthetic test data is committed. Never place financial exports, credentials, signing keys or OAuth user identifiers in this repository. App name/branding needs review before a public product launch.

## Original local web prototype

The root web prototype remains available for comparison; it is separate from the native Android app and keeps imports only in tab memory.

```sh
npm test
npm start
```

Requires Node 22+ for tests and Python 3 for the loopback static server. Open http://127.0.0.1:8080 and select **Explore synthetic demo**. No package installation needed. Root-browser notifications work only while that page is open. Do not expose the server publicly or put exports in the served directory.

## Development

MIT licensed. Android dependencies and Gradle are pinned; build and validation instructions are in [android/README.md](android/README.md). Portable synthetic domain and Sheets-response tests live in `android/domain-tests` and `android/sync-tests`. Hosted CI and automatic deployments are intentionally absent.
