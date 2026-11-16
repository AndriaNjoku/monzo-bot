# Financial assistant direction and integration boundaries

Research checkpoint: 16 November 2026. This is an unofficial open-source companion, not a bank, financial adviser or a claim of a unique invention.

## Product intent

Make this genuinely useful for its owner first: understand imported transactions, bills and personal planning context, explain evidence and propose helpful next steps under clear user-controlled permissions. Keep the project open source so others can inspect, adapt and contribute. This is not a monetisation roadmap. Later blog/LinkedIn discussion should show verified capabilities with synthetic examples, not personal financial data or claims of autonomous banking. External posts require separate approval.

## Existing alternatives

- [Actual Budget schedules](https://actualbudget.org/docs/schedules/) already support recurring transactions and planning.
- [Firefly III](https://github.com/firefly-iii/firefly-iii) already provides open-source personal finance management and recurring transactions.
- [Monzo MCP](https://github.com/tcoretech/monzo-mcp) offers conversational read-only Monzo account information through an MCP server.
- [smartMoney subscription detection](https://github.com/mrahmadt/smartMoney/blob/main/docs/features/subscription-detection.md) is an additional implementation comparator, not a vetted recommendation.

The product hypothesis is a useful combination of understandable evidence, personal context, on-device privacy and explicit action permissions. Anticipating bills or chatting with Monzo data is not itself novel.

## Monzo integration constraints

The [official Developer API](https://docs.monzo.com/) explicitly is not suitable for public applications. It is for personal accounts or a small allowed group. Native clients cannot keep a client secret confidential and do not receive refresh tokens. Do not embed a client secret or promise unattended permanent sync. Transaction history access and pagination have separate limits; retrieval success does not establish complete coverage. Application-level read-only behavior is not proof of bank-enforced read-only token scope.

The [Open Banking API](https://docs.monzo.com/open-banking/) serves licensed third-party providers, with separate account-information and payment-initiation services. Public distribution needs an appropriate provider/regulatory route, security review and consent design. This increment does not connect that API, create OAuth clients, move money, write to pots, or contact creditors.

## First increment: local bill review

Opt-in analysis of the existing imported transaction history, with scanning off by default. Inferences are review candidates, not verified bills. Show original evidence, estimation uncertainty and retrieval freshness. The only new action is an explicitly reviewed local plan commitment. It must reject revoked permission, stale review/source, duplicate confirmation and invalid amounts/dates. Editing a proposed amount/date is the user's statement, not a model or bank finding.

No network, AI provider calls, external sharing, payments or background execution are added. Existing AI consent and notification permission flows remain separate. Importing a backup does not silently enable local scanning. Source transaction history and signing identity must be retained.

## Next stages, not implemented promises

- User-maintained bill context and explanations for uncertain/variable patterns, with evidence links and reversible decisions.
- Separate opt-in reminder scheduling with OS delivery acceptance, quiet hours and a revocable permission summary.
- Only after provider/regulatory design: account-information integration, with minimum scope and no bank-write executor by default.
- Any later external action needs concrete preview, destination, data, cost, expiry/replay protection and appropriate consent. No blanket assistant permission should imply money movement or sensitive-data sharing.
