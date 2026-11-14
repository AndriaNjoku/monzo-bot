# Development privacy notice

Monzo Bot is an independent, unofficial open-source budgeting project. This notice describes this development version; it is not a claim of independent security certification.

The Android app reads only the Google spreadsheet explicitly selected by the user, after Google read-only authorization. The current Google permission covers Sheets broadly; the app does not list Drive or modify source cells. Data requests go directly from the device to Google's Sheets API. Google processes authorization and API requests under its own terms and policies.

Imported transaction evidence, category corrections, budgets and selected-file details are encrypted in app-private storage with an Android Keystore AES-GCM key. Android backup/device-transfer copies are disabled by app configuration. Google access tokens stay in memory. Optional AI keys are held in memory by default; an explicit remember option uses a separate app-private Android Keystore AES-GCM credential file. Provider keys never enter exportable financial JSON. No application analytics, advertising SDK, cloud financial database or transaction logging is configured.

Notifications and the home-screen widget use generic text without financial amounts, names, categories or account identifiers. Financial screens block screenshots/recording where Android enforces FLAG_SECURE. These measures do not replace device security or prevent access on a compromised device.

Optional AI requires explicit per-question provider consent and a provider-charge notice. The typed question is sent as written to api.openai.com, with bounded facts selected through local read-only tools. Numeric/date facts are the default. Merchant/category/type and user-plan labels require a separate opt-in; labels may include personal names. Source IDs, notes, addresses, receipts and Pot names are excluded from tool output. Calculated facts are separate from unverified AI reasoning and suggestions; no financial actions or source edits are available.

The user can export a plaintext private copy to a location they choose after confirmation. Exported files fall outside this app's encrypted store and should be kept private. Local deletion removes financial data and both encryption keys, including any remembered provider key, and cancels automatic sync. Disconnect stops this device's synchronization while retaining local records unless deleted; Google grants can separately be revoked in Google Account settings. Original financial spreadsheets are never deleted or changed.

Only synthetic examples are committed to public source. The project should complete live-device testing, a release security review and appropriate developer/contact and Google-consent disclosures before any public app launch.
