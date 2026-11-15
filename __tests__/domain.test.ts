import { createHash } from 'node:crypto';
import {
  add,
  applyProposal,
  assessAffordability,
  assessTransaction,
  budgetStatus,
  calendarMonth,
  categoryEmoji,
  createWorkspace,
  demoWorkspace,
  effectiveCategory,
  fixedPayCycle,
  freshness,
  isoDate,
  money,
  monthlyPayCycle,
  overview,
  parseDraft,
  parseMoney,
  proposalHash,
  readPlan,
  records,
  removeBudget,
  reviewFlags,
  reviewProposal,
  searchHistory,
  sha256,
  sourceToken,
  transactionEmoji,
  updateContext,
  upsertBudget,
  upsertCommitment,
  validateEmoji,
  validateWorkspace,
  workspaceRevision,
  MAX_PENCE,
  type Workspace,
  type Transaction,
  type Proposal,
} from '../src/domain';
const DAY = '2026-11-15',
  NOW = '2026-11-15T12:00:00Z';
function row(
  id: string,
  amount = -100,
  date = DAY,
  category = 'Groceries',
  name = 'Synthetic market',
  type = 'Card payment',
): Transaction {
  return {
    id,
    date,
    amount,
    category,
    name,
    type,
    raw: { Emoji: '🛒', 'Notes and #tags': 'Synthetic only' },
  };
}
function workspace(rows: Transaction[] = []): Workspace {
  return {
    ...createWorkspace(),
    workspaceId: 'synthetic-workspace',
    transactions: rows,
    lastSync: NOW,
  };
}
function proposal(
  w: Workspace,
  amount = 12000,
  mode: 'ADD_ALLOCATION' | 'WITHIN_EXISTING_ALLOCATION' = 'ADD_ALLOCATION',
  from = DAY,
  to = DAY,
  name = 'Bicycle',
  category = 'Shopping',
): Proposal {
  return reviewProposal(
    parseDraft(
      `I might buy a ${name} for £${(amount / 100).toFixed(2)} on ${from}`,
      DAY,
    ),
    { name, category, from, to, amountPence: amount, mode },
    DAY,
  );
}
function save(w: Workspace, p: Proposal): Workspace {
  return applyProposal(
    w,
    p,
    assessAffordability(w, p, DAY, NOW),
    true,
    DAY,
    NOW,
  );
}

describe('money precision and explicit dates', () => {
  test.each([
    ['0', 0],
    ['12', 1200],
    ['12.30', 1230],
    ['-12.01', -1201],
    ['+1.2', 120],
    ['90000000000000.00', MAX_PENCE],
  ])('decimal %s is exact pence', (raw, expected) =>
    expect(parseMoney(raw)).toBe(expected),
  );
  test.each([
    '1.234',
    '1,000',
    '£12',
    'NaN',
    'Infinity',
    '1e2',
    '',
    '90000000000000.01',
  ])('rejects unsupported money %s', v =>
    expect(() => parseMoney(v)).toThrow(),
  );
  test('formats signed GBP without floating point loss', () => {
    expect(money(-125050)).toBe('-£1,250.50');
    expect(money(0)).toBe('£0.00');
    expect(money(MAX_PENCE)).toBe('£90,000,000,000,000.00');
  });
  test('arithmetic throws instead of silently rounding or overflowing', () => {
    expect(add(100, -30, 40)).toBe(110);
    expect(() => add(MAX_PENCE, 1)).toThrow();
    expect(() => add(0.1)).toThrow();
    expect(() => money(Number.MAX_SAFE_INTEGER)).toThrow();
  });
  test.each([
    '2026-02-29',
    '2026-13-01',
    '2026-1-01',
    '0000-01-01',
    'not a date',
  ])('rejects invalid explicit date %s', d =>
    expect(() => isoDate(d)).toThrow(),
  );
  test('month and explicit payday periods clamp calendar dates', () => {
    expect(calendarMonth('2024-02-15')).toEqual({
      start: '2024-02-01',
      end: '2024-02-29',
    });
    expect(calendarMonth('2026-12-20', 1)).toEqual({
      start: '2027-01-01',
      end: '2027-01-31',
    });
    expect(monthlyPayCycle('2026-02-28', 31)).toEqual({
      start: '2026-02-28',
      end: '2026-03-30',
    });
    expect(fixedPayCycle('2026-11-10', 14, '2026-11-01')).toEqual({
      start: '2026-10-27',
      end: '2026-11-09',
    });
  });
});

