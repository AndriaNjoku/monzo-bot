# Versioning and release process

## Identity

Use SemVer-compatible versions: MAJOR.MINOR.PATCH, with numbered alpha/beta/rc suffixes during testing. New functionality increments MINOR; compatible fixes increment PATCH; breaking migration or compatibility changes require explicit planning. A 0.x version does not promise production stability.

`version.json` records the version and monotonically increasing buildNumber. Update package.json, both root package-lock entries, Android versionName/versionCode, and iOS marketing/build metadata in the same commit. iOS marketing metadata uses the base numeric version; its aligned metadata does not make the scaffold functional. Run `npm run version:check`. Never reuse a published version/tag/build number or move a published tag.

## Release classes

- **Source prerelease:** explicitly marked GitHub prerelease; source archive only. May come from a named review branch, with that fact in its notes. State tested commit/platforms and remaining acceptance limits. No implication of a main merge, app-store readiness or functional iOS.
- **Signed binary alpha:** requires release-signing ownership/backup and update-path decisions, actual certificate verification, final APK build, secret/financial-data scan, third-party notices, checksum and device acceptance of the exact artifact. Never distribute a personal debug-key build as an official public release.
- **Stable release:** requires resolved critical bugs plus documented Google/provider/device acceptance and reviewed privacy/security/distribution requirements.

## Before publishing

1. Review intended changes against main; preserve unrelated local work. Keep a draft PR for an unmerged source prerelease. Check existing tags/releases to prevent collisions.
2. Run version consistency, TypeScript, all synthetic tests, lint and production bundling. Native changes/version metadata require Android build validation. Record warnings and untested stages honestly.
3. Review only intended tracked files for credentials, device identifiers, personal account details, real financial data, screenshots and signing artifacts. Review dependency notices and security findings; tests are not a security audit.
4. Commit the exact versioned source; push only the intended branch. Verify remote SHA. Create annotated tag `v<version>` at that SHA, then verify the remote tag resolves to it.
5. Publish the explicitly appropriate release class with changelog, commit, validation, known limits, and approved assets only. Mark alpha/beta/rc as prereleases, not latest stable. Read back the published state. No social post is implied.
6. New fixes receive a new version/tag. Do not silently replace a published artifact. Keep release notes and installed-device version distinct.

Local checks do not substitute for required hosted checks. No hosted workflow is currently included in this source tree; adding CI is a separate reviewed change. Do not claim CI passed when only local checks ran.
