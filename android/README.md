# Android host

This is the Kotlin host for the React Native/TypeScript application at the repository root. Run `npm ci` at root, start Metro with `npm start`, and run `npm run android` or open this folder in Android Studio. All application screens and financial/AI logic are TypeScript; Kotlin supplies device integrations.

Use the original signing key for an in-place Pixel update. The build never generates a replacement key; it reuses an already-existing user debug key for debug only or explicit original-keystore Gradle properties. Without one the APK is unsigned. Release signing must be explicitly configured privately. Do not uninstall or clear the app to address a signing mismatch.

Native validation: `./gradlew testDebugUnitTest lintDebug` (Windows `gradlew.bat`). See root README and docs/MIGRATION.md for storage compatibility and unverified device/service boundaries.