describe('full source history and category presentation', () => {
  test('all history remains searchable independently of budget dates', () => {
    const rows = Array.from({ length: 1007 }, (_, i) =>
      row(
        `synthetic-${i}`,
        -100,
        i === 0 ? '2020-01-01' : DAY,
        'Groceries',
        i === 0 ? 'Ancient bicycle' : 'Example market',
      ),
    );
    const w = upsertBudget(workspace(rows), -1, {
      category: 'Groceries',
      start: DAY,
      end: DAY,
      limitPence: 2000,
      essential: true,
    });
    expect(overview(w).transactionCount).toBe(1007);
    expect(searchHistory(w, 'ancient')).toHaveLength(1);
    expect(searchHistory(w, 'synthetic only')).toHaveLength(1007);
    expect(searchHistory(w, '')).toHaveLength(1007);
    expect(w.transactions).toHaveLength(1007);
  });
  test('unclassified credits and internal Pot transfers stay separate', () => {
    const w = workspace([
      row('debit', -1000),
      row('credit', 300),
      row('pot-out', -9000, DAY, 'Savings', 'Example Pot', 'Pot transfer'),
      row('pot-in', 4000, DAY, 'Savings', 'Example Pot', ' POT  TRANSFER '),
    ]);
    const o = overview(w);
    expect(o).toMatchObject({
      moneyOut: 1000,
      moneyIn: 300,
      potMoneyOut: 9000,
      potMoneyIn: 4000,
      potMovementCount: 2,
    });
    expect(o.categoryDebits).toEqual({ Groceries: 1000 });
  });
  test('source transaction emoji wins over category customization', () => {
    const t = row('a');
    const w = {
      ...workspace([t]),
      categoryEmojiOverrides: { groceries: '🍏' },
    };
    expect(transactionEmoji(t, w)).toBe('🛒');
    expect(categoryEmoji('Groceries', w.categoryEmojiOverrides)).toBe('🍏');
    expect(transactionEmoji({ ...t, raw: {} }, w)).toBe('🍏');
    expect(categoryEmoji('Transport')).toBe('🚆');
    expect(categoryEmoji('constructor')).toEqual(expect.any(String));
  });
  test.each(['🏳️‍🌈', '👩🏽‍💻', '🇬🇧', '1️⃣', '🛒'])('supports emoji sequence %s', e =>
    expect(validateEmoji(e)).toBe(e),
  );
  test.each(['Groceries', '💰password', '123', '', String.fromCharCode(0)])(
    'rejects text/settings control as emoji',
    e => expect(() => validateEmoji(e)).toThrow(),
  );
  test('category override preserves original source evidence', () => {
    const w = { ...workspace([row('a')]), overrides: { a: 'Transport' } };
    expect(effectiveCategory(w.transactions[0], w)).toBe('Transport');
    expect(w.transactions[0].category).toBe('Groceries');
  });
  test('prototype-like source categories and identifiers are ordinary data', () => {
    const w = workspace([
      row('constructor', -100, DAY, 'constructor'),
      row('__proto__', -200, DAY, '__proto__'),
    ]);
    const o = overview(w);
    expect(o.categoryDebits.constructor).toBe(100);
    expect(o.categoryDebits.__proto__).toBe(200);
    expect(effectiveCategory(w.transactions[0], w)).toBe('constructor');
  });
  test('identical IDs deduplicate; conflicting IDs fail without losing evidence', () => {
    const t = row('same');
    expect(overview(workspace([t, { ...t }])).transactionCount).toBe(1);
    expect(() =>
      validateWorkspace(workspace([t, { ...t, amount: -101 }])),
    ).toThrow(/Conflicting/);
    expect(() =>
      validateWorkspace(workspace([{ ...t, amount: 0.1 }])),
    ).toThrow();
  });
});

