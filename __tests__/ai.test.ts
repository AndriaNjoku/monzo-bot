import {
  ask,
  preparePlan,
  protocolReply,
  protocolRequest,
  ProviderId,
  PROVIDERS,
  Transport,
  TransportRequest,
} from '../src/ai';
import { toolSession, pointerWithRefs } from '../src/ai/tools';
import { emptyPlan, parsePlanningMessage, Workspace } from '../src/domain';
const today = '2026-11-15',
  key = 'synthetic-test-key',
  model = 'synthetic-model';
const workspace: Workspace = {
  transactions: [
    {
      id: 'private-source-id',
      date: '2026-11-01',
      name: 'Private Merchant',
      type: 'Card payment',
      category: 'Groceries',
      amount: -1200,
      raw: {
        Notes: 'private-note',
        Address: 'private-address',
        'Pot Name': 'private-pot',
      },
    },
    {
      id: 'credit-source-id',
      date: '2026-11-02',
      name: 'Private Credit',
      type: 'Transfer',
      category: 'General',
      amount: 300,
      raw: {},
    },
    {
      id: 'pot-source-id',
      date: '2026-11-03',
      name: 'Private Pot',
      type: 'Pot transfer',
      category: 'Savings',
      amount: -6000,
      raw: {},
    },
    {
      id: 'future-id',
      date: '2027-01-01',
      name: 'Future Merchant',
      type: 'Card payment',
      category: 'Shopping',
      amount: -10000,
      raw: {},
    },
  ],
  overrides: {},
  lastSync: '2026-11-15T12:00:00Z',
  companionPlan: {
    ...emptyPlan(),
    budgets: [
      {
        category: 'Groceries',
        start: '2026-11-01',
        end: '2026-11-30',
        limitPence: 10000,
        essential: true,
      },
    ],
  },
};
const consent = (provider: ProviderId, labels = false) => ({
  provider,
  processing: true,
  labels,
});
function native(provider: ProviderId, value: unknown, call = false): unknown {
  if (provider === 'openai') {
    return {
      choices: [
        {
          finish_reason: call ? 'tool_calls' : 'stop',
          message: {
            role: 'assistant',
            content: call ? null : JSON.stringify(value),
            ...(call
              ? {
                  tool_calls: [
                    {
                      id: 'summary',
                      type: 'function',
                      function: {
                        name: 'summarize_transactions',
                        arguments: JSON.stringify(value),
                      },
                    },
                  ],
                }
              : {}),
          },
        },
      ],
    };
  }
  return {
    type: 'message',
    role: 'assistant',
    stop_reason: call ? 'tool_use' : 'end_turn',
    content: call
      ? [
          {
            type: 'tool_use',
            id: 'summary',
            name: 'summarize_transactions',
            input: value,
          },
        ]
      : [{ type: 'text', text: JSON.stringify(value) }],
  };
}
function lastResult(
  request: TransportRequest,
  provider: ProviderId,
): Record<string, unknown> {
  const messages = request.body.messages as unknown as Record<
    string,
    unknown
  >[];
  const last = messages[messages.length - 1];
  return JSON.parse(
    provider === 'openai'
      ? (last.content as string)
      : (last.content as { content: string }[])[0].content,
  );
}
function groundedGateway(
  provider: ProviderId,
  edit?: (
    answer: Record<string, unknown>,
    result: Record<string, unknown>,
  ) => void,
): Transport {
  let round = 0;
  return jest.fn(async request => {
    if (round++ === 0) {
      return native(provider, { from: null, to: null }, true);
    }
    const result = lastResult(request, provider);
    const answer = {
      interpretation:
        'Your recorded money out is {{fact:0}}. Export coverage remains unknown.',
      facts: [{ toolCallId: 'summary', path: '/moneyOutPence' }],
      evidenceRefs: result.evidenceRefs,
      nextSteps: ['Review your flexible choices against your essential plan.'],
    };
    edit?.(answer, result);
    return native(provider, answer);
  });
}
const askOptions = (provider: ProviderId) => ({
  provider,
  key,
  model,
  question: 'Help me understand spending',
  consent: consent(provider),
  workspace,
  today,
});
const message = 'Next month I might buy a bicycle for £120 in Shopping';
function planResult(original = message) {
  const parsed = parsePlanningMessage(original, today);
  return {
    tentative: true,
    name: original.includes('bicycle') ? 'bicycle' : '',
    category: original.includes('Shopping') ? 'Shopping' : '',
    amountPence: parsed.amountPence,
    from: parsed.from,
    to: parsed.to,
  };
}
const planOptions = (provider: ProviderId, original = message) => ({
  provider,
  key,
  model,
  message: original,
  today,
  consent: consent(provider),
});

