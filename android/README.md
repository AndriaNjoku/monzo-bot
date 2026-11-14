# Native Android app

Java/native Android UI; minimum Android 11 (API 30), compile/target API 35. Development alpha, independent and unofficial. No affiliation with Monzo.

## Build

Use a full JDK 17 or 21, Android platform 35 and build tools 35.0.0. Accept the Android SDK license only with the account owner's authorization. The Gradle wrapper uses pinned Gradle 8.9 and Android Gradle Plugin 8.7.3.

```sh
cd android
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

The development APK is `app/build/outputs/apk/debug/app-debug.apk`. Inspect the actual APK signing certificate before OAuth setup:

```sh
apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

Application ID is `org.monzobot.app`. Debug builds are for device testing, not a production release. Keep the local debug key private; a different signing key needs its own OAuth registration and cannot update an existing installed app signed by another key. Use an intentionally managed private release key before public APK distribution.

## Required Google setup

This is an external-account configuration requirement, not a client secret to paste into source.

1. Create the owner-authorized dedicated Google Cloud project. Do not enable billing or agree to new terms without the owner's authorization.
2. Enable the Google Sheets API. Configure Google Auth Platform branding, support contact, privacy policy and consent audience accurately.
3. For a personal testing alpha, keep external consent in Testing and add the actual intended test account in the private Google Console, never in this repo.
4. Register an **Android OAuth client** with application ID `org.monzobot.app` and the SHA-1 fingerprint printed from the exact APK to be installed. An Android client secret is not embedded in the app. This device-only implementation does not request server offline tokens or need a backend/web client secret.
5. Configure the `https://www.googleapis.com/auth/spreadsheets.readonly` scope. Google grants broad read access to Sheets; the app discloses this and only reads the selected ID. It does not enumerate Drive. Public rollout may require Google's verification process for the requested scopes and audience.
6. Install the matching APK. Tap Connect Google and grant read-only access. Paste the private native Google Sheet link, tap Sync, review the imported dates/categories, set category budgets and explicitly enable notifications.

Google authorization will fail until registration, API enablement, consent audience and signing certificate agree. Do not make the financial spreadsheet public to work around an authorization failure. Google services and a network connection are required for sync.

Official setup: https://developer.android.com/identity/authorization

## Device install and acceptance

With a user-authorized connected Android phone and adb:

```sh
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A connected device must appear before claiming installation. Alternatively the user can download the private development APK and install it using their phone's permitted install flow. This environment cannot assume USB access to the user's phone.

Validate on the actual phone: cold launch; demo; CSV rejection and source isolation; Google consent cancellation; live selected-Sheet sync; totals against a trusted source; permission denial/grant; process restart and encrypted-data restore; scheduled background work; quiet hours; no duplicate notifications; widget; source replacement confirmation; private export; local deletion; disconnect. No real data or screenshots containing it belong in the public repository.

## Deterministic tests

Root `npm test` runs the original 12 web-core tests. Native finance and notification tests have no Android dependency:

```sh
mkdir -p /tmp/monzo-domain-tests
javac -d /tmp/monzo-domain-tests app/src/main/java/org/monzobot/app/Finance.java app/src/main/java/org/monzobot/app/NotificationPolicy.java domain-tests/FinanceTest.java domain-tests/NotificationPolicyTest.java
java -cp /tmp/monzo-domain-tests FinanceTest ../fixtures/synthetic.csv
java -cp /tmp/monzo-domain-tests NotificationPolicyTest
```

Sheets client tests use the standard org.json JVM library as a test-only classpath dependency; it is provided by Android in the actual app. `sync-tests/ClientChecks.java` simulates HTTPS responses without network/private data. Compile it with GoogleSheetsClient.java and run `org.monzobot.app.ClientChecks`. Test-library source: https://github.com/stleary/JSON-java (test dependency is not vendored).

## Data and notification behavior

UK-locale native Sheets only for now; GBP account currency; 50,000 grid rows, 26 columns and 10 MB response limits. Imports preserve evidence and reject changed existing IDs pending explicit reconciliation work. Category splits, pot transfers, credits and upcoming bills are not guessed. The companion Activity searches and pages through all imported rows; planning periods affect budgets only. Source emojis and original/local currency fields remain available in transaction details.

On-device data is AES-GCM encrypted with an Android Keystore key and written atomically. Google tokens stay in memory. AI keys are not saved by default; explicit remembering uses a separate Android Keystore AES-GCM credential file, outside the financial JSON export. Delete clears both stores. Local exported JSON is plaintext only after a warning and destination selection. Delete removes the encrypted store/key and cancels background work. Disconnect stops access on this device; previously granted Google permissions can also be revoked in the user's Google Account. Original Sheets are never changed.

WorkManager runs are inexact; they can be delayed by battery/network restrictions. Default checks are hourly after a successful connection/sync, with user-controlled notifications, six-hour cooldown and daily cap three. Source coverage remains unknown, so external notices are generic freshness check-ins; actual calculations are inside the app. This is not a guaranteed direct-debit reminder or remote push service.

Optional AI is user-triggered, bounded and uses read-only local tools with per-question provider consent and separate label-sharing opt-in. The suggested model ID is editable and is not a price/availability promise. Facts are host-resolved from actual local tool output; qualitative interpretation remains unverified. No AI call is needed for budgets, imports or notifications.

The new launcher is CompanionActivity. It opens the existing MainActivity for connection/import controls, without replacing its Google authorization implementation. Notification/widget taps open the companion; source-token and event checks keep stale notifications from presenting another workspace as current. See docs/NOTIFICATIONS.md.

Local JVM Android UI tests use Robolectric with synthetic data and a fake financial store. They exercise navigation, old-history search, budget creation, consent gating and private notification settings. They do not verify real Google authorization, Android Keystore hardware behavior, actual notification delivery or a connected Pixel.