describe('budgets, manual context and cautious payment review', () => {
  test('new budgets and legacy budgets decode without invented cash', () => {
    const w = {
      ...workspace(),
      companionPlan: undefined,
      start: DAY,
      end: DAY,
      budgets: { Groceries: 5000 },
    };
    expect(readPlan(w).budgets[0].limitPence).toBe(5000);
    expect(readPlan(w).reportedBalancePence).toBeNull();
    expect(readPlan(w).commitmentsComplete).toBe(false);
  });
  test('overlapping category dates fail; separated periods remain valid', () => {
    const w = upsertBudget(workspace(), -1, {
      category: 'Groceries',
      start: DAY,
      end: '2026-11-20',
      limitPence: 5000,
      essential: true,
    });
    expect(() =>
      upsertBudget(w, -1, {
        category: ' groceries ',
        start: '2026-11-20',
        end: '2026-11-25',
        limitPence: 5000,
        essential: false,
      }),
    ).toThrow(/overlap/);
    expect(
      readPlan(
        upsertBudget(w, -1, {
          category: 'Groceries',
          start: '2026-11-21',
          end: '2026-11-25',
          limitPence: 5000,
          essential: false,
        }),
      ).budgets,
    ).toHaveLength(2);
  });
  test('budget impact uses gross debits and keeps credits outside remaining allocation', () => {
    const w = upsertBudget(
      workspace([row('debit', -1000), row('credit', 500)]),
      -1,
      {
        category: 'Groceries',
        start: DAY,
        end: DAY,
        limitPence: 3000,
        essential: true,
      },
    );
    expect(budgetStatus(w, DAY)[0]).toMatchObject({
      remainingPence: 2000,
      creditsPence: 500,
      grossDebitsPence: 1000,
      essential: true,
    });
    expect(assessTransaction(w, 'debit', DAY).budgetImpacts[0]).toMatchObject({
      beforeRemainingPence: 3000,
      afterRemainingPence: 2000,
    });
    expect(assessTransaction(w, 'credit', DAY).creditUnclassified).toBe(true);
  });
  test('future transactions never inflate a current budget calculation', () => {
    const w = upsertBudget(
      workspace([row('future', -2000, '2026-11-16')]),
      -1,
      {
        category: 'Groceries',
        start: DAY,
        end: '2026-11-30',
        limitPence: 3000,
        essential: false,
      },
    );
    expect(budgetStatus(w, DAY)[0].grossDebitsPence).toBe(0);
  });
  test('updating commitments invalidates declared completeness', () => {
    const w = updateContext(workspace(), {
      reportedBalancePence: 100000,
      reportedBalanceDate: DAY,
      commitmentsComplete: true,
    });
    const changed = upsertCommitment(w, -1, {
      label: 'Rent',
      dueDate: '2026-11-20',
      amountPence: 50000,
      confirmed: false,
    });
    expect(readPlan(changed).commitmentsComplete).toBe(false);
    expect(readPlan(changed).commitments[0].confirmed).toBe(false);
    expect(w.companionPlan!.commitments).toHaveLength(0);
  });
  test('deleting local budget retains source transactions and unknown fields', () => {
    const w = upsertBudget(
      { ...workspace([row('a')]), futurePrivateSetting: { keep: true } },
      -1,
      {
        category: 'Groceries',
        start: DAY,
        end: DAY,
        limitPence: 5000,
        essential: true,
      },
    );
    const next = removeBudget(w, 0);
    expect(next.transactions).toEqual(w.transactions);
    expect(next.futurePrivateSetting).toEqual({ keep: true });
  });
  test('payment anomalies are review candidates never confirmed fraud', () => {
    const prior = Array.from({ length: 5 }, (_, i) =>
      row(`old-${i}`, -2000, `2026-10-${String(i + 1).padStart(2, '0')}`),
    );
    const w = workspace([
      ...prior,
      row('large', -12000),
      row('repeat-a', -500, DAY, 'Groceries', 'Example double'),
      row('repeat-b', -500, DAY, 'Groceries', 'Example double'),
    ]);
    const flags = reviewFlags(w, DAY);
    expect(flags.map(f => f.kind)).toEqual([
      'possible_duplicate',
      'unusually_large_debit',
    ]);
    expect(flags.every(f => f.confirmedFraud === false)).toBe(true);
    expect(flags.some(f => f.evidenceTransactionIds.includes('large'))).toBe(
      true,
    );
  });
});

