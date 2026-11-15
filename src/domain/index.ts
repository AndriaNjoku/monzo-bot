/** Deterministic finance domain. No networking, credentials, UI or financial actions. */
import { canonical, sha256, utf8 } from './hash';
export { sha256 } from './hash';
export const MAX_PENCE = 9_000_000_000_000_000;
export const MAX_TRANSACTIONS = 100_000;
export type Transaction = {
  id: string;
  date: string;
  name: string;
  category: string;
  type: string;
  amount: number;
  raw: Record<string, string>;
  currency?: string;
};
export type Budget = {
  category: string;
  start: string;
  end: string;
  limitPence: number;
  essential: boolean;
};
export type Commitment = {
  label: string;
  dueDate: string;
  amountPence: number;
  confirmed: boolean;
};
export type Plan = {
  version: 1;
  currency: 'GBP';
  budgets: Budget[];
  commitments: Commitment[];
  reportedBalancePence: number | null;
  reportedBalanceDate: string;
  reservePence: number;
  commitmentsComplete: boolean;
  priorities: string[];
};
export type Mode = 'ADD_ALLOCATION' | 'WITHIN_EXISTING_ALLOCATION';
export type Draft = {
  originalText: string;
  amountPence: number | null;
  from: string | null;
  to: string | null;
  issues: string[];
  tentative: true;
};
export type ProposalFields = {
  name: string;
  category: string;
  from: string;
  to: string;
  amountPence: number;
  mode: Mode;
};
export type Proposal = ProposalFields & {
  id: string;
  originalText: string;
  reviewedOn: string;
  tentative: true;
};
export type PlanningRecord = {
  proposal: Proposal;
  payloadSha256: string;
  status: 'active' | 'cancelled';
  budgetLimitBeforePence: number;
  budgetLimitAfterPence: number;
};
export type Ledger = {
  version: 1;
  records: PlanningRecord[];
  reviewSourceToken?: string;
};
export type NotificationSettings = {
  enabled: boolean;
  persistent: boolean;
  quietStart: number;
  quietEnd: number;
  thresholdPercent: number;
};
export type Workspace = {
  transactions: Transaction[];
  overrides?: Record<string, string>;
  categoryEmojiOverrides?: Record<string, string>;
  companionPlan?: Plan;
  conversationBudgets?: Ledger;
  companionNotifications?: NotificationSettings;
  lastSync?: string;
  mode?: string;
  workspaceId?: string;
  spreadsheetId?: string;
  budgets?: Record<string, number>;
  start?: string;
  end?: string;
  [key: string]: unknown;
};
export function copy<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}
export function normal(value: string | undefined): string {
  return (value ?? '').trim().replace(/\s+/g, ' ').toLowerCase();
}
function fail(message: string): never {
  throw new Error(message);
}
export function integer(value: unknown, label = 'Amount'): number {
  if (
    typeof value !== 'number' ||
    !Number.isSafeInteger(value) ||
    Math.abs(value) > MAX_PENCE
  )
    fail(label + ' must be safe integer GBP pence');
  return value;
}
function positive(value: unknown, label = 'Amount'): number {
  const result = integer(value, label);
  if (result <= 0) fail(label + ' must be positive');
  return result;
}
function nonnegative(value: unknown, label = 'Amount'): number {
  const result = integer(value, label);
  if (result < 0) fail(label + ' must not be negative');
  return result;
}
export function add(...values: number[]): number {
  let result = 0n;
  for (const v of values) result += BigInt(integer(v));
  if (result > BigInt(MAX_PENCE) || result < -BigInt(MAX_PENCE))
    fail('Money arithmetic exceeds supported precision');
  return Number(result);
}
export function parseMoney(value: string): number {
  if (
    typeof value !== 'string' ||
    !/^[-+]?\d+(?:\.\d{1,2})?$/.test(value.trim())
  )
    fail('Use a decimal GBP amount with at most two decimal places');
  const s = value.trim(),
    negative = s.startsWith('-'),
    unsigned = s.replace(/^[-+]/, '');
  const [whole, fraction = ''] = unsigned.split('.');
  const result = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
  return integer(Number(negative ? -result : result));
}
export const minor = parseMoney;
export function money(pence: number): string {
  integer(pence);
  const v = BigInt(pence),
    abs = v < 0n ? -v : v;
  const whole = (abs / 100n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${v < 0n ? '-' : ''}£${whole}.${(abs % 100n)
    .toString()
    .padStart(2, '0')}`;
}
export function isoDate(value: string): string {
  if (
    typeof value !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}$/.test(value) ||
    value < '0001-01-01'
  )
    fail('Use an explicit yyyy-MM-dd date');
  const d = new Date(value + 'T00:00:00.000Z');
  if (!Number.isFinite(d.getTime()) || d.toISOString().slice(0, 10) !== value)
    fail('Invalid calendar date');
  return value;
}
export function todayISO(now = new Date()): string {
  const y = now.getFullYear(),
    m = String(now.getMonth() + 1).padStart(2, '0'),
    d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}
export function daysBetween(from: string, to: string): number {
  return (
    (Date.parse(isoDate(to) + 'T00:00:00Z') -
      Date.parse(isoDate(from) + 'T00:00:00Z')) /
    86400000
  );
}
export function plusDays(value: string, days: number): string {
  if (!Number.isSafeInteger(days)) fail('Invalid day offset');
  return new Date(Date.parse(isoDate(value) + 'T00:00:00Z') + days * 86400000)
    .toISOString()
    .slice(0, 10);
}
function label(value: string): string {
  if (
    typeof value !== 'string' ||
    !value.trim() ||
    value.trim().length > 120 ||
    /[\u0000-\u001f\u007f]/.test(value)
  )
    fail('Labels must contain one to 120 visible characters');
  return value.trim();
}
function text(value: string): string {
  if (typeof value !== 'string' || !value.trim() || value.length > 8192)
    fail('Use a short planning message');
  return value;
}
function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function bool(value: unknown): boolean {
  if (typeof value !== 'boolean')
    fail('An explicit true/false value is required');
  return value;
}
export function validateBudget(b: Budget): Budget {
  if (!object(b)) fail('Invalid budget');
  const start = isoDate(b.start),
    end = isoDate(b.end);
  if (daysBetween(start, end) < 0 || daysBetween(start, end) > 3660)
    fail('Budget dates must span at most ten years');
  return {
    category: label(b.category),
    start,
    end,
    limitPence: positive(b.limitPence),
    essential: bool(b.essential),
  };
}
export function validateCommitment(c: Commitment): Commitment {
  if (!object(c)) fail('Invalid commitment');
  return {
    label: label(c.label),
    dueDate: isoDate(c.dueDate),
    amountPence: positive(c.amountPence),
    confirmed: bool(c.confirmed),
  };
}
export function emptyPlan(): Plan {
  return {
    version: 1,
    currency: 'GBP',
    budgets: [],
    commitments: [],
    reportedBalancePence: null,
    reportedBalanceDate: '',
    reservePence: 0,
    commitmentsComplete: false,
    priorities: [],
  };
}
export function validatePlan(input: Plan): Plan {
  if (
    !object(input) ||
    input.version !== 1 ||
    input.currency !== 'GBP' ||
    !Array.isArray(input.budgets) ||
    !Array.isArray(input.commitments) ||
    !Array.isArray(input.priorities)
  )
    fail('Unsupported saved plan; original data is preserved');
  if (
    input.budgets.length > 100 ||
    input.commitments.length > 500 ||
    input.priorities.length > 30
  )
    fail('Planning limits exceeded');
  const p: Plan = {
    ...input,
    budgets: input.budgets.map(validateBudget),
    commitments: input.commitments.map(validateCommitment),
    priorities: input.priorities.map(label),
    reportedBalancePence:
      input.reportedBalancePence === null
        ? null
        : integer(input.reportedBalancePence),
    reportedBalanceDate: input.reportedBalanceDate,
    reservePence: nonnegative(input.reservePence),
    commitmentsComplete: bool(input.commitmentsComplete),
  };
  if (p.reportedBalancePence === null && p.reportedBalanceDate !== '')
    fail('A balance date requires a reported amount');
  if (p.reportedBalancePence !== null) isoDate(p.reportedBalanceDate);
  for (let i = 0; i < p.budgets.length; i++)
    for (let j = i + 1; j < p.budgets.length; j++) {
      const a = p.budgets[i],
        b = p.budgets[j];
      if (
        normal(a.category) === normal(b.category) &&
        a.start <= b.end &&
        b.start <= a.end
      )
        fail('Budgets for one category must not overlap');
    }
  return p;
}
export function readPlan(w: Workspace): Plan {
  if (w.companionPlan !== undefined && w.companionPlan !== null)
    return validatePlan(w.companionPlan);
  const p = emptyPlan();
  if (w.budgets !== undefined) {
    if (!object(w.budgets))
      fail('Invalid legacy budgets; saved data preserved');
    p.budgets = Object.entries(w.budgets).map(([category, limitPence]) => ({
      category,
      start: w.start ?? '',
      end: w.end ?? '',
      limitPence,
      essential: false,
    }));
  }
  return validatePlan(p);
}
export function writePlan(w: Workspace, p: Plan): Workspace {
  return { ...copy(w), companionPlan: validatePlan(p) };
}
function checkIndex(index: number, size: number): void {
  if (!Number.isInteger(index) || index < 0 || index >= size)
    fail('This item is no longer available');
}
export function upsertBudget(
  w: Workspace,
  index: number,
  budget: Budget,
): Workspace {
  const p = readPlan(w),
    b = validateBudget(budget);
  if (index === -1) p.budgets.push(b);
  else {
    checkIndex(index, p.budgets.length);
    p.budgets[index] = b;
  }
  return writePlan(w, p);
}
export function removeBudget(w: Workspace, index: number): Workspace {
  const p = readPlan(w);
  checkIndex(index, p.budgets.length);
  p.budgets.splice(index, 1);
  return writePlan(w, p);
}
export function updateContext(w: Workspace, context: Partial<Plan>): Workspace {
  const p = readPlan(w);
  return writePlan(w, {
    ...p,
    reportedBalancePence:
      context.reportedBalancePence === undefined
        ? p.reportedBalancePence
        : context.reportedBalancePence,
    reportedBalanceDate:
      context.reportedBalanceDate === undefined
        ? p.reportedBalanceDate
        : context.reportedBalanceDate,
    reservePence: context.reservePence ?? p.reservePence,
    commitmentsComplete: context.commitmentsComplete ?? p.commitmentsComplete,
    priorities: context.priorities ?? p.priorities,
  });
}
export function upsertCommitment(
  w: Workspace,
  index: number,
  c: Commitment,
): Workspace {
  const p = readPlan(w);
  if (index === -1) p.commitments.push(validateCommitment(c));
  else {
    checkIndex(index, p.commitments.length);
    p.commitments[index] = validateCommitment(c);
  }
  p.commitmentsComplete = false;
  return writePlan(w, p);
}
export function removeCommitment(w: Workspace, index: number): Workspace {
  const p = readPlan(w);
  checkIndex(index, p.commitments.length);
  p.commitments.splice(index, 1);
  p.commitmentsComplete = false;
  return writePlan(w, p);
}
export function uniqueTransactions(rows: Transaction[]): Transaction[] {
  if (!Array.isArray(rows) || rows.length > MAX_TRANSACTIONS)
    fail('Transaction history exceeds supported import size');
  const found = new Map<string, Transaction>();
  for (const t of rows) {
    if (
      !object(t) ||
      typeof t.id !== 'string' ||
      !t.id.trim() ||
      t.id.length > 500 ||
      typeof t.name !== 'string' ||
      typeof t.category !== 'string' ||
      typeof t.type !== 'string' ||
      !object(t.raw)
    )
      fail('Invalid source transaction; saved data preserved');
    isoDate(t.date);
    integer(t.amount);
    if (t.currency !== undefined && t.currency !== 'GBP')
      fail('Only GBP account currency is supported');
    for (const v of Object.values(t.raw))
      if (typeof v !== 'string') fail('Raw source fields must be text');
    const prior = found.get(t.id);
    if (prior && canonical(prior) !== canonical(t))
      fail('Conflicting duplicate transaction ID');
    found.set(t.id, t);
  }
  return [...found.values()];
}
export function validateWorkspace(input: unknown): Workspace {
  const w = input as Workspace;
  if (!object(w) || !Array.isArray(w.transactions))
    fail('Invalid workspace; saved data preserved');
  uniqueTransactions(w.transactions);
  readPlan(w);
  if (w.overrides !== undefined) {
    if (!object(w.overrides)) fail('Category corrections must be an object');
    for (const [id, value] of Object.entries(w.overrides)) {
      if (!id || typeof value !== 'string') fail('Invalid category correction');
      if (value.trim()) label(value);
    }
  }
  if (w.categoryEmojiOverrides !== undefined) {
    if (!object(w.categoryEmojiOverrides)) fail('Invalid emoji overrides');
    for (const emoji of Object.values(w.categoryEmojiOverrides))
      validateEmoji(emoji);
  }
  records(w);
  return copy(w);
}
export function effectiveCategory(
  t: Transaction,
  w: Pick<Workspace, 'overrides'> = {},
): string {
  const correction =
    w.overrides && Object.prototype.hasOwnProperty.call(w.overrides, t.id)
      ? w.overrides[t.id]
      : undefined;
  return correction?.trim() || t.category.trim() || 'Uncategorised';
}
export function isPotMovement(t: Transaction): boolean {
  return normal(t.type) === 'pot transfer';
}
export function movementLabel(t: Transaction): string {
  return isPotMovement(t)
    ? 'Pot movement · excluded from external spending'
    : t.amount < 0
    ? 'Money out'
    : t.amount > 0
    ? 'Money in · unclassified'
    : 'No money movement';
}
const EMOJIS: Record<string, string> = {
  general: '💷',
  uncategorised: '🏷️',
  uncategorized: '🏷️',
  bills: '🧾',
  'eating out': '🍽️',
  entertainment: '🎟️',
  expenses: '🧳',
  family: '👪',
  finances: '💰',
  gifts: '🎁',
  groceries: '🛒',
  holidays: '🏖️',
  income: '💷',
  'personal care': '🧴',
  savings: '🐖',
  shopping: '🛍️',
  subscriptions: '🔄',
  transport: '🚆',
};
export function validateEmoji(value: string): string {
  if (
    typeof value !== 'string' ||
    !value ||
    [...value].length > 24 ||
    !/[\p{Extended_Pictographic}\p{Regional_Indicator}\u20e3]/u.test(value) ||
    /[^\p{Extended_Pictographic}\p{Regional_Indicator}\p{Emoji_Modifier}\u200d\ufe0f\ufe0e\u20e3#*0-9\u{e0020}-\u{e007f}]/u.test(
      value,
    )
  )
    fail('Choose an emoji rather than a text label');
  return value;
}
export function categoryEmoji(
  category: string,
  overrides: Record<string, string> = {},
): string {
  const matches = Object.entries(overrides)
    .filter(([key]) => normal(key) === normal(category))
    .map(([, value]) => value);
  if (new Set(matches).size > 1) fail('Conflicting category emoji overrides');
  if (matches.length) return validateEmoji(matches[0]);
  if (Object.prototype.hasOwnProperty.call(EMOJIS, normal(category)))
    return EMOJIS[normal(category)];
  let hash = 0;
  for (const c of normal(category)) hash = (31 * hash + c.charCodeAt(0)) | 0;
  return ['🏷️', '🎯', '🧩', '🌱', '✨', '📌'][((hash % 6) + 6) % 6];
}
export function transactionEmoji(
  t: Transaction,
  w: Workspace = { transactions: [] },
): string {
  return (
    t.raw?.Emoji?.trim() ||
    categoryEmoji(effectiveCategory(t, w), w.categoryEmojiOverrides)
  );
}
export type Overview = {
  transactionCount: number;
  moneyOut: number;
  moneyIn: number;
  potMovementCount: number;
  potMoneyIn: number;
  potMoneyOut: number;
  categoryDebits: Record<string, number>;
  categoryCredits: Record<string, number>;
  firstDate: string | null;
  lastDate: string | null;
};
export function overview(w: Workspace): Overview {
  const rows = uniqueTransactions(w.transactions),
    out: Overview = {
      transactionCount: rows.length,
      moneyOut: 0,
      moneyIn: 0,
      potMovementCount: 0,
      potMoneyIn: 0,
      potMoneyOut: 0,
      categoryDebits: Object.create(null) as Record<string, number>,
      categoryCredits: Object.create(null) as Record<string, number>,
      firstDate: null,
      lastDate: null,
    };
  for (const t of rows) {
    if (out.firstDate === null || t.date < out.firstDate)
      out.firstDate = t.date;
    if (out.lastDate === null || t.date > out.lastDate) out.lastDate = t.date;
    if (isPotMovement(t)) {
      out.potMovementCount++;
      if (t.amount < 0) out.potMoneyOut = add(out.potMoneyOut, -t.amount);
      else out.potMoneyIn = add(out.potMoneyIn, t.amount);
      continue;
    }
    const c = effectiveCategory(t, w);
    if (t.amount < 0) {
      out.moneyOut = add(out.moneyOut, -t.amount);
      out.categoryDebits[c] = add(out.categoryDebits[c] ?? 0, -t.amount);
    } else if (t.amount > 0) {
      out.moneyIn = add(out.moneyIn, t.amount);
      out.categoryCredits[c] = add(out.categoryCredits[c] ?? 0, t.amount);
    }
  }
  return out;
}
export function searchHistory(w: Workspace, query: string): Transaction[] {
  const needle = normal(query);
  return uniqueTransactions(w.transactions)
    .filter(
      t =>
        !needle ||
        normal(
          [
            t.name,
            effectiveCategory(t, w),
            t.type,
            t.date,
            ...Object.values(t.raw),
          ].join(' '),
        ).includes(needle),
    )
    .sort((a, b) => b.date.localeCompare(a.date) || a.id.localeCompare(b.id));
}
export type BudgetStatus = Budget & {
  budgetIndex: number;
  grossDebitsPence: number;
  creditsPence: number;
  remainingPence: number;
  dailyGuidePence: number | null;
  projectedDebitPence: number | null;
  daysLeft: number;
  observedDays: number;
  debitCount: number;
  pace: string;
  recommendation: string;
};
export function budgetStatus(w: Workspace, today: string): BudgetStatus[] {
  isoDate(today);
  const rows = uniqueTransactions(w.transactions);
  return readPlan(w).budgets.map((b, budgetIndex) => {
    const filtered = rows.filter(
        t =>
          !isPotMovement(t) &&
          normal(effectiveCategory(t, w)) === normal(b.category) &&
          t.date >= b.start &&
          t.date <= b.end &&
          t.date <= today,
      ),
      debits = filtered.filter(t => t.amount < 0);
    const grossDebitsPence = add(...debits.map(t => -t.amount)),
      creditsPence = add(
        ...filtered.filter(t => t.amount > 0).map(t => t.amount),
      ),
      remainingPence = add(b.limitPence, -grossDebitsPence),
      daysLeft =
        today > b.end
          ? 0
          : daysBetween(today < b.start ? b.start : today, b.end) + 1,
      observedDays =
        today < b.start
          ? 0
          : daysBetween(b.start, today > b.end ? b.end : today) + 1;
    let projectedDebitPence: number | null = null;
    if (
      today >= b.start &&
      today < b.end &&
      observedDays >= 7 &&
      debits.length >= 3 &&
      new Set(debits.map(t => t.date)).size >= 3 &&
      debits.some(t => t.date <= plusDays(today, -6))
    ) {
      const value =
        (BigInt(grossDebitsPence) * BigInt(daysBetween(b.start, b.end) + 1) +
          BigInt(observedDays - 1)) /
        BigInt(observedDays);
      projectedDebitPence = integer(Number(value));
    }
    const pace =
      today < b.start
        ? 'not_started'
        : today > b.end
        ? 'ended'
        : remainingPence < 0
        ? 'over_budget'
        : projectedDebitPence !== null && projectedDebitPence > b.limitPence
        ? 'projected_over_budget'
        : 'within_recorded_budget';
    return {
      ...b,
      budgetIndex,
      grossDebitsPence,
      creditsPence,
      remainingPence,
      dailyGuidePence: daysLeft
        ? Math.floor(Math.max(0, remainingPence) / daysLeft)
        : null,
      projectedDebitPence,
      daysLeft,
      observedDays,
      debitCount: debits.length,
      pace,
      recommendation: b.essential
        ? 'Protect this essential allocation; review flexible choices first.'
        : 'Review optional spending against your priorities. Remaining allocation is not cash.',
    };
  });
}
export function assessTransaction(w: Workspace, id: string, today: string) {
  const t = uniqueTransactions(w.transactions).find(row => row.id === id);
  if (!t) fail('Transaction not found');
  return {
    transaction: t,
    category: effectiveCategory(t, w),
    potMovement: isPotMovement(t),
    creditUnclassified: t.amount > 0 && !isPotMovement(t),
    budgetImpacts:
      isPotMovement(t) || t.date > today
        ? []
        : budgetStatus(w, today)
            .filter(
              b =>
                normal(b.category) === normal(effectiveCategory(t, w)) &&
                t.date >= b.start &&
                t.date <= b.end,
            )
            .map(b => ({
              budgetIndex: b.budgetIndex,
              category: b.category,
              essential: b.essential,
              beforeRemainingPence: add(
                b.remainingPence,
                t.amount < 0 ? -t.amount : 0,
              ),
              afterRemainingPence: b.remainingPence,
              countedDebitPence: t.amount < 0 ? -t.amount : 0,
              creditsPence: Math.max(0, t.amount),
            })),
  };
}
export function reviewFlags(
  w: Workspace,
  today: string,
): {
  kind: string;
  explanation: string;
  evidenceTransactionIds: string[];
  confirmedFraud: false;
}[] {
  isoDate(today);
  const prior = new Map<string, Transaction[]>(),
    duplicates = new Map<string, Transaction[]>(),
    flags: {
      kind: string;
      explanation: string;
      evidenceTransactionIds: string[];
      confirmedFraud: false;
    }[] = [];
  for (const t of uniqueTransactions(w.transactions).sort(
    (a, b) => a.date.localeCompare(b.date) || a.id.localeCompare(b.id),
  )) {
    if (t.amount >= 0 || isPotMovement(t) || !normal(t.name) || t.date > today)
      continue;
    const merchant = normal(t.name),
      history = prior.get(merchant) ?? [];
    if (t.date >= plusDays(today, -29)) {
      const key = JSON.stringify([merchant, t.date, t.amount]),
        group = duplicates.get(key) ?? [];
      group.push(t);
      duplicates.set(key, group);
      const baseline = history.filter(row => row.date < t.date).slice(-20);
      if (baseline.length >= 5 && -t.amount >= 10000) {
        const values = baseline.map(row => -row.amount).sort((a, b) => a - b),
          mid = Math.floor(values.length / 2),
          median =
            values.length % 2
              ? values[mid]
              : Number((BigInt(values[mid - 1]) + BigInt(values[mid])) / 2n);
        if (median > 0 && BigInt(-t.amount) >= 3n * BigInt(median))
          flags.push({
            kind: 'unusually_large_debit',
            explanation:
              'At least three times the median of five or more earlier merchant debits. This can be legitimate; review it, not a fraud verdict.',
            evidenceTransactionIds: [
              t.id,
              ...baseline.slice(0, 5).map(row => row.id),
            ],
            confirmedFraud: false,
          });
      }
    }
    history.push(t);
    prior.set(merchant, history);
  }
  for (const group of duplicates.values())
    if (group.length > 1)
      flags.push({
        kind: 'possible_duplicate',
        explanation:
          'Distinct source IDs share merchant, date and debit amount. Repeated purchases can be legitimate; check the records.',
        evidenceTransactionIds: group.slice(0, 20).map(t => t.id),
        confirmedFraud: false,
      });
  return flags
    .sort(
      (a, b) =>
        a.kind.localeCompare(b.kind) ||
        a.evidenceTransactionIds[0].localeCompare(b.evidenceTransactionIds[0]),
    )
    .slice(0, 50);
}
export function freshness(
  retrievedAt: string | undefined,
  nowISO = new Date().toISOString(),
): 'recent' | 'stale' | 'unknown' {
  if (
    !retrievedAt ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(
      retrievedAt,
    )
  )
    return 'unknown';
  try {
    isoDate(retrievedAt.slice(0, 10));
  } catch {
    return 'unknown';
  }
  const now = Date.parse(nowISO),
    at = Date.parse(retrievedAt);
  if (!Number.isFinite(now) || !Number.isFinite(at) || at > now)
    return 'unknown';
  return now - at > 86400000 ? 'stale' : 'recent';
}
export function sourceToken(w: Workspace): string {
  return sha256(
    [w.workspaceId ?? '', w.mode ?? '', w.spreadsheetId ?? ''].join('\0'),
  );
}
export function workspaceRevision(w: Workspace): string {
  const keys = [
    'workspaceId',
    'mode',
    'spreadsheetId',
    'authorized',
    'lastSync',
    'transactions',
    'overrides',
    'companionPlan',
    'conversationBudgets',
    'budgets',
    'start',
    'end',
    'categoryEmojiOverrides',
  ];
  return sha256(keys.map(k => k + '\0' + canonical(w[k]) + '\0').join(''));
}
export function parseDraft(originalText: string, today: string): Draft {
  const original = text(originalText);
  isoDate(today);
  const issues: string[] = [];
  const amountPattern =
    /(?:£\s*|\bGBP\s+)([+-]?[0-9][0-9,]*(?:\.[0-9]+)?)|([+-]?[0-9][0-9,]*(?:\.[0-9]+)?)\s*(?:GBP\b|pounds?\b)/gi;
  const amounts: { value: string; start: number; end: number }[] = [];
  let hit: RegExpExecArray | null;
  while ((hit = amountPattern.exec(original)) !== null)
    amounts.push({
      value: hit[1] ?? hit[2],
      start: hit.index,
      end: amountPattern.lastIndex,
    });
  const foreign =
    /[$€¥₹₩₽]|\b(?:USD|EUR|JPY|AUD|CAD|CHF|INR|CNY|NZD|dollars?|euros?|yen)\b/i.test(
      original,
    );
  const approximate =
    /\b(?:about|around|approximately|roughly|between|up\s+to|at\s+least|at\s+most)\s*(?:(?:£|GBP)\s*)?(?![0-9]{4}-[0-9]{2}-[0-9]{2})[0-9]/i.test(
      original,
    );
  const negated =
    /\b(?:cancel|cancelled|canceled|cancelling|canceling|don['’]t|dont|do\s+not|doesn['’]t|not\s+want|no\s+longer|never\s+mind|forget\s+(?:it|that))\b/i.test(
      original,
    );
  const liability =
    /\b(?:owe|owed|debt|loan|repay|repayment|refund|salary|income|transfer)\b|\bpay\s+back\b|\bcredit\s+card\s+balance\b/i.test(
      original,
    );
  let amountPence: number | null = null;
  if (foreign)
    issues.push(
      'Foreign or mixed currencies require your explicit GBP choice; no conversion inferred.',
    );
  else if (amounts.length !== 1)
    issues.push(
      amounts.length
        ? 'Multiple amounts are unresolved. Choose one explicit amount.'
        : 'Enter one explicit GBP amount, such as £120.',
    );
  else {
    const item = amounts[0],
      before = original.slice(0, item.start),
      after = original.slice(item.end);
    const ambiguous =
      /^\s*[-–—/]\s*£?[0-9]/.test(after) ||
      /^\s*(?:to|or|and)\s+£?[0-9]/i.test(after) ||
      /^[a-zA-Z%]/.test(after) ||
      /^[.,][.,0-9]/.test(after) ||
      /[0-9]\s*[-–—/]\s*$/.test(before) ||
      /\b(?:minus|negative)\s*$/i.test(before) ||
      /[+\-−]\s*$/.test(before);
    if (ambiguous || approximate)
      issues.push(
        'This amount is an estimate or range; choose a precise amount.',
      );
    else if (
      !/^(?:[0-9]+(?:\.[0-9]{1,2})?|[0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]{1,2})?)$/.test(
        item.value,
      )
    )
      issues.push('The GBP amount has unsupported formatting or precision.');
    else
      try {
        amountPence = positive(parseMoney(item.value.replace(/,/g, '')));
      } catch {
        issues.push('Choose a positive GBP amount within safe precision.');
      }
  }
  if (negated) {
    amountPence = null;
    issues.push(
      'Cancellation or negation needs your explicit review. No action was inferred.',
    );
  }
  if (liability) {
    amountPence = null;
    issues.push(
      'Possible liability, credit or money movement. Review purpose and amount; no obligation was created.',
    );
  }
  const next = /\bnext\s+month\b/i.test(original),
    current = /\bthis\s+month\b/i.test(original);
  const dateMatches = [
    ...original.matchAll(/(?<![0-9])([0-9]{4}-[0-9]{2}-[0-9]{2})(?![0-9])/g),
  ];
  const dates: string[] = [];
  let invalidDate = false;
  for (const match of dateMatches)
    try {
      dates.push(isoDate(match[1]));
    } catch {
      invalidDate = true;
    }
  const dateLike = [
    ...original.matchAll(
      /(?<![0-9])(?:[0-9]{1,4}\/[0-9]{1,4}(?:\/[0-9]{1,4})?|[0-9]{1,4}-[0-9]{1,2}(?:-[0-9]{1,4})?)(?![0-9])/g,
    ),
  ];
  const unsupportedDate =
    dateLike.some(m => !/^\d{4}-\d{2}-\d{2}$/.test(m[0])) ||
    /\b(?:on|next|this|by|before|after)\s+(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\b/i.test(
      original,
    ) ||
    /\b(?:tomorrow|yesterday|next\s+week|this\s+week)\b/i.test(original);
  const alternatives =
    dateMatches.length === 2 &&
    /\b(?:or|either)\b/i.test(
      original.slice(dateMatches[0].index! + 10, dateMatches[1].index),
    );
  let from: string | null = null,
    to: string | null = null;
  if (
    unsupportedDate ||
    negated ||
    invalidDate ||
    alternatives ||
    (next && current) ||
    ((next || current) && dates.length > 0) ||
    dates.length > 2
  )
    issues.push(
      'The date scope is unsupported, invalid or ambiguous. Choose explicit first/last days.',
    );
  else if (next || current) {
    const p = calendarMonth(today, next ? 1 : 0);
    from = p.start;
    to = p.end;
  } else if (dates.length === 1) {
    from = dates[0];
    to = from;
  } else if (dates.length === 2 && dates[0] <= dates[1]) {
    [from, to] = dates;
  } else
    issues.push(
      'Choose an explicit ISO date/range, this month or next month. No period was assumed.',
    );
  return {
    originalText: original,
    amountPence,
    from,
    to,
    issues,
    tentative: true,
  };
}
export const parsePlanningMessage = parseDraft;
export function calendarMonth(
  asOf: string,
  offset = 0,
): { start: string; end: string } {
  isoDate(asOf);
  if (!Number.isInteger(offset)) fail('Invalid month offset');
  const [y, m] = asOf.split('-').map(Number),
    date = new Date(0);
  date.setUTCFullYear(y, m - 1 + offset, 1);
  date.setUTCHours(0, 0, 0, 0);
  const start = date.toISOString().slice(0, 10);
  date.setUTCMonth(date.getUTCMonth() + 1, 0);
  return {
    start: isoDate(start),
    end: isoDate(date.toISOString().slice(0, 10)),
  };
}
export function monthlyPayCycle(
  asOf: string,
  payday: number,
): { start: string; end: string } {
  isoDate(asOf);
  if (!Number.isInteger(payday) || payday < 1 || payday > 31)
    fail('Payday must be 1–31');
  const month = calendarMonth(asOf);
  const clamp = (p: { start: string; end: string }) =>
    p.start.slice(0, 8) +
    String(Math.min(payday, Number(p.end.slice(8)))).padStart(2, '0');
  const window = asOf < clamp(month) ? calendarMonth(asOf, -1) : month;
  return {
    start: clamp(window),
    end: plusDays(clamp(calendarMonth(window.start, 1)), -1),
  };
}
export function fixedPayCycle(
  anchor: string,
  cycleDays: number,
  asOf: string,
): { start: string; end: string } {
  if (!Number.isInteger(cycleDays) || cycleDays < 1 || cycleDays > 366)
    fail('Choose a cycle of 1–366 days');
  const start = plusDays(
    anchor,
    Math.floor(daysBetween(anchor, asOf) / cycleDays) * cycleDays,
  );
  return { start, end: plusDays(start, cycleDays - 1) };
}
function uuid(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const n = Math.floor(Math.random() * 16);
    return (c === 'x' ? n : (n & 3) | 8).toString(16);
  });
}
export function validateProposal(value: Proposal): Proposal {
  if (
    !object(value) ||
    value.tentative !== true ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      value.id,
    )
  )
    fail('Invalid tentative proposal review token');
  text(value.originalText);
  validateBudget({
    category: value.category,
    start: value.from,
    end: value.to,
    limitPence: value.amountPence,
    essential: false,
  });
  label(value.name);
  isoDate(value.reviewedOn);
  if (
    value.mode !== 'ADD_ALLOCATION' &&
    value.mode !== 'WITHIN_EXISTING_ALLOCATION'
  )
    fail('Choose an allocation mode');
  return {
    ...copy(value),
    id: value.id.toLowerCase(),
    name: label(value.name),
    category: label(value.category),
  };
}
export function reviewProposal(
  draft: Draft,
  fields: ProposalFields,
  today: string,
): Proposal {
  text(draft.originalText);
  return validateProposal({
    ...fields,
    id: uuid(),
    originalText: draft.originalText,
    reviewedOn: isoDate(today),
    tentative: true,
  });
}
export function proposalHash(p: Proposal): string {
  const data: number[] = [];
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
    const bytes = utf8(v),
      len = bytes.length;
    data.push(
      (len >>> 24) & 255,
      (len >>> 16) & 255,
      (len >>> 8) & 255,
      len & 255,
      ...bytes,
    );
  }
  return sha256(data);
}
export function records(w: Workspace): PlanningRecord[] {
  if (w.conversationBudgets === undefined || w.conversationBudgets === null)
    return [];
  const ledger = w.conversationBudgets;
  if (
    !object(ledger) ||
    ledger.version !== 1 ||
    !Array.isArray(ledger.records) ||
    ledger.records.length > 500
  )
    fail('Saved planning ledger is unsupported; it has not been deleted');
  const ids = new Set<string>();
  for (const r of ledger.records) {
    if (!object(r)) fail('Invalid saved planning record');
    const p = validateProposal(r.proposal);
    if (ids.has(p.id) || r.payloadSha256 !== proposalHash(p))
      fail('Saved proposal identity or replay record does not match');
    ids.add(p.id);
    if (r.status !== 'active' && r.status !== 'cancelled')
      fail('Unknown saved proposal status');
    nonnegative(r.budgetLimitBeforePence);
    nonnegative(r.budgetLimitAfterPence);
  }
  return copy(ledger.records);
}
function sameCategory(a: string, b: string): boolean {
  return normal(a) === normal(b);
}
function overlaps(
  a: { from: string; to: string },
  b: { from: string; to: string },
): boolean {
  return a.from <= b.to && b.from <= a.to;
}
function requireSameSource(w: Workspace): void {
  const token = w.conversationBudgets?.reviewSourceToken;
  if (token && token !== sourceToken(w))
    fail(
      'Saved plans belong to another export. They are preserved; reconcile them before planning against this source.',
    );
}
export type AffordabilityResult = {
  cannotConfirmAffordability: true;
  bankCoverageKnown: false;
  estimate: true;
  alreadySaved: boolean;
  overlapAmbiguous: boolean;
  negativeReportedBalance: boolean;
  decision: string;
  retrievalStatus: string;
  sourceCutoffDate: string | null;
  latestObservedDate: string | null;
  balanceStatus: string;
  proposalFrom: string;
  proposalTo: string;
  assessedOn: string;
  proposalPence: number;
  grossDebitsPence: number;
  creditsPence: number;
  existingReservedPence: number;
  allocationLimitBeforePence: number | null;
  allocationLimitAfterPence: number | null;
  allocationRemainingBeforePence: number | null;
  allocationRemainingAfterPence: number | null;
  allocationShortfallPence: number | null;
  availablePositiveReportedBalancePence: number | null;
  knownCommitmentsPence: number;
  reservePence: number;
  conditionalCashAfterPence: number | null;
  conditionalCashShortfallPence: number | null;
  conditionalHeadroomPence: number | null;
  explanations: string[];
  warnings: string[];
  canSave: boolean;
  reviewRevision: string;
  reviewedAt: string;
  proposalHash: string;
  summary: string;
};
export function assessAffordability(
  w: Workspace,
  proposal: Proposal,
  today: string,
  nowISO = new Date().toISOString(),
): AffordabilityResult {
  const p = validateProposal(proposal);
  isoDate(today);
  requireSameSource(w);
  const plan = readPlan(w),
    rows = uniqueTransactions(w.transactions),
    saved = records(w),
    retrievalStatus = freshness(w.lastSync, nowISO),
    warnings = [
      'Budget remaining is an allocation, not available cash. Bank coverage and pending payments are unknown from an export.',
    ],
    explanations = [
      'This is a tentative allocation review, not confirmation of affordability or a payment instruction.',
      `Proposal window ${p.from} through ${p.to}, inclusive; assessed on ${today}.`,
    ];
  if (!Number.isFinite(Date.parse(nowISO))) fail('Invalid review timestamp');
  warnings.push(
    retrievalStatus === 'recent'
      ? 'Recent retrieval dates the import; it does not prove current bank data.'
      : `Retrieval is ${retrievalStatus}; recorded spending may be incomplete.`,
  );
  const observed = rows
      .filter(t => t.date <= today)
      .map(t => t.date)
      .sort(),
    sourceCutoffDate = observed.at(-1) ?? null;
  if (sourceCutoffDate)
    explanations.push(
      `Latest observed transaction: ${sourceCutoffDate}; coverage remains unverified.`,
    );
  else warnings.push('No observed transaction date on or before this review.');
  let grossDebitsPence = 0,
    creditsPence = 0,
    overlapAmbiguous = false,
    existingReservedPence = 0,
    otherCashProvisions = 0,
    alreadySaved = false,
    cancelled = false;
  const paymentOverlap = (provision: Proposal) =>
    rows.some(
      t =>
        t.date <= today &&
        !isPotMovement(t) &&
        t.amount === -provision.amountPence &&
        t.date >= provision.from &&
        t.date <= provision.to &&
        (normal(t.name) === normal(provision.name) ||
          sameCategory(effectiveCategory(t, w), provision.category)),
    );
  for (const t of rows) {
    if (t.date > today || isPotMovement(t) || t.date < p.from || t.date > p.to)
      continue;
    if (sameCategory(effectiveCategory(t, w), p.category)) {
      if (t.amount < 0) grossDebitsPence = add(grossDebitsPence, -t.amount);
      if (t.amount > 0) creditsPence = add(creditsPence, t.amount);
    }
  }
  if (paymentOverlap(p)) {
    overlapAmbiguous = true;
    warnings.push(
      'A recorded debit may already represent this purchase. Reconcile before combining figures.',
    );
  }
  const horizon = p.to < today ? today : p.to;
  const commitmentOverlap = (a: Proposal) =>
    plan.commitments.some(
      c =>
        c.confirmed &&
        c.dueDate >= today &&
        c.dueDate <= horizon &&
        c.dueDate >= a.from &&
        c.dueDate <= a.to &&
        (c.amountPence === a.amountPence ||
          normal(c.label) === normal(a.name) ||
          sameCategory(c.label, a.category)),
    );
  for (const r of saved) {
    const a = r.proposal,
      same = a.id === p.id;
    if (same) {
      if (proposalHash(a) !== proposalHash(p))
        fail('Review token already used for different fields');
      alreadySaved = true;
      cancelled = r.status !== 'active';
    }
    if (r.status !== 'active') continue;
    if (sameCategory(a.category, p.category) && overlaps(a, p)) {
      if (a.from !== p.from || a.to !== p.to) {
        overlapAmbiguous = true;
        warnings.push(
          'An earlier provision overlaps a different date scope; review exact periods.',
        );
      } else existingReservedPence = add(existingReservedPence, a.amountPence);
    }
    if (paymentOverlap(a)) overlapAmbiguous = true;
    if (!same && a.to >= today && a.from <= p.to) {
      otherCashProvisions = add(otherCashProvisions, a.amountPence);
      if (
        commitmentOverlap(a) ||
        (a.amountPence === p.amountPence &&
          normal(a.name) === normal(p.name) &&
          overlaps(a, p))
      )
        overlapAmbiguous = true;
    }
  }
  let match: Budget | undefined,
    periodConflict = false;
  for (const b of plan.budgets)
    if (sameCategory(b.category, p.category)) {
      if (b.start === p.from && b.end === p.to) match = b;
      else if (b.start <= p.to && p.from <= b.end) periodConflict = true;
    }
  let allocationLimitBeforePence: number | null = null,
    allocationLimitAfterPence: number | null = null,
    allocationRemainingBeforePence: number | null = null,
    allocationRemainingAfterPence: number | null = null,
    allocationShortfallPence: number | null = null,
    decision = 'needs_review';
  if (periodConflict)
    warnings.push(
      'A category allocation overlaps different dates. Review periods explicitly.',
    );
  if (!periodConflict && (match || p.mode === 'ADD_ALLOCATION')) {
    allocationLimitBeforePence = match?.limitPence ?? 0;
    allocationLimitAfterPence = add(
      allocationLimitBeforePence,
      !alreadySaved && p.mode === 'ADD_ALLOCATION' ? p.amountPence : 0,
    );
    allocationRemainingBeforePence = add(
      allocationLimitBeforePence,
      -grossDebitsPence,
      -existingReservedPence,
    );
    allocationRemainingAfterPence = add(
      allocationLimitAfterPence,
      -grossDebitsPence,
      -existingReservedPence,
      alreadySaved ? 0 : -p.amountPence,
    );
    allocationShortfallPence = Math.max(0, -allocationRemainingAfterPence);
    decision =
      allocationRemainingAfterPence >= 0
        ? 'conditional_allocation_fit'
        : 'conditional_allocation_shortfall';
    explanations.push(
      `Allocation: ${money(allocationLimitAfterPence)} − gross debits ${money(
        grossDebitsPence,
      )} − existing provisions ${money(
        existingReservedPence,
      )} − new provision ${money(alreadySaved ? 0 : p.amountPence)} = ${money(
        allocationRemainingAfterPence,
      )}. The proposed purchase is reserved once; the increased limit already contains it.`,
    );
  } else
    warnings.push(
      'No matching exact-period allocation for this within-allocation proposal.',
    );
  explanations.push(
    `Recorded credits ${money(
      creditsPence,
    )} are kept separate; no refund or income classification was inferred.`,
  );
  if (alreadySaved)
    explanations.push(
      'Already saved: its allocation and provision are not applied again.',
    );
  const balanceStatus =
    plan.reportedBalancePence === null
      ? 'unknown'
      : plan.reportedBalanceDate === today
      ? 'current'
      : plan.reportedBalanceDate < today
      ? 'stale'
      : 'unknown';
  const availablePositiveReportedBalancePence =
      balanceStatus === 'current'
        ? Math.max(0, plan.reportedBalancePence!)
        : null,
    negativeReportedBalance =
      balanceStatus === 'current' && plan.reportedBalancePence! < 0;
  let liabilitiesUnresolved = negativeReportedBalance,
    knownCommitmentsPence = 0;
  if (negativeReportedBalance)
    warnings.push(
      'Negative reported balance: available positive cash is zero. Overdraft is not income or cash; unresolved debt withholds combined cash figures.',
    );
  if (balanceStatus !== 'current')
    warnings.push(
      'A same-day user-reported balance is missing or stale. No combined cash figure.',
    );
  if (!plan.commitmentsComplete)
    warnings.push(
      'Your commitment list is not declared complete. No combined cash figure.',
    );
  for (const c of plan.commitments) {
    if (c.dueDate > horizon) continue;
    if (!c.confirmed || c.dueDate < today) {
      liabilitiesUnresolved = true;
      warnings.push(
        c.confirmed
          ? 'An overdue commitment has no recorded paid status; review whether it remains owed.'
          : 'An unconfirmed commitment may be owed; review it before combining figures.',
      );
    } else knownCommitmentsPence = add(knownCommitmentsPence, c.amountPence);
  }
  if (commitmentOverlap(p)) overlapAmbiguous = true;
  const futureWindow = p.from > today,
    staleReview = p.reviewedOn !== today,
    historical = p.to < today;
  if (futureWindow)
    warnings.push(
      'Future window: income and future cash balances are unknown. Current cash is not projected into this window.',
    );
  if (historical) {
    decision = 'needs_review';
    warnings.push(
      'This proposed period has ended; it is not a current spending guide.',
    );
  }
  if (staleReview || cancelled || overlapAmbiguous) {
    decision = 'needs_review';
    allocationRemainingBeforePence = null;
    allocationRemainingAfterPence = null;
    allocationShortfallPence = null;
    for (let i = explanations.length - 1; i >= 0; i--)
      if (explanations[i].startsWith('Allocation:')) explanations.splice(i, 1);
    if (staleReview)
      warnings.push(
        'The review day changed. Review again with current figures.',
      );
    if (cancelled)
      warnings.push(
        'This saved review token was cancelled; create a new explicitly reviewed proposal.',
      );
    if (overlapAmbiguous)
      warnings.push(
        'Purchase provisions, recorded debits or commitments may overlap. Reconcile them explicitly; combined cash figures are withheld.',
      );
  }
  let conditionalCashAfterPence: number | null = null;
  if (
    availablePositiveReportedBalancePence !== null &&
    plan.commitmentsComplete &&
    !liabilitiesUnresolved &&
    !overlapAmbiguous &&
    !cancelled &&
    !staleReview &&
    !futureWindow &&
    !historical
  ) {
    conditionalCashAfterPence = add(
      availablePositiveReportedBalancePence,
      -knownCommitmentsPence,
      -plan.reservePence,
      -otherCashProvisions,
      -p.amountPence,
    );
    explanations.push(
      `Conditional current user inputs: positive reported cash ${money(
        availablePositiveReportedBalancePence,
      )} − commitments ${money(knownCommitmentsPence)} − reserve ${money(
        plan.reservePence,
      )} − purchase provisions ${money(
        add(otherCashProvisions, p.amountPence),
      )} = ${money(
        conditionalCashAfterPence,
      )}. Other spending is excluded; this is not verified bank cash.`,
    );
  }
  const canSave =
    !alreadySaved &&
    !cancelled &&
    !staleReview &&
    !overlapAmbiguous &&
    !periodConflict &&
    allocationLimitAfterPence !== null &&
    (p.mode === 'ADD_ALLOCATION' ||
      (allocationRemainingAfterPence !== null &&
        allocationRemainingAfterPence >= 0));
  return {
    cannotConfirmAffordability: true,
    bankCoverageKnown: false,
    estimate: true,
    alreadySaved,
    overlapAmbiguous,
    negativeReportedBalance,
    decision,
    retrievalStatus,
    sourceCutoffDate,
    latestObservedDate: sourceCutoffDate,
    balanceStatus,
    proposalFrom: p.from,
    proposalTo: p.to,
    assessedOn: today,
    proposalPence: p.amountPence,
    grossDebitsPence,
    creditsPence,
    existingReservedPence,
    allocationLimitBeforePence,
    allocationLimitAfterPence,
    allocationRemainingBeforePence,
    allocationRemainingAfterPence,
    allocationShortfallPence,
    availablePositiveReportedBalancePence,
    knownCommitmentsPence,
    reservePence: plan.reservePence,
    conditionalCashAfterPence,
    conditionalHeadroomPence: conditionalCashAfterPence,
    conditionalCashShortfallPence:
      conditionalCashAfterPence === null
        ? null
        : Math.max(0, -conditionalCashAfterPence),
    explanations,
    warnings,
    canSave,
    reviewRevision: workspaceRevision(w),
    reviewedAt: nowISO,
    proposalHash: proposalHash(p),
    summary:
      'Cannot confirm affordability. Review conditional allocation figures and incomplete source coverage.',
  };
}
export function applyProposal(
  w: Workspace,
  proposal: Proposal,
  review: AffordabilityResult,
  confirmed: boolean,
  today: string,
  nowISO = new Date().toISOString(),
): Workspace {
  if (confirmed !== true) fail('Explicit confirmation is required');
  const p = validateProposal(proposal),
    existing = records(w),
    hash = proposalHash(p),
    same = existing.find(r => r.proposal.id === p.id);
  if (same) {
    if (same.payloadSha256 !== hash)
      fail('Review token was already used for different fields');
    return copy(w);
  }
  requireSameSource(w);
  isoDate(today);
  if (existing.length >= 500)
    fail('Planning ledger is full; durable replay records have been preserved');
  if (
    p.reviewedOn !== today ||
    review.assessedOn !== today ||
    review.proposalHash !== hash ||
    review.reviewRevision !== workspaceRevision(w)
  )
    fail('The proposal, source or plan changed. Review the exact fields again');
  const elapsed = Date.parse(nowISO) - Date.parse(review.reviewedAt);
  if (!Number.isFinite(elapsed) || elapsed < 0 || elapsed > 300000)
    fail('This review expired. Review current figures again');
  const fresh = assessAffordability(w, p, today, nowISO);
  if (
    fresh.retrievalStatus !== review.retrievalStatus ||
    !fresh.canSave ||
    !review.canSave
  )
    fail('Current context or source freshness requires another review');
  const plan = readPlan(w),
    index = plan.budgets.findIndex(
      b =>
        sameCategory(b.category, p.category) &&
        b.start === p.from &&
        b.end === p.to,
    ),
    before = index < 0 ? 0 : plan.budgets[index].limitPence;
  let next = copy(w),
    after = before;
  if (p.mode === 'ADD_ALLOCATION') {
    after = add(before, p.amountPence);
    next = upsertBudget(next, index, {
      category: p.category,
      start: p.from,
      end: p.to,
      limitPence: after,
      essential: index < 0 ? false : plan.budgets[index].essential,
    });
  } else if (index < 0) fail('An exact existing allocation is required');
  existing.push({
    proposal: p,
    payloadSha256: hash,
    status: 'active',
    budgetLimitBeforePence: before,
    budgetLimitAfterPence: after,
  });
  next.conversationBudgets = {
    version: 1,
    records: existing,
    reviewSourceToken: sourceToken(w),
  };
  return next;
}

/** New private workspace; no invented bank balance, commitments or live account connection. */
export function emptyWorkspace(): Workspace {
  return {
    workspaceId: uuid(),
    mode: 'private',
    transactions: [],
    overrides: {},
    categoryEmojiOverrides: {},
    companionPlan: emptyPlan(),
    companionNotifications: {
      enabled: false,
      persistent: false,
      quietStart: 21,
      quietEnd: 8,
      thresholdPercent: 80,
    },
  };
}
export const createWorkspace = emptyWorkspace;
/** Explicitly synthetic fixture, generated locally. No real account/merchant identifiers. */
export function demoWorkspace(today = todayISO()): Workspace {
  isoDate(today);
  const w = emptyWorkspace();
  w.mode = 'demo';
  const entries: [string, string, string, number, string][] = [
    ['Example market', 'Groceries', 'Card payment', -3245, '🛒'],
    ['Example cafe', 'Eating out', 'Card payment', -780, '☕'],
    ['Example rail', 'Transport', 'Card payment', -1240, '🚆'],
    ['Example cinema', 'Entertainment', 'Card payment', -1600, '🎬'],
    ['Example credit', 'General', 'Bank transfer', 150000, '💷'],
    ['Example savings pot', 'Savings', 'Pot transfer', -25000, '🐖'],
    ['Example bookshop', 'Shopping', 'Card payment', -1499, '📚'],
    ['Example credit', 'Groceries', 'Bank transfer', 500, '↙️'],
  ];
  w.transactions = entries.map(([name, category, type, amount, emoji], i) => ({
    id: `synthetic-demo-${i}`,
    date: plusDays(today, -(entries.length - i)),
    name,
    category,
    type,
    amount,
    raw: { Emoji: emoji, 'Notes and #tags': 'Synthetic demonstration only' },
  }));
  const period = calendarMonth(today);
  w.companionPlan = {
    ...emptyPlan(),
    budgets: [
      {
        category: 'Groceries',
        start: period.start,
        end: period.end,
        limitPence: 20000,
        essential: true,
      },
      {
        category: 'Transport',
        start: period.start,
        end: period.end,
        limitPence: 10000,
        essential: true,
      },
      {
        category: 'Eating out',
        start: period.start,
        end: period.end,
        limitPence: 8000,
        essential: false,
      },
    ],
  };
  w.lastSync = new Date().toISOString();
  return w;
}
