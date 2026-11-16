# Optional AI

Implemented providers are OpenAI Chat Completions and Anthropic Messages, with fixed official endpoints and provider-bound credentials/consent. There is no arbitrary endpoint forwarding or any-provider promise. Model IDs are editable per provider and account/capability dependent. The retained OpenAI gpt-4.1-mini compatibility default has a deprecation warning; Anthropic uses the pinned claude-haiku-4-5-20251001 example. No live model availability is claimed.

Native HTTPS disables redirects and bounds request/response bytes, timeouts and output. Ask has at most 3 requests/8 local read-only tools; AI drafting uses one request, no financial tools and no retry. In-flight requests may finish after cancellation but cannot attach to a changed provider/source screen. No background AI or monetary spending cap is implied.

Ask facts are resolved locally from validated tool JSON-pointer references. Models cannot supply their own amounts/dates/evidence or call write tools. Seven tools cover aggregate history, redacted browsing, categories, budgets, cash context, review candidates and transaction impact. Budget allocations are not cash. Credits and Pot movements remain separate; model interpretations and payment suspicions remain unverified suggestions.

AI planning accepts a strict tentative schema. Name/category suggestions must be exact source excerpts or unresolved. Integer-pence amounts and supported dates must match the local parser's evidence from original wording; unsupported dates, estimates, multiple amounts and ambiguous liabilities remain unresolved. Original wording is retained only when the user explicitly confirms the local plan. Offline parsing is labelled honestly and never pretends to be generative AI.

All proposals require editable user review, deterministic affordability/freshness/source checks, explicit allocation mode and separate confirmation. Review expires, edits invalidate it and durable replay tokens prevent duplicate provisions. Payments, borrowing, creditor contact, calendar writes and notes are not AI tools.

Protocol references: [OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create), [Anthropic Messages](https://platform.claude.com/docs/en/api/messages), [React Native native platform integrations](https://reactnative.dev/docs/native-platform). Tests use synthetic data and fake gateways only, with no real API keys or paid calls.