describe('tentative message parsing and unresolved fields', () => {
  test('single explicit GBP and relative month scope is deterministic', () => {
    const d = parseDraft('Next month I might buy a bicycle for £120', DAY);
    expect(d).toMatchObject({
      amountPence: 12000,
      from: '2026-12-01',
      to: '2026-12-31',
      tentative: true,
    });
    expect(d.originalText).toBe('Next month I might buy a bicycle for £120');
  });
  test.each([
    'I want something',
    'About £120 this month',
    '£120 or £140 this month',
    '£120-150 this month',
    '£120.001 this month',
    '£1,20 this month',
    '£-120 this month',
    '£120 and €20 this month',
    'I owe £120 this month',
    'Refund £120 this month',
    'Cancel buying this for £120 this month',
    'I do not want £120 this month',
    '£1200% this month',
  ])('keeps ambiguous amount unresolved: %s', s =>
    expect(parseDraft(s, DAY).amountPence).toBeNull(),
  );
  test.each([
    'tomorrow',
    '2026-02-29',
    '15/11/2026',
    'next Monday',
    'this month or next month',
    '2026-11-15 or 2026-11-20',
  ])('does not invent a date from %s', d => {
    const result = parseDraft(`Buy a book for £12 ${d}`, DAY);
    expect(result.from).toBeNull();
    expect(result.to).toBeNull();
  });
  test('ordered explicit date scope is retained; original intent stays tentative', () => {
    const d = parseDraft('Maybe £35 from 2026-11-20 to 2026-11-25', DAY);
    expect(d).toMatchObject({
      amountPence: 3500,
      from: '2026-11-20',
      to: '2026-11-25',
      tentative: true,
    });
  });
});

