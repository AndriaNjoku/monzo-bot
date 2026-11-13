# Private companion notifications and planning integration

## What is implemented

`CompanionAlertEngine` calculates read-only candidates from the whole imported history and a user-owned plan. It supports budget threshold crossings, exceeded budgets, qualified spending-pace estimates, possible duplicate/unusually large payment reviews, user-confirmed commitments due within three days, and source-retrieval freshness checks. A recent retrieval never proves bank coverage. Stale, absent, invalid or future retrieval timestamps suppress transaction-based alerts.

The first source snapshot is a baseline, not a backlog of alerts. New transaction references are retained briefly across quiet hours and cooldowns. Old backfilled transactions do not generate fake budget crossings. Candidate titles/bodies are generic; rich evidence and explanations belong inside the private app. Estimates are never confirmed upcoming bank debits or verified fraud.

`CompanionDelivery` applies local quiet hours, a six-hour global cooldown, three-per-day cap, stable event deduplication and a bounded sent-key ledger. Only successful OS posting consumes an event. `CompanionNotifications` persists that ledger and hashed snapshot references in the existing encrypted Android Keystore store. No transaction payload, credential or model key enters WorkManager. Delivery performs no AI requests.

`CompanionMonitorWorker` supplies an hourly, inexact local check and a one-time post-sync check. Google export synchronization remains separate. These are local Android notifications, not a bank webhook or a remote push service. Notifications can be delayed by OS scheduling, battery restrictions, disabled channels, denied permissions or a spreadsheet that has not refreshed.

An optional low-importance ongoing notification shows only that the private companion is enabled and export coverage remains unverified. It is user-controlled, may be dismissed by Android, and does not keep an unlimited background process alive. It is not an Android promoted Live Update: [Android's criteria](https://developer.android.com/develop/ui/views/notifications/live-update) restrict those to ongoing, user-initiated, time-sensitive activities. Scheduling follows [WorkManager's periodic-work model](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

## Native UI hooks still required

The Windows UI/auth/discovery patch is owned separately. These modules do not silently enable the feature or claim installation on a phone.

1. After a contextual explanation, request Android notification permission and call `CompanionNotifications.configure(...)` off the main thread. Expose `availability(...)` so denied permission/disabled channel is visible. Quiet times use the device's local timezone. Enabling the new engine disables the old budget-only notification setting; the legacy adapter also checks the new engine's enabled flag to prevent duplicate delivery.
2. After each successful atomic import/sync, call `CompanionMonitorWorker.checkSoon(context)`. Do not run checks against a partial response. Schedule periodic checks only when enabled.
3. On disconnect, source change or deletion, call `CompanionMonitorWorker.cancel(context)` and `CompanionNotifications.clearPosted(context)` before changing the workspace. The adapter also detects a changed source token at its next check and clears old notifications before baselining. If enabled after switching, configure/schedule again against the new source.
4. A notification opens MainActivity with opaque `companionEvent` and kind extras. Resolve these only within the current source revision; render locally recomputed evidence, never treat an old notification as proof. The current Activity still needs this navigation hook.
5. Use `BudgetWorkspace` for user-approved planning edits, then `CompanionNotifications.savePlan(context, plan)`. Budget periods are independent of the global Activity history. Monthly payday dates clamp per month, without drifting the selected payday; fixed cycles support fortnightly planning. A user-reported balance is dated and remains unverified. Changing commitments invalidates the prior declaration that the list is complete.
6. Use `CategoryPresentation` for source transaction emoji, category icon overrides, readable labels and separate debit/credit/Pot indicators. Store emoji overrides locally. Never publish private custom categories as fixtures.
7. Render `CompanionOpportunities.cards(...)` as calculated local insight cards and user-triggered Ask suggestions. Label sharing follows explicit consent. No card automatically invokes a provider; retain the optional agent's existing consent and call limits.

## Verification scope

Deterministic candidate/delivery tests use synthetic fixtures and a fake OS publisher. They cover first-import noise, Pot transfers, future/backfilled rows, freshness, threshold crossings, deduplication, failed posting, quiet hours, daily reset and bounded ledgers. Real device permission flows, notification navigation, background scheduling, source lifecycle hooks and Google authorization must still be exercised after the native UI integration. A passing APK build alone does not verify those behaviors on a Pixel.
