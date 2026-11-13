# Android notification plan

Research checked 2026-11-13 against primary Android documentation. This is a design plan, not an implemented native client.

## Recommended delivery

Use normal NotificationCompat notifications with user-controlled channels and POST_NOTIFICATIONS runtime consent on Android 13+. Keep public/lock-screen notification content generic and use private visibility plus a redacted public version. Opening the app reveals grounded calculations and source timestamps after device/app authentication. Provide quiet hours, digest mode, snooze, per-budget cooldown and an explicit off switch.

Background source checks can use WorkManager, whose periodic interval has a minimum of 15 minutes and is inexact due to system constraints. Select a conservative frequency after measuring export freshness. WorkManager is scheduled device work, not remote push. FCM or Web Push would require separately configured services, consent, secure subscriptions and a privacy design; do not place financial data in messages. Freshness and deduplication must be checked on the receiving device before alerting.

## Longer-lived display

Android Live Updates / promoted ongoing notifications may be the remembered API, but that identification is unconfirmed. Google requires an ongoing, user-initiated, time-sensitive activity with a distinct start/end and explicitly excludes general alerts, upcoming calendar events and ambient information. An always-on budget balance should therefore not claim Live Update eligibility. Prefer an opt-in app widget or Quick Settings tile with redacted defaults, a timestamp and a tap to unlock. Standard notifications remain dismissible and controlled by the OS/user; do not promise a permanent lock-screen display or run a foreground service merely to pin a budget.

## Alert evidence contract

- Input: exact period, category budget, user-approved classifications, deterministic totals, source coverage timestamp and freshness state.
- Unknown/stale source: generic sync reminder, not a claim about current spending or affordability.
- Threshold event: stable category/period/severity key, ledger of sent events, cooldown and updated import validation.
- Forecasts: separately labelled estimates; require adequate history and user confirmation of upcoming commitments.
- AI: optional explanations after deterministic calculation; no fabricated transactions, provider calls or payments without consent.

Primary sources:
- https://developer.android.com/develop/ui/views/notifications/live-update
- https://developer.android.com/develop/ui/compose/notifications/notification-permission
- https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