describe('review, provenance and durable duplicate protection', () => {
  test('no mutation before explicit confirmation', () => {
    const w = workspace(),
      p = proposal(w),
      r = assessAffordability(w, p, DAY, NOW);
    expect(w.conversationBudgets).toBeUndefined();
    expect(() => applyProposal(w, p, r, false, DAY, NOW)).toThrow(
      /confirmation/,
    );
    expect(r).toMatchObject({
      canSave: true,
      cannotConfirmAffordability: true,
      bankCoverageKnown: false,
    });
  });
  test('add allocation reserves purchase once and replay does not add it twice', () => {
    const w = workspace(),
      p = proposal(w),
      next = save(w, p);
    expect(readPlan(next).budgets[0].limitPence).toBe(12000);
    expect(records(next)).toHaveLength(1);
    const after = assessAffordability(next, p, DAY, NOW);
    expect(after).toMatchObject({
      alreadySaved: true,
      allocationLimitAfterPence: 12000,
      allocationRemainingAfterPence: 0,
    });
    expect(applyProposal(next, p, after, true, DAY, NOW)).toEqual(next);
    expect(w.conversationBudgets).toBeUndefined();
  });
  test('same token changed payload is rejected', () => {
    const w = workspace(),
      p = proposal(w),
      next = save(w, p);
    expect(() =>
      applyProposal(
        next,
        { ...p, amountPence: 13000 },
        assessAffordability(w, p, DAY, NOW),
        true,
        DAY,
        NOW,
      ),
    ).toThrow(/different fields/);
  });
  test('within-existing allocation provisions leave limit unchanged', () => {
    let w = upsertBudget(workspace(), -1, {
      category: 'Shopping',
      start: DAY,
      end: DAY,
      limitPence: 20000,
      essential: true,
    });
    w = save(w, proposal(w, 12000, 'WITHIN_EXISTING_ALLOCATION'));
    expect(readPlan(w).budgets[0].limitPence).toBe(20000);
    const p = proposal(w, 9000, 'WITHIN_EXISTING_ALLOCATION');
    expect(assessAffordability(w, p, DAY, NOW)).toMatchObject({
      existingReservedPence: 12000,
      allocationRemainingAfterPence: -1000,
      canSave: false,
    });
    expect(() => save(w, p)).toThrow();
  });
  test('possible recorded payment overlap withholds allocation/cash and confirmation', () => {
    const w = workspace([row('paid', -12000, DAY, 'Shopping', 'Bicycle')]),
      p = proposal(w),
      r = assessAffordability(w, p, DAY, NOW);
    expect(r).toMatchObject({
      overlapAmbiguous: true,
      canSave: false,
      allocationRemainingAfterPence: null,
      conditionalCashAfterPence: null,
    });
    expect(() => applyProposal(w, p, r, true, DAY, NOW)).toThrow();
  });
  test('partial provision date overlap is not silently reshaped', () => {
    let w = workspace();
    const first = proposal(w, 12000, 'ADD_ALLOCATION', DAY, '2026-11-20');
    w = save(w, first);
    const second = proposal(
      w,
      5000,
      'ADD_ALLOCATION',
      '2026-11-18',
      '2026-11-25',
      'Book',
    );
    expect(assessAffordability(w, second, DAY, NOW)).toMatchObject({
      overlapAmbiguous: true,
      canSave: false,
    });
  });
  test('stale source/review day, expiry and changed fields cannot apply', () => {
    const w = workspace(),
      p = proposal(w),
      r = assessAffordability(w, p, DAY, NOW);
    expect(() =>
      applyProposal(
        { ...w, lastSync: '2026-11-15T12:01:00Z' },
        p,
        r,
        true,
        DAY,
        NOW,
      ),
    ).toThrow(/changed/);
    expect(() =>
      applyProposal(w, p, r, true, DAY, '2026-11-15T12:06:00Z'),
    ).toThrow(/expired/);
    expect(() =>
      applyProposal(w, p, r, true, '2026-11-16', '2026-11-16T12:00:00Z'),
    ).toThrow(/changed/);
    expect(() =>
      applyProposal(w, { ...p, name: 'Different' }, r, true, DAY, NOW),
    ).toThrow(/changed/);
  });
  test('freshness transition invalidates an otherwise unexpired review', () => {
    const w = { ...workspace(), lastSync: '2026-11-14T12:01:00Z' },
      p = proposal(w),
      r = assessAffordability(w, p, DAY, NOW);
    expect(r.retrievalStatus).toBe('recent');
    expect(() =>
      applyProposal(w, p, r, true, DAY, '2026-11-15T12:02:00Z'),
    ).toThrow(/freshness/);
  });
  test('source-switch preserves ledger but blocks reuse under a different export', () => {
    const w = workspace(),
      p = proposal(w),
      next = save(w, p),
      switched = { ...next, spreadsheetId: 'synthetic-other-export' };
    expect(switched.conversationBudgets).toEqual(next.conversationBudgets);
    expect(() =>
      assessAffordability(switched, proposal(switched), DAY, NOW),
    ).toThrow(/another export/);
  });
  test('legacy Java replay hash and source token remain exact compatible SHA-256', () => {
    const p = {
      ...proposal(workspace()),
      id: '00000000-0000-4000-8000-000000000001',
      originalText: 'Maybe buy 🚲 for £120',
    };
    const hash = createHash('sha256');
    for (const v of [
      'conversation-budget-v1',
      p.id,
      p.originalText,
      p.name,
      p.category,
      p.from,
      p.to,
      String(p.amountPence),
      p.mode,
      p.reviewedOn,
    ]) {
      const bytes = Buffer.from(v, 'utf8'),
        length = Buffer.alloc(4);
      length.writeUInt32BE(bytes.length);
      hash.update(length);
      hash.update(bytes);
    }
    expect(proposalHash(p)).toBe(hash.digest('hex'));
    const w = workspace();
    expect(sourceToken(w)).toBe(
      createHash('sha256')
        .update('synthetic-workspace\0private\0')
        .digest('hex'),
    );
    const next = save(w, p);
    expect(validateWorkspace(next).conversationBudgets).toEqual(
      next.conversationBudgets,
    );
  });
  test('bad existing ledger never disappears on validation/edit', () => {
    const w = workspace();
    w.conversationBudgets = {
      version: 1,
      records: [
        {
          proposal: proposal(w),
          payloadSha256: 'tampered',
          status: 'active',
          budgetLimitBeforePence: 0,
          budgetLimitAfterPence: 12000,
        },
      ],
    };
    expect(() => validateWorkspace(w)).toThrow(/replay/);
    expect(w.conversationBudgets.records).toHaveLength(1);
  });
  test('delivery and provider metadata changes do not invalidate a financial review', () => {
    const w = workspace(),
      r = workspaceRevision(w);
    expect(
      workspaceRevision({
        ...w,
        companionAlertLedger: { lastSent: 'synthetic' },
        companionModels: { openai: 'example' },
      }),
    ).toBe(r);
    expect(workspaceRevision({ ...w, transactions: [row('new')] })).not.toBe(r);
  });
});

