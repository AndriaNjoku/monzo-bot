# Integration and release boundary

This source feature is a reviewable draft, not a release or a verified working bank connection.

## Cloud source

The AI-to-plan change builds on BYOK PR #3 (`accbea991a91390b2115ea8de86eab39c6e92f1e`), which is stacked on conversational-plan PR #2 and companion PR #1. Those branches remain separate from public main. Source validation uses local compile/lint, synthetic domain fixtures, fake provider transports/credential stores and host Robolectric. It does not establish live provider acceptance, device Keystore behaviour, microphone recognition or notification delivery.

No workflow, signing configuration, installed data, APK installation, merge or deployment is changed by this feature. No paid hosted CI or real provider API call is requested. The original signing identity must be retained for a later compatible update; a replacement key or uninstall is not an acceptable integration shortcut.

## Unpublished Windows connection patch

The reported Windows checkout is `D:\monzo-bot`, branch `fix/simple-ui-google-feedback`, with unpublished 0.2.2 authentication/discovery work in MainActivity, AppController, styles, GoogleDriveDiscovery and GoogleAuthFeedback. It is not present in the public branches inspected for this task. The current environment exposes Linux execution and the GitHub connector, with no Windows execution/transfer surface; consequently the actual patch, working-tree state, signing identity and Pixel are not inspectable here. The last reported Windows execution error is `helper_sandbox_lock_failed`; it was not reproduced by this task and its current status is unknown.

Leave that checkout unchanged. Integration requires preserving a reviewed patch/commit from the actual checkout, reconciling its connection/authentication/discovery behaviour with the companion entry point and existing storage contracts, and validating that source selection, Google consent, full-history import and disconnect/deletion behave correctly. Never publish financial exports, sheet/account identifiers, credentials or signing material in that patch. No claim of live Google import or automatic spreadsheet discovery is established by these cloud tests.

## Outstanding device and service verification

- Test the selected account/model against each implemented provider only with authorized data and charge consent; fake protocol acceptance is insufficient.
- Verify real provider-isolated Android Keystore save/read/forget, no-backup behaviour, process death and cancellation. Inspect financial exports for absent credentials.
- Exercise explicit AI consent, transcript edits, stale-source handling and review/confirm on the original signed app without clearing its data.
- Verify real on-device recognition availability/permissions and the honest typing fallback; do not silently add cloud transcription.
- Validate Google connection/discovery with the preserved Windows patch, transaction refresh/freshness, notification permissions/channels/quiet hours and Android background restrictions.

These checks remain incomplete until observed on the integrated source and device. The current draft must not be described as installed, production-ready or connected to live bank data.