describe.each<ProviderId>(['openai', 'anthropic'])(
  '%s bounded BYOK',
  provider => {
    test('uses only its fixed native endpoint/header and locally resolved financial facts', async () => {
      const transport = groundedGateway(provider);
      const answer = await ask(askOptions(provider), transport);
      expect(answer.complete).toBe(true);
      expect(answer.text).toContain('£12.00');
      expect(answer.facts[0].value).toBe(1200);
      expect(answer.facts[0].bankDataVerified).toBe(false);
      expect(answer.modelRequests).toBe(2);
      expect(answer.toolCalls).toBe(1);
      expect(transport).toHaveBeenCalledTimes(2);
      for (const [request] of (transport as jest.Mock).mock.calls as [
        TransportRequest,
      ][]) {
        expect(request.endpoint).toBe(
          PROVIDERS.find(item => item.id === provider)?.endpoint,
        );
        expect(
          request.headers[
            provider === 'openai' ? 'Authorization' : 'x-api-key'
          ],
        ).toBe(provider === 'openai' ? `Bearer ${key}` : key);
        const body = JSON.stringify(request.body);
        for (const secret of [
          key,
          'private-source-id',
          'private-note',
          'private-address',
          'private-pot',
          'Private Merchant',
          'Private Credit',
        ]) {
          expect(body).not.toContain(secret);
        }
        expect(
          provider === 'openai'
            ? request.body.store
            : request.headers['anthropic-version'],
        ).toBe(provider === 'openai' ? false : '2026-10-21');
        if (provider === 'anthropic') {
          expect(request.body).not.toHaveProperty('store');
          expect(request.body).not.toHaveProperty('response_format');
        }
      }
      expect(workspace.transactions).toHaveLength(4);
      expect(workspace).not.toHaveProperty('conversationBudgets');
    });
    test('declined or wrong-provider consent makes zero calls', async () => {
      const transport = jest.fn();
      await expect(
        ask(
          {
            ...askOptions(provider),
            consent: { provider, processing: false, labels: false },
          },
          transport,
        ),
      ).rejects.toThrow('consent');
      await expect(
        ask(
          {
            ...askOptions(provider),
            consent: consent(provider === 'openai' ? 'anthropic' : 'openai'),
          },
          transport,
        ),
      ).rejects.toThrow('consent');
      expect(transport).not.toHaveBeenCalled();
    });
    test.each([
      '123 invented pounds',
      'You can afford it',
      'I paid the bill',
      'Guaranteed safety',
      'A date in 2027',
      '{{fact:8}}',
      '{{wrong}}',
    ])('rejects ungrounded prose %s', async bad => {
      await expect(
        ask(
          askOptions(provider),
          groundedGateway(provider, answer => {
            answer.interpretation = bad;
          }),
        ),
      ).rejects.toThrow();
    });
    test('rejects invented values/refs and requires the selected fact support', async () => {
      await expect(
        ask(
          askOptions(provider),
          groundedGateway(provider, answer => {
            answer.facts = [
              { toolCallId: 'summary', path: '/moneyOutPence', value: 2 },
            ];
          }),
        ),
      ).rejects.toThrow();
      await expect(
        ask(
          askOptions(provider),
          groundedGateway(provider, answer => {
            answer.evidenceRefs = ['invented-ref'];
          }),
        ),
      ).rejects.toThrow();
      await expect(
        ask(
          askOptions(provider),
          groundedGateway(provider, answer => {
            answer.evidenceRefs = [];
          }),
        ),
      ).rejects.toThrow();
      await expect(
        ask(
          askOptions(provider),
          groundedGateway(provider, answer => {
            answer.facts = [{ toolCallId: 'missing', path: '/moneyOutPence' }];
          }),
        ),
      ).rejects.toThrow();
    });
    test('requires a successful local tool before a factual final answer', async () => {
      await expect(
        ask(askOptions(provider), async () =>
          native(provider, { interpretation: 'No evidence' }),
        ),
      ).rejects.toThrow();
    });
    test('network failure gets one attempt and does not leak the provider error or key', async () => {
      const transport = jest.fn(async () => {
        throw new Error(`Secret ${key}`);
      });
      await expect(ask(askOptions(provider), transport)).rejects.toThrow(
        'selected provider',
      );
      expect(transport).toHaveBeenCalledTimes(1);
    });
    test('caps repeated tool rounds at three provider calls and no automatic write tool', async () => {
      let round = 0;
      const transport: Transport = jest.fn(async () => {
        const response = native(
          provider,
          { from: null, to: null },
          true,
        ) as Record<string, unknown>;
        if (provider === 'openai') {
          const c = response.choices as {
            message: { tool_calls: { id: string }[] };
          }[];
          c[0].message.tool_calls[0].id = `round_${round++}`;
        } else {
          (response.content as { id: string }[])[0].id = `round_${round++}`;
        }
        return response;
      });
      const answer = await ask(askOptions(provider), transport);
      expect(answer.complete).toBe(false);
      expect(answer.modelRequests).toBe(3);
      expect(transport).toHaveBeenCalledTimes(3);
      const body = JSON.stringify((transport as jest.Mock).mock.calls);
      expect(body).not.toContain('apply_budget');
    });
    test('prepares exactly one untrusted message-only tentative draft without applying it', async () => {
      const transport: Transport = jest.fn(async () =>
        native(provider, planResult()),
      );
      const result = await preparePlan(planOptions(provider), transport);
      expect(result.draft.originalText).toBe(message);
      expect(result.draft.tentative).toBe(true);
      expect(result.draft.amountPence).toBe(12000);
      expect(result.draft.from).toBe('2026-12-01');
      expect(result.name).toBe('bicycle');
      expect(result.category).toBe('Shopping');
      expect(transport).toHaveBeenCalledTimes(1);
      const body = (transport as jest.Mock).mock.calls[0][0].body;
      const payload =
        provider === 'openai'
          ? body.messages[1].content
          : body.messages[0].content[0].text;
      expect(JSON.parse(payload)).toEqual({ message, referenceDate: today });
      expect(body.tools).toBeUndefined();
      expect(JSON.stringify(body)).not.toContain(key);
      expect(JSON.stringify(body)).not.toContain('Private Merchant');
    });
    test.each([
      { amountPence: 12001 },
      { amountPence: '12000' },
      { amountPence: 12000.5 },
      { amountPence: -1 },
      { amountPence: Number.MAX_SAFE_INTEGER + 1 },
      { from: '2027-01-01', to: '2027-01-31' },
      { from: '2026-12-01', to: null },
      { tentative: false },
      { name: 'invented purchase' },
      { category: 'Groceries' },
      { action: 'save' },
      { originalText: 'rewritten' },
    ])('rejects invented/unsafe structured proposal %j', async change => {
      const transport = jest.fn(async () =>
        native(provider, { ...planResult(), ...change }),
      );
      await expect(
        preparePlan(planOptions(provider), transport),
      ).rejects.toThrow();
      expect(transport).toHaveBeenCalledTimes(1);
    });
    test.each([
      'I might buy a bicycle',
      'I owe £120 next month',
      'Buy for about £120 next month',
      'Buy for £120 or £150 next month',
      'Do not buy a bicycle for £120 next month',
    ])('keeps unresolved evidence null for %s', async original => {
      const result = await preparePlan(
        planOptions(provider, original),
        async () => native(provider, planResult(original)),
      );
      expect(result.draft.amountPence).toBeNull();
      expect(result.draft.originalText).toBe(original);
      await expect(
        preparePlan(planOptions(provider, original), async () =>
          native(provider, { ...planResult(original), amountPence: 12000 }),
        ),
      ).rejects.toThrow();
    });
    test('allows the model to leave explicit details unresolved but never fills them silently', async () => {
      const result = await preparePlan(planOptions(provider), async () =>
        native(provider, {
          ...planResult(),
          amountPence: null,
          from: null,
          to: null,
        }),
      );
      expect(result.draft.amountPence).toBeNull();
      expect(result.draft.from).toBeNull();
      expect(result.draft.issues.length).toBeGreaterThan(0);
    });
    test('rejected consent/cancellation/invalid key/model stop before network', async () => {
      const transport = jest.fn(),
        controller = new AbortController();
      controller.abort();
      await expect(
        preparePlan(
          { ...planOptions(provider), signal: controller.signal },
          transport,
        ),
      ).rejects.toThrow();
      await expect(
        preparePlan({ ...planOptions(provider), key: 'bad\nkey' }, transport),
      ).rejects.toThrow();
      await expect(
        preparePlan(
          { ...planOptions(provider), model: 'https://arbitrary.example' },
          transport,
        ),
      ).rejects.toThrow();
      await expect(
        preparePlan(
          {
            ...planOptions(provider),
            consent: { provider, processing: false, labels: false },
          },
          transport,
        ),
      ).rejects.toThrow();
      expect(transport).not.toHaveBeenCalled();
    });
    test('a pasted authentication key is never forwarded into prompts or accepted in output', async () => {
      const transport = jest.fn(async () => native(provider, planResult()));
      await expect(
        preparePlan(
          { ...planOptions(provider), message: `${message} ${key}` },
          transport,
        ),
      ).rejects.toThrow();
      expect(transport).not.toHaveBeenCalled();
      await expect(
        ask(
          askOptions(provider),
          groundedGateway(provider, answer => {
            answer.interpretation = key;
          }),
        ),
      ).rejects.toThrow();
    });
    test('late response after cancellation is rejected', async () => {
      const controller = new AbortController();
      await expect(
        preparePlan(
          { ...planOptions(provider), signal: controller.signal },
          async () => {
            controller.abort();
            return native(provider, planResult());
          },
        ),
      ).rejects.toThrow();
    });
  },
);