describe('cash uncertainty and liabilities', () => {
  function cash(balance = 100000): Workspace {
    return updateContext(workspace(), {
      reportedBalancePence: balance,
      reportedBalanceDate: DAY,
      reservePence: 10000,
      commitmentsComplete: true,
    });
  }
  test('reported positive cash subtracts provisions once, separate from allocation increases', () => {
    const w = cash(),
      p = proposal(w),
      r = assessAffordability(w, p, DAY, NOW);
    expect(r.conditionalCashAfterPence).toBe(78000);
    const next = save(w, p);
    expect(
      assessAffordability(next, p, DAY, NOW).conditionalCashAfterPence,
    ).toBe(78000);
    expect(r.cannotConfirmAffordability).toBe(true);
  });
  test('overdraft is clamped to zero available cash and leaves debt unresolved', () => {
    const w = cash(-2500),
      r = assessAffordability(w, proposal(w), DAY, NOW);
    expect(r).toMatchObject({
      negativeReportedBalance: true,
      availablePositiveReportedBalancePence: 0,
      conditionalCashAfterPence: null,
      conditionalCashShortfallPence: null,
    });
  });
  test('future purchase does not project current cash or invent salary', () => {
    const w = cash(),
      p = proposal(w, 12000, 'ADD_ALLOCATION', '2026-12-01', '2026-12-31');
    expect(
      assessAffordability(w, p, DAY, NOW).conditionalCashAfterPence,
    ).toBeNull();
  });
  test.each([
    {
      label: 'Past bill',
      dueDate: '2026-11-14',
      amountPence: 5000,
      confirmed: true,
    },
    { label: 'Maybe owed', dueDate: DAY, amountPence: 5000, confirmed: false },
  ])('overdue/unconfirmed liabilities withhold cash: %s', c => {
    let w = upsertCommitment(cash(), -1, c);
    w = updateContext(w, { commitmentsComplete: true });
    expect(
      assessAffordability(w, proposal(w), DAY, NOW).conditionalCashAfterPence,
    ).toBeNull();
  });
  test('same-name/amount commitment overlap is unresolved, not double-counted', () => {
    let w = upsertCommitment(cash(), -1, {
      label: 'Bicycle',
      dueDate: DAY,
      amountPence: 12000,
      confirmed: true,
    });
    w = updateContext(w, { commitmentsComplete: true });
    expect(assessAffordability(w, proposal(w), DAY, NOW)).toMatchObject({
      overlapAmbiguous: true,
      conditionalCashAfterPence: null,
    });
  });
  test('stale or incomplete manual cash inputs withhold combined figure', () => {
    for (const fields of [
      { reportedBalanceDate: '2026-11-14' },
      { commitmentsComplete: false },
    ]) {
      const w = updateContext(cash(), fields);
      expect(
        assessAffordability(w, proposal(w), DAY, NOW).conditionalCashAfterPence,
      ).toBeNull();
    }
  });
  test('cutoff excludes future rows and recent retrieval still does not prove bank coverage', () => {
    const w = workspace([
      row('old', -500, '2026-11-10'),
      row('future', -500, '2026-11-20'),
    ]);
    const r = assessAffordability(w, proposal(w), DAY, NOW);
    expect(r.sourceCutoffDate).toBe('2026-11-10');
    expect(r.bankCoverageKnown).toBe(false);
    expect(r.warnings.some(x => x.includes('does not prove'))).toBe(true);
  });
  test('retrieval timestamps reject unknown offsets, invalid/future dates', () => {
    expect(freshness(NOW, NOW)).toBe('recent');
    expect(freshness('2026-11-14T11:59:59Z', NOW)).toBe('stale');
    for (const bad of [
      '2026-11-15',
      '2026-11-15T12:00:00',
      '2026-02-30T12:00:00Z',
      '2026-11-16T12:00:00Z',
      'bad',
    ])
      expect(freshness(bad, NOW)).toBe('unknown');
  });
});

describe('synthetic fixtures and SHA compatibility', () => {
  test('private constructor has no invented cash or connection', () => {
    const w = createWorkspace();
    expect(w.mode).toBe('private');
    expect(w.transactions).toEqual([]);
    expect(readPlan(w).reportedBalancePence).toBeNull();
    expect(w.spreadsheetId).toBeUndefined();
  });
  test('demo remains explicitly synthetic and passes same validation', () => {
    const w = demoWorkspace(DAY);
    expect(w.mode).toBe('demo');
    expect(validateWorkspace(w).transactions.length).toBeGreaterThan(0);
    expect(w.transactions.every(t => t.id.startsWith('synthetic-'))).toBe(true);
  });
  test.each(['', 'abc', '🚲 £12', 'x'.repeat(1000)])(
    'SHA-256 matches independent Node crypto',
    s => expect(sha256(s)).toBe(createHash('sha256').update(s).digest('hex')),
  );
});
