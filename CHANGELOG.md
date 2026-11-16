# Changelog

## 0.5.0-alpha.1 - 2026-11-16

Experimental source prerelease, Android versionCode 501. No public signed APK or iOS release is included. The new features were developed on a feature branch; this entry does not imply that branch has merged into main.

### Added
- Opt-in local recurring-bill review with transaction evidence, editable commitments, stale-review protection and duplicate confirmation safeguards.
- Provider-specific Continue/Cancel AI privacy notice. Continue enables consent only; sending remains a separate action. Labels have separate consent and context changes revoke approval.
- Synchronized version metadata, version consistency check and explicit release gates.

### Corrected
- Backup import resets bill-analysis permission while preserving its audit markers.
- Setup Node engine range matches React Native; Android 11/API 30+ minimum documented.
- Manual CSV replacement, optional paid Sheets export and source-coverage limits are explicit.
- Documentation distinguishes local, device-tested and release states; MIT source licensing is not a whole-dependency audit.

### Verification and known limits
- Feature checkpoint: 238 synthetic tests across 7 suites, TypeScript and production Android JS bundling passed. ESLint: 0 errors / 137 warnings.
- Version 501 unsigned Android release assembly passed, with 8 native tests and release lint 0 errors / 16 warnings. No APK is included in this source release.
- Earlier bill-review checkpoint 42c0b16: signed Android build/native tests/release lint and in-place physical-device smoke checks passed, preserving stored history. It retained the legacy 0.4.0-alpha/build 400 label.
- The final 0.5.0-alpha.1 version metadata and AI consent screen do not inherit that earlier device acceptance. No live Google, AI-provider, speech or notification acceptance is claimed. iOS remains a scaffold.
- Full-history operations can feel slow. Variable/irregular bills may be missed. No autonomous payments, creditor contact or bank-write executor exists.

## Historical 0.4.0-alpha source baseline - 2026-11-15

Commit e31d5fd merged the React Native/TypeScript replacement into main. Android metadata was 0.4.0-alpha/build 400, npm metadata 0.4.0. No retroactive tag or public binary release is asserted. Subsequent local device builds reused build 400; future releases must use distinct increasing build numbers.