describe('local tool privacy and scope', () => {
  test('totals exclude future rows/internal transfers, separate credits and retain dates', () => {
    const result = toolSession(
      workspace,
      today,
      false,
    )('summarize_transactions', { from: null, to: null });
    expect(result.moneyOutPence).toBe(1200);
    expect(result.moneyInPence).toBe(300);
    expect(result.potMovementCount).toBe(1);
    expect(result.futureRowsExcluded).toBe(1);
    expect(() =>
      toolSession(
        workspace,
        today,
        false,
      )('summarize_transactions', { from: '2026-12-01', to: '2026-01-01' }),
    ).toThrow();
  });
  test('labels require separate opt-in and source IDs/raw notes never leave tools', () => {
    for (const labels of [false, true]) {
      const result = toolSession(
        workspace,
        today,
        labels,
      )('browse_transactions', { from: null, to: null, offset: 0, limit: 20 });
      const body = JSON.stringify(result);
      expect(body.includes('Private Merchant')).toBe(labels);
      expect(body).not.toContain('private-source-id');
      expect(body).not.toContain('private-note');
      expect(body).not.toContain('private-address');
      expect(body).not.toContain('private-pot');
    }
  });
  test('budget remaining is an allocation and user cash keeps its dated unverified identity', () => {
    const w = {
      ...workspace,
      companionPlan: {
        ...emptyPlan(),
        reportedBalancePence: -500,
        reportedBalanceDate: today,
        commitments: [
          {
            label: 'Private rent',
            dueDate: '2026-11-20',
            amountPence: 12000,
            confirmed: true,
          },
        ],
      },
    };
    const context = toolSession(w, today, false)('planning_context', {});
    expect(context.reportedBalancePence).toBe(-500);
    expect(context.verifiedAffordability).toBe(false);
    expect(JSON.stringify(context)).not.toContain('Private rent');
    const budgets = toolSession(workspace, today, false)('budget_status', {});
    expect((budgets.budgets as Record<string, unknown>[])[0]).toMatchObject({
      remainingPence: 8800,
      remainingIsCash: false,
      essentialByUser: true,
    });
  });
  test('all-history browse retains future-dated rows with explicit observation flags', () => {
    const result = toolSession(
      workspace,
      today,
      false,
    )('browse_transactions', { from: null, to: null, offset: 0, limit: 20 });
    expect(result.total).toBe(4);
    expect((result.items as Record<string, unknown>[])[0]).toMatchObject({
      date: '2027-01-01',
      recordedDateInFuture: true,
      countsAsObservedThroughToday: false,
    });
  });
  test('transaction impact accepts only issued opaque refs and preserves exact local budget math', () => {
    const run = toolSession(workspace, today, false);
    expect(() =>
      run('assess_transaction', { ref: 'private-source-id' }),
    ).toThrow();
    const page = run('browse_transactions', {
      from: null,
      to: null,
      offset: 0,
      limit: 20,
    });
    const row = (page.items as Record<string, unknown>[]).find(
      item => item.date === '2026-11-01',
    )!;
    const impact = run('assess_transaction', { ref: row.ref as string });
    expect(
      (impact.budgetImpacts as Record<string, unknown>[])[0],
    ).toMatchObject({
      beforeRemainingPence: 10000,
      afterRemainingPence: 8800,
      countedDebitPence: 1200,
      remainingIsCash: false,
    });
    expect(JSON.stringify(impact)).not.toContain('private-source-id');
    expect(JSON.stringify(impact)).not.toContain('Groceries');
    expect(() =>
      run('assess_transaction', { ref: 'ev_unissued_999' }),
    ).toThrow();
  });
  test('write tools, excessive pages, unknown fields and unsafe money are rejected', () => {
    const run = toolSession(workspace, today, false);
    expect(() => run('apply_budget', {})).toThrow();
    expect(() =>
      run('browse_transactions', {
        from: null,
        to: null,
        offset: 0,
        limit: 100,
      }),
    ).toThrow();
    expect(() =>
      run('summarize_transactions', { from: null, to: null, notes: true }),
    ).toThrow();
    expect(() =>
      toolSession(
        {
          ...workspace,
          transactions: [{ ...workspace.transactions[0], amount: 1.25 }],
        },
        today,
        false,
      ),
    ).toThrow();
  });
  test('Ask uses the same stable-ID, Pot classification and category matching as the finance domain', () => {
    const rows = [
      { ...workspace.transactions[0], category: '  Groceries  ' },
      { ...workspace.transactions[0], category: '  Groceries  ' },
      {
        ...workspace.transactions[1],
        id: 'ordinary-pot-word',
        type: 'Pottery purchase',
        amount: -200,
        category: 'Groceries',
      },
    ];
    const w = { ...workspace, transactions: rows };
    const run = toolSession(w, today, false);
    expect(
      run('summarize_transactions', { from: null, to: null }).moneyOutPence,
    ).toBe(1400);
    expect(
      (run('budget_status', {}).budgets as Record<string, unknown>[])[0]
        .remainingPence,
    ).toBe(8600);
    const categories = run('category_totals', { from: null, to: null });
    expect(categories.totalCategories).toBe(1);
  });
  test('Pot source names remain private even when merchant/category labels are opted in', () => {
    const run = toolSession(workspace, today, true),
      page = run('browse_transactions', {
        from: null,
        to: null,
        offset: 0,
        limit: 20,
      });
    expect(JSON.stringify(page)).not.toContain('Private Pot');
    const row = (page.items as Record<string, unknown>[]).find(
      item => item.internalMovement,
    )!;
    expect(
      JSON.stringify(run('assess_transaction', { ref: row.ref as string })),
    ).not.toContain('Private Pot');
  });
  test('fact reference cannot traverse prototype or borrow a sibling reference', () => {
    expect(() => pointerWithRefs({ x: 1 }, '/__proto__/toString')).toThrow();
    expect(
      pointerWithRefs(
        {
          items: [
            { value: 1, evidenceRefs: ['row_a'] },
            { value: 2, evidenceRefs: ['row_b'] },
          ],
        },
        '/items/1/value',
      ).refs,
    ).toEqual(['row_b']);
  });
});

