# Bring-your-own-key provider choices

## Three separate features

- **Ask** uses a selected generative provider through bounded read-only local tools. Model output is untrusted interpretation and suggestions. No model tool can apply a budget, make a payment or write a source/calendar/note.
- **Plan → Plan from a message** defaults to offline rule parsing. A separate **Use AI to prepare draft** action sends only the approved planning message and reference date to the selected provider after named consent. Its structured result must pass local amount/date validation and editable review before the deterministic impact review and explicit confirmation. See [conversational planning](CONVERSATIONAL_PLANNING.md).
- **Speak a draft** uses supported Android on-device recognition only. It fills editable text and does not invoke a financial AI provider. Unsupported devices get the typing fallback.

Generated suggestions are never imported automatically as allocations. If used to prepare a plan, their dates, amounts and category remain untrusted inputs to the same local validation and explicit review flow. Financial arithmetic, cutoff/freshness warnings and duplicate-submit protection are independent of provider/model choice.

## Implemented adapters

| Provider | Fixed endpoint | Example/default model ID | Required model capabilities |
| --- | --- | --- | --- |
| OpenAI | `https://api.openai.com/v1/chat/completions` | `gpt-4.1-mini` (legacy) | Chat Completions function tools and final JSON |
| Anthropic | `https://api.anthropic.com/v1/messages` | `claude-haiku-4-5-20251001` (pinned example) | Messages tool use and final JSON instructions |

The model field is editable and saved separately per provider. This is not an all-provider or all-model interface. There is no custom base URL, endpoint forwarding or third-party compatibility proxy. A configured model must be accessible to the user's API account and support the adapter's protocol; validation cannot establish live availability. Unsupported/refused/truncated responses fail without automatic paid retries.

Official documentation checked on 14 November 2026:

- [OpenAI GPT-4.1 mini](https://developers.openai.com/api/docs/models/gpt-4.1-mini) documents tool/structured-output support and marks the legacy model deprecated. It remains an editable compatibility default, not a recommendation or availability promise. See [deprecations](https://developers.openai.com/api/docs/deprecations) before relying on it.
- [Claude Messages](https://platform.claude.com/docs/en/build-with-claude/working-with-messages) documents the fixed endpoint and `anthropic-version: 2026-10-21` used here. The adapter maps system instructions, client tools, assistant `tool_use` and user `tool_result` blocks to the existing read-only loop. It does not enable server tools or forward OpenAI-only request fields.

These are protocol-tested adapters, not live model acceptance tests. No real keys, financial data or paid provider requests were used in their tests.

## Consent, cost and switching

Ask requires processing consent naming the selected provider and disclosing that the typed question and selected local financial facts are shared. Numeric/date facts and redacted browsing are the default. Merchant/category/type and user-plan labels need a separate opt-in; they may contain personal names. Notes, addresses, receipts, Pot names and original transaction IDs are excluded from tool output. Anything manually typed into a question is sent as written after consent.

Changing provider clears the previous key field, answer and consent, cancels later rounds of the previous request, and loads only that provider's explicitly remembered key. Model, key, question or sharing edits revoke the processing consent. An already in-flight request may complete at the provider; it cannot be unsent. Late answers cannot attach to the new provider's screen.

Each Ask question has at most three model requests and eight local tool calls, with bounded output/context, HTTP timeouts and no automatic retries or background provider calls. Requests are billed to the selected API account under its model pricing. A request bound is not a monetary spending cap or a promise that an existing chat subscription includes API usage.

AI draft preparation uses one model request, no local financial tools and no automatic retries. It shares no imported transaction or budget data. Consent is separate from Ask, tied to the current provider/key/model/message, and starts off. The local parser remains available without a provider key.

OpenAI receives the existing `store:false` flag. Anthropic has no equivalent request flag in this adapter; none is invented. Ask does not retain a conversation log. AI planning drafts remain in memory until confirmed; an explicitly confirmed local plan retains its original wording in the encrypted financial workspace. Provider-side processing/retention follows the selected provider's terms and account controls. No zero-retention promise is made.

## Credentials

Remembering is optional. Keys stay out of financial JSON, exports, saved view state, app-generated prompts/transcripts, logs and backups. Each provider uses its own encrypted no-backup file and Android Keystore alias. The legacy OpenAI file/alias/encryption format is preserved; its key is never migrated or reused for Anthropic. New Anthropic storage also authenticates its provider/file/alias identity as AES-GCM associated data.

Forget removes the selected provider's slot; Settings also offers forgetting all provider keys. Deleting the financial workspace clears both provider slots. Storage writes recheck cancellation under the same lock as forgetting so a cancelled request cannot restore a forgotten credential. Matching provider generations also guard asynchronous key loads. Device-level Keystore deletion, backup exclusion and crash recovery remain unverified by host tests.

## Verification boundary

Synthetic fake transports test both native protocols, fixed origins and auth headers, tool/result conversion, privacy, refusal/malformed responses, call bounds and absence of retries. Core-agent tests require provider-matched consent before any gateway call and reject write tools or fabricated figures. Robolectric UI tests exercise provider switches, consent revocation, per-provider model/key separation, forgetting and accepted-response identity. Portable storage checks cover slot metadata, legacy format and synthetic AES/AAD behaviour; they do not exercise real Android Keystore hardware.

No live provider, microphone, external editor, physical phone or emulator is used for this verification. Windows authentication/discovery work, signing identity and installed app data remain outside this feature's changes. No merge, deployment, installation or paid hosted CI is performed.
