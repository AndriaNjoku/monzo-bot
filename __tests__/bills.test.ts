import {
  billCandidates,
  confirmBill,
  readBillSettings,
  setBillEnabled,
} from '../src/domain/bills';
import {
  createWorkspace,
  readPlan,
  workspaceRevision,
  type Workspace,
  type Transaction,
} from '../src/domain';
const today = '2026-11-16';
const row = (
  date: string,
  id = date,
  patch: Partial<Transaction> = {},
): Transaction => ({
  id,
  date,
  name: 'Example energy',
  type: 'Direct Debit',
  category: 'Bills',
  amount: -4500,
  raw: {},
  ...patch,
});
const workspace = (): Workspace =>
  setBillEnabled(
    {
      ...createWorkspace(),
      lastSync: '2026-11-16T08:00:00Z',
      transactions: [row('2026-08-20'), row('2026-09-20'), row('2026-10-20')],
    },
    true,
  );
const fields = {
  label: 'Example energy',
  dueDate: '2026-11-20',
  amountPence: 4500,
};
beforeEach(() => {
  jest.useFakeTimers();
  jest.setSystemTime(new Date('2026-11-16T12:00:00Z'));
});
afterEach(() => jest.useRealTimers());
test('disabled by default; opt in is local and non-mutating', () => {
  const w = workspace();
  delete w.companionBills;
  expect(readBillSettings(w).enabled).toBe(false);
  expect(billCandidates(w, today)).toEqual([]);
  const enabled = setBillEnabled(w, true);
  expect(billCandidates(enabled, today)).toHaveLength(1);
  expect(w.companionBills).toBeUndefined();
  expect(() => setBillEnabled(w, 'yes' as unknown as boolean)).toThrow();
});
test('three monthly observations include exact evidence and estimated date', () => {
  const candidate = billCandidates(workspace(), today)[0];
  expect(candidate).toMatchObject({
    cadence: 'monthly',
    amountPence: 4500,
    estimatedDueDate: '2026-11-20',
    retrievalStatus: 'recent',
    reviewedOn: today,
  });
  expect(candidate.evidence.map(e => e.date)).toEqual([
    '2026-08-20',
    '2026-09-20',
    '2026-10-20',
  ]);
  expect(candidate.explanation).toContain('not a verified bill');
});
test('weekly estimates and month end clamping', () => {
  const w = workspace();
  w.transactions = ['2026-11-02', '2026-11-09', '2026-11-16'].map(d => row(d));
  expect(billCandidates(w, today)[0]).toMatchObject({
    cadence: 'weekly',
    estimatedDueDate: '2026-11-23',
  });
  w.transactions = ['2026-08-31', '2026-09-30', '2026-10-31'].map(d => row(d));
  expect(billCandidates(w, today)[0].estimatedDueDate).toBe('2026-11-30');
});
test.each(['Pot transfer', 'Internal transfer', 'Refund', 'Cash withdrawal'])(
  'excludes %s',
  type => {
    const w = workspace();
    w.transactions = w.transactions.map(t => ({ ...t, type }));
    expect(billCandidates(w, today)).toEqual([]);
  },
);
test('does not infer variable amounts, one-offs, credits, or future records', () => {
  for (const rows of [
    [row('2026-09-20'), row('2026-10-20')],
    [
      row('2026-08-20'),
      row('2026-09-20'),
      row('2026-10-20', 'last', { amount: -5000 }),
    ],
    [row('2026-11-17'), row('2026-11-24'), row('2026-12-01')],
    workspace().transactions.map(t => ({ ...t, amount: 4500 })),
  ])
    expect(
      billCandidates({ ...workspace(), transactions: rows }, today),
    ).toEqual([]);
});
test('deduplicates identical IDs, rejects conflicting ones and ambiguous same-day purchases', () => {
  const w = workspace();
  w.transactions.push({ ...w.transactions[0] });
  expect(billCandidates(w, today)).toHaveLength(1);
  w.transactions.push({ ...w.transactions[0], id: 'other' });
  expect(billCandidates(w, today)).toEqual([]);
  w.transactions.push({ ...w.transactions[0], amount: -9000 });
  expect(() => billCandidates(w, today)).toThrow('Conflicting duplicate');
});
test('foreign original currency excluded; foreign account currency fails closed', () => {
  const w = workspace();
  w.transactions[0].raw = { 'Original currency': 'EUR' };
  expect(billCandidates(w, today)).toEqual([]);
  w.transactions[0].currency = 'EUR';
  expect(() => billCandidates(w, today)).toThrow('Only GBP');
});
test('does not roll discontinued patterns forward', () => {
  const w = workspace();
  w.transactions = ['2026-01-20', '2026-02-20', '2026-03-20'].map(d => row(d));
  expect(billCandidates(w, today)).toEqual([]);
});
test('explicit confirmation saves edited local commitment, audit marker and preserves transactions', () => {
  const w = workspace(),
    before = JSON.stringify(w),
    c = billCandidates(w, today)[0];
  const next = confirmBill(
    w,
    c.id,
    { ...fields, amountPence: 4700 },
    workspaceRevision(w),
    today,
  );
  expect(readPlan(next).commitments).toEqual([
    { ...fields, amountPence: 4700, confirmed: true },
  ]);
  expect(readPlan(next).commitmentsComplete).toBe(false);
  expect(next.transactions).toEqual(w.transactions);
  expect(JSON.stringify(w)).toBe(before);
  expect(readBillSettings(next).confirmedCandidateIds).toContain(c.evidenceId);
  expect(() =>
    confirmBill(next, c.id, fields, workspaceRevision(next), today),
  ).toThrow();
});
test('rejects stale source, changed source, next-day review, revoked permission, and invented IDs', () => {
  const w = workspace(),
    c = billCandidates(w, today)[0],
    revision = workspaceRevision(w);
  expect(() =>
    confirmBill(
      { ...w, lastSync: '2026-11-10T12:00:00Z' },
      c.id,
      fields,
      revision,
      today,
    ),
  ).toThrow('changed');
  const stale = { ...w, lastSync: '2026-11-10T12:00:00Z' };
  expect(billCandidates(stale, today)[0].retrievalStatus).toBe('stale');
  expect(() =>
    confirmBill(stale, c.id, fields, workspaceRevision(stale), today),
  ).toThrow('Refresh');
  expect(() => confirmBill(w, c.id, fields, revision, '2026-11-17')).toThrow(
    'expired',
  );
  expect(() =>
    confirmBill(setBillEnabled(w, false), c.id, fields, revision, today),
  ).toThrow('disabled');
  expect(() => confirmBill(w, 'invented', fields, revision, today)).toThrow(
    'expired',
  );
});
test.each([undefined, 'garbage', '2026-11-17T12:00:00Z'])(
  'unknown/future retrieval blocks confirmation: %s',
  lastSync => {
    const w = { ...workspace(), lastSync },
      c = billCandidates(w, today)[0];
    expect(c.retrievalStatus).toBe('unknown');
    expect(() =>
      confirmBill(w, c.id, fields, workspaceRevision(w), today),
    ).toThrow('Refresh');
  },
);
test('validates edits and avoids duplicates even when amount edited', () => {
  const w = workspace(),
    c = billCandidates(w, today)[0],
    revision = workspaceRevision(w);
  for (const edit of [
    { amountPence: -1 },
    { amountPence: 1.5 },
    { dueDate: '2026-11-01' },
    { dueDate: '2028-01-01' },
    { label: '' },
  ])
    expect(() =>
      confirmBill(w, c.id, { ...fields, ...edit }, revision, today),
    ).toThrow();
  const next = confirmBill(w, c.id, fields, revision, today);
  next.companionBills = { enabled: true, confirmedCandidateIds: [] };
  expect(() =>
    confirmBill(
      next,
      c.id,
      { ...fields, amountPence: 1 },
      workspaceRevision(next),
      today,
    ),
  ).toThrow('already exists');
});
test.each([
  null,
  true,
  { enabled: 'true', confirmedCandidateIds: [] },
  { enabled: true, confirmedCandidateIds: ['bad'] },
])('malformed permissions fail closed', companionBills => {
  expect(() =>
    billCandidates({ ...workspace(), companionBills }, today),
  ).toThrow('settings');
});

test('same evidence cannot be confirmed again next day even with edited label/date', () => {
  const w = workspace(),
    c = billCandidates(w, today)[0];
  const next = confirmBill(w, c.id, fields, workspaceRevision(w), today);
  expect(billCandidates(next, '2026-11-17')).toEqual([]);
  expect(() =>
    confirmBill(
      next,
      c.id,
      { ...fields, label: 'Renamed', dueDate: '2026-11-22' },
      workspaceRevision(next),
      '2026-11-17',
    ),
  ).toThrow();
});