describe('protocol refusal and malformed boundaries', () => {
  test('rejects truncated/refused/unknown content, malformed tool arguments and unsupported provider', () => {
    expect(() =>
      protocolReply('openai', {
        choices: [
          {
            finish_reason: 'length',
            message: { role: 'assistant', content: '{}' },
          },
        ],
      }),
    ).toThrow();
    expect(() =>
      protocolReply('openai', {
        choices: [
          {
            finish_reason: 'stop',
            message: { role: 'assistant', content: '{}', refusal: 'no' },
          },
        ],
      }),
    ).toThrow();
    expect(() =>
      protocolReply('anthropic', {
        type: 'message',
        role: 'assistant',
        stop_reason: 'end_turn',
        content: [{ type: 'thinking', text: 'private' }],
      }),
    ).toThrow();
    expect(() =>
      protocolReply('anthropic', {
        type: 'message',
        role: 'assistant',
        stop_reason: 'max_tokens',
        content: [{ type: 'text', text: '{}' }],
      }),
    ).toThrow();
    expect(() =>
      protocolReply('openai', {
        choices: [
          {
            finish_reason: 'tool_calls',
            message: {
              role: 'assistant',
              tool_calls: [
                {
                  id: 'x',
                  type: 'function',
                  function: { name: 'summary', arguments: 'not-json' },
                },
              ],
            },
          },
        ],
      }),
    ).toThrow();
    expect(() =>
      protocolRequest('other' as ProviderId, {
        model,
        tools: [],
        messages: [
          { role: 'system', content: 'JSON' },
          { role: 'user', content: 'question' },
        ],
      }),
    ).toThrow();
  });
});
