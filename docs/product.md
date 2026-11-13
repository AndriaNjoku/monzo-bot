# Product direction and source contract

## Scope recovered from prior decisions

Free and open source; usable without AI; optional user-supplied AI credentials. Google Drive transaction exports are the intended ingestion route. Proactive budget notifications are central. A Mac dashboard, graphs, pay cycles and optional calendar reminders are requested directions. Keep the existing private personal-finance dashboard separate from public source.

The original brief called for local sample/upload first, then Google read-only file selection, imports/duplicate handling, budgets, charts, optional grounded AI and explicit freshness. The prior Monzo Bot folder was reported empty and blocked by an agent hook; no reusable code was found in the current workspace or visible GitHub account. Hooks must not be bypassed.

## Observed header schema

Only headers were read from the actual native spreadsheet for schema inspection. No rows, file IDs or private financial values are included here.

Transaction ID; Date; Time; Type; Name; Emoji; Category; Amount; Currency; Local amount; Local currency; Notes and #tags; Address; Receipt; Description; Category split; Pot name.

The import requires ID, Date, Name, Category, Amount and Currency. Current account currency support is GBP; local currency is preserved in raw evidence. Date accepts ISO dates or explicit UK day/month/year. The source does not include an explicit pending-state field, balance or upcoming schedule. Header inspection does not verify source refresh latency or semantics of split values.

## Features these fields could support

- Category + amount + dates: budget pacing, monthly templates, configurable category alerts and pay-cycle views.
- Notes/tags: optional event envelopes such as holiday or seasonal gifts; user-selected goals, never automatically exposed to AI.
- Account versus local amount/currency: travel-spend review after exchange-rate and fee semantics are validated. Do not sum unlike currencies.
- Pot name/type: potential transfer reconciliation after source behavior is verified, preventing double counting.
- Stable ID: idempotent sync with explicit corrections rather than heuristic deletion of similar payments.
- Name/type/date history: estimated recurring-payment candidates for user confirmation; never a guaranteed future bill.
- Address/receipt/description: potentially sensitive enrichment retained only locally; exclude from notifications, AI and diagnostics by default.

## AI interaction ideas

Show the calculation first, then offer an optional explanation: explain category pace, compare an optional purchase against a user-approved envelope, offer three small budget adjustments, or propose a prior-month template. Each suggestion needs a period, data timestamp, evidence references and uncertainty. No affordability guarantee without verified balances and commitments. No financial actions.
