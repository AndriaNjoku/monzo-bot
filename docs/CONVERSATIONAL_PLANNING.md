# Conversational planning: first vertical slice

In **Plan → Plan from a message**, type a tentative purchase plan such as “Next month I want to buy a bicycle for £120”. Review the editable fields, choose an allocation mode, review the local calculation, then explicitly confirm the budget change. Preparing or reviewing a draft never saves it or makes a payment.

## What the draft can establish

The deterministic parser preserves the original words. It recognizes one explicit numeric GBP amount and supported date scopes: this month, next month, one ISO date, or an ordered pair of ISO dates. Missing, approximate, mixed-currency, multiple, debt/credit-related or ambiguous amounts remain unresolved. Unsupported dates stay blank. Names, categories and allocation mode require the user's choice. No AI provider or external transcription service is called to parse a message.

The reviewed proposal remains tentative. Confirming it authorizes a local allocation change, not a purchase, bank payment, borrowing, creditor contact or a claim that a transaction happened. Original imported transactions and stable IDs are unchanged; credits remain separate and internal Pot movements do not inflate spending.

## Two explicit allocation modes

- **Add to the allocation:** create a new exact-period category budget or raise its existing limit by the reviewed amount. Essential flags are preserved.
- **Use part of an existing allocation:** leave its limit unchanged and record the purchase provision within that exact category/period. Gross recorded debits and prior unresolved provisions must fit; credits are not automatically treated as refunds. Different overlapping periods or possible fulfilled-plan/payment overlaps require review rather than silent reconciliation.

Plans are stored under `conversationBudgets` in the encrypted financial workspace, together with original wording and a UUID/hash replay record. A budget mutation and its record are saved atomically. Replaying the same token cannot add the amount twice; reusing a token for different fields fails. The 500-record limit rejects further entries rather than pruning replay protection. This first slice does not link a plan to a later bank transaction, mark it paid, or infer that an old liability was settled. Saved entries stay visible as tentative plans. Review possible overlaps before relying on combined figures.

## Affordability is a transparent conditional review

The review separates allocation figures from cash. It displays exact integer-pence calculations, inclusive dates, latest observed transaction cutoff, retrieval freshness and unverified export coverage. A recent retrieval never establishes complete or current bank coverage. The app cannot confidently confirm affordability from this export.

A combined illustrative cash calculation requires same-day user-reported cash and a complete declared commitment list. It remains conditional and excludes other unrecorded spending. No future income or next-month balance is extrapolated. A negative balance provides no positive cash; unresolved negative balances, overdue commitments without a paid status, unconfirmed liabilities, duplicate provisions or possible payment overlaps withhold combined figures. Purchase provisions are counted once; increasing an allocation is not another cash deduction.

Editing any proposal field invalidates its previous review. Confirmation is bound to the exact immutable proposal, source and plan revision, current review date and a short review expiry. Background notification-ledger updates alone do not invalidate an otherwise unchanged review. A changed source or changed financial context requires another review; errors preserve entered fields. Confirmed records are bound to an opaque local source token. If an export changes, plans reviewed under the previous source are preserved but withheld from the new source’s conversational review until reconciled; they are never silently transferred or deleted.

## Optional voice

The microphone is optional and requested only after the user chooses **Speak a draft** and accepts the explanation. Supported Android 31+ devices use `createOnDeviceSpeechRecognizer` only. There is no default/cloud recognizer, model download, audio-file storage or automatic retry. Unsupported devices show a clear typing boundary. A final transcript only fills the editable text field; it never confirms a plan. Capture times out and is cancelled when the screen stops. Real recognition and microphone permission behavior still require device testing.

## Optional calendar and notes

Only a successfully confirmed local proposal exposes these actions. Each opens a separate content preview. Amount sharing starts off. Raw original wording, IDs and source transactions are not handed off.

Calendar opens an editor for a user-reviewed all-day tentative, free planning reminder. Its day is editable and is not inferred to be a payment due date. The user must choose a calendar and save there. Notes opens an Android text chooser only after explicit approval; that chooser is not limited to notes apps, and the chosen app may sync the reviewed content. The app does not automatically create calendar events, cloud notes or messages, and it cannot verify an external save.

## Verification and integration boundary

Synthetic domain checks cover parsing, integer amounts, dates, refunds/credits, Pot movements, idempotency, provision overlap and incomplete affordability inputs. JVM Android UI tests cover review-before-save, edit invalidation, double-submit protection, stale sources, honest unavailable-voice behavior and separate calendar/notes previews. They do not capture real audio, access financial accounts or launch a real external service.

This feature builds on PR #1's cloud source. The unpublished Windows 0.2.2 authentication/discovery patch is not overwritten. No signing identity, installed phone data, deployment, merge or paid hosted CI is changed by this work. Preserve and review that Windows patch before integrating it with the source branch.

## Optional generative assistance remains separate

Ask now offers implemented OpenAI/Anthropic BYOK adapters with provider-specific model/key selection and named processing consent. This local Plan-from-message parser is still deterministic, not a generative assistant. Model suggestions never mutate plans: any amount/date/category used from them remains untrusted input requiring local validation, a fresh impact review and explicit confirmation. Voice input remains on-device recognition and does not use the selected financial AI provider. See [BYOK providers](BYOK_PROVIDERS.md).
