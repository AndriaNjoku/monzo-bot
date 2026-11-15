# Transaction companion direction

## Product contract

The app connects to a private transaction export and keeps its entire validated history available. A user should get a useful Home screen immediately after connection, without setting reporting dates or budgets first. Optional date/category filters change the view or answer scope, never what the app imports or remembers. Activity must paginate through every imported record, including older rows beyond the first thousand.

This is a source-export companion. Retrieval time, latest observed transaction and verified bank data coverage are distinct. Do not imply a live balance or guaranteed real-time feed.

## Independent visual identity

Use warm off-white #F8F6F2, navy #172A3A, coral #FF6B55 and muted text #526573. Navy text on coral maintains normal-text contrast; white text on coral does not. Use an independent icon and attribution in About, without bank logos or claims of affiliation. Keep connection and freshness details compact and expandable rather than turning Home into a diagnostics form.

Use each source Emoji as the merchant avatar when available, preserving its entire Unicode sequence. Fallback by category only when absent; do not replace source emojis with generic initials or automatically fetch external merchant logos. Debit, credit and Pot movement need distinct labels/icons as well as colour.

## Main surfaces

- Home: recent activity, an automatic current-spending overview and a small number of evidence-backed observations. No empty configuration wall or large slogan banner.
- Activity: all history by default; newest first; day groups; search names, categories and available local notes; optional Spending / Money in / Pots chips; stable cursor pagination.
- Plan: optional budgets, goals, category corrections and calendar-month/pay-cycle settings. Planning scope must not narrow global Activity.
- Ask: optional BYOK conversation using local read-only tools that can search and calculate across history. Questions should get calculations, scope, evidence and uncertainty, not generic coaching disconnected from transactions.
- Settings: source discovery/selection, provider and secure-key preferences, notifications, export/delete/disconnect and attribution.

## Preserve and use the export

Retain Transaction ID, Date, Time, Type, Name, Emoji, Category, Amount, Currency, Local amount, Local currency, Notes and #tags, Address, Receipt, Description, Category split and Pot name when present. Keep original evidence separate from user corrections. Show fields progressively in transaction detail; absent fields should not create invented content.

Separate rows explicitly marked `Pot transfer` from external spending and money-in totals, while retaining them in Activity and pot summaries. Do not exclude the broad `Transfers` category automatically: external payments may use it. Positive amounts are credits, not automatically salary or refunds. Distinguish external cashflow, category spending, internal movements and any confirmed income classification. Preserve original/local currencies without summing unlike currencies or inventing exchange fees.

Recurring-payment detection proposes candidates from sufficient repeated evidence; estimates remain labelled and require confirmation before becoming a planned bill. Old, irregular or duplicate-date payments must not generate a promised upcoming debit.

## Optional agent

Core import/search/insights/budgets/notifications work without AI. Provider calls require explicit user consent, a user-supplied key and a disclosed sharing mode. Keys must remain private and never enter source, logs or tool outputs. A provider preference should not require repeated key-entry dialogs for every question; optional secure persistence needs explicit consent and device protection.

The model should request bounded read-only local tools: search transactions; get a transaction detail; summarize by category/merchant; compare requested intervals; suggest recurring candidates. The local calculation engine remains authoritative. Never upload the whole spreadsheet to answer a narrow question. Default to numeric aggregates; merchant/category labels require the corresponding sharing consent. Raw notes, addresses, receipts and source IDs stay out of provider context by default. Opaque local evidence references can support explanations without exposing source transaction IDs.

Bound model calls and result sizes. Treat source fields and model outputs as data, not instructions. No tool can send payments, alter bank accounts or silently write source cells. Budget proposals need user review before any local change.

## Acceptance checks

1. Connect and discover candidate exports without a hard-coded personal file ID. Ambiguous matches require a choice; no-match paths explain next steps.
2. All validated rows are imported or sync fails visibly and atomically. A partial import must not look complete.
3. A synthetic old transaction beyond row 1,000 remains searchable and accessible through pagination.
4. Source emoji, multi-emoji strings and original/local currency values survive display projection.
5. Pot movements remain visible but do not inflate external-spending totals; an external payment in category Transfers is retained.
6. Home works without opening a date picker or setting a budget.
7. Agent answers use the correct all-history or explicitly requested scope, state coverage limits and reference actual local evidence.
8. Provider requests contain only permitted fields and stop at the configured call limit. Without consent there is no network request.
9. Sync, source switching, sign-out, cancellation and app restart preserve privacy and data isolation.

The separate CompanionInsights and CompanionTools modules are integration building blocks; adding them alone does not claim that the installed Android interface has been redesigned or that live authorization is configured.

## Integration building blocks

New modules are isolated from the current Activity/controller so the native redesign can integrate them without conflicting edits:

- `CompanionInsights`: full-history overview, rich emoji/source metadata, merchant views, immutable activity-browsing sessions and tentative recurring patterns. Keep one session per dataset/correction revision; reset it after sync.
- `CompanionPlanner`: user-defined budgets and essential priorities, transaction impact, conservative pace estimates, review-only payment signals and explicitly dated user-reported cash context. Credits stay separate; budget remaining is not available cash.
- `CompanionTools`: bounded read-only history/search/compare/merchant/recurrence functions. Private-label mode disables label filters, not merely their output. Provider searches never inspect notes, descriptions, addresses or receipts.
- `CompanionPlanningTools`: budget status, transaction assessment, payment review and planning context. The host supplies user context; the model cannot invent or modify it.
- `CompanionAgent`: optional BYOK tool loop, explicit provider/label consent, maximum model/tool calls, no automatic paid retries, and a test-injectable provider gateway.

Agent final answers reference actual successful tool-call IDs and scalar JSON-pointer paths. The host resolves the values and rejects replacement values, unknown citations and model-written numerical claims. Prose can use `{{fact:0}}` placeholders; the host inserts the actual formatted value and preserves estimate/headroom qualifiers. Model interpretation and suggested next steps remain AI reasoning, explicitly separate from calculated facts. This is not a guarantee that every qualitative suggestion is correct; the UI must not label model prose or bank coverage as verified.

When invoking the agent, pass all validated history and an immutable copy of user-approved planning context, along with the actual retrieval timestamp. Keep keys private. The result carries `verifiedFactsJson` (referenced local calculations, **not verified bank data**), source coverage limits, evidence references, separate suggestions and `interpretationVerified=false`. Before attaching a result to the current screen, check the source/plan revision or identify it as an older snapshot.

The intended proactive flow is: import a new transaction; evaluate its effect on the user's plan; recompute useful review signals; show a concise explanation and options; apply quiet hours/privacy/cooldowns before notifying. A possible duplicate or unusual amount remains a review candidate. No payment, refund, bank-account change or automatic budget rewrite is exposed.

Core value must remain available before a user supplies a budget or AI key. Ask for a small amount of missing planning context conversationally when it changes a decision, rather than making setup forms the entire product.
