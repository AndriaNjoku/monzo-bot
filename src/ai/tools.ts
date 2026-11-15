import { Json, Obj, Tool, object, only, text } from './providers';
import {
  readPlan,
  Workspace,
  Transaction,
  assessTransaction,
  reviewFlags,
  budgetStatus,
  uniqueTransactions,
  effectiveCategory,
  isPotMovement,
  normal,
  add as sumMoney,
} from '../domain';
export type AiTransaction = {
  id: string;
  date: string;
  name: string;
  category: string;
  type: string;
  amount: number;
  raw?: Record<string, unknown>;
};
export type AiWorkspace = {
  transactions: readonly AiTransaction[];
  overrides?: Readonly<Record<string, string>>;
  lastSync?: string;
  companionPlan?: unknown;
};
const rangeSchema: Obj = {
  type: 'object',
  properties: {
    from: {
      type: ['string', 'null'],
      description: 'Inclusive ISO day or null for full imported history',
    },
    to: { type: ['string', 'null'], description: 'Inclusive ISO day or null' },
  },
  required: ['from', 'to'],
  additionalProperties: false,
};
const emptySchema: Obj = {
  type: 'object',
  properties: {},
  required: [],
  additionalProperties: false,
};
export const TOOLS: Tool[] = [
  {
    name: 'summarize_transactions',
    description:
      'Exact integer-pence totals of imported external debits and credits, with Pot/internal movements separate. Not a bank balance or income classification.',
    parameters: rangeSchema,
  },
  {
    name: 'browse_transactions',
    description:
      'Page all imported rows, including explicitly flagged future-dated records, with opaque references, date and integer GBP pence. Private labels require consent; raw notes, IDs and receipts never returned.',
    parameters: {
      ...rangeSchema,
      properties: {
        ...(rangeSchema.properties as Obj),
        offset: { type: 'integer', minimum: 0, maximum: 50000 },
        limit: { type: 'integer', minimum: 1, maximum: 20 },
      },
      required: ['from', 'to', 'offset', 'limit'],
    },
  },
  {
    name: 'assess_transaction',
    description:
      'How a previously returned opaque transaction reference fits exact user budgets. No raw source ID or automatic action.',
    parameters: {
      type: 'object',
      properties: { ref: { type: 'string', minLength: 8, maxLength: 100 } },
      required: ['ref'],
      additionalProperties: false,
    },
  },
  {
    name: 'category_totals',
    description:
      'Gross recorded external debits and separate credits per category. Categories redacted unless permitted.',
    parameters: rangeSchema,
  },
  {
    name: 'budget_status',
    description:
      'Exact user-defined period/category allocations and recorded spending; remaining allocation is not cash or affordability.',
    parameters: emptySchema,
  },
  {
    name: 'planning_context',
    description:
      'User-provided essential priorities, dated reported cash and confirmed commitment metadata. Unverified by bank; no forecast income.',
    parameters: emptySchema,
  },
  {
    name: 'review_payments',
    description:
      'Bounded duplicate-like or recurring-looking candidates requiring user review. Never proof of fraud or a future payment.',
    parameters: emptySchema,
  },
];
function integer(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) {
    throw new Error('Unsafe monetary value.');
  }
  return value;
}
function add(a: number, b: number): number {
  return sumMoney(a, b);
}
function iso(day: unknown): string {
  const s = text(day, 10);
  if (
    !/^\d{4}-\d{2}-\d{2}$/.test(s) ||
    !Number.isFinite(Date.parse(`${s}T00:00:00Z`)) ||
    new Date(`${s}T00:00:00Z`).toISOString().slice(0, 10) !== s
  ) {
    throw new Error('Invalid date scope.');
  }
  return s;
}
function category(row: AiTransaction, workspace: AiWorkspace) {
  return effectiveCategory(row as Transaction, workspace);
}
function pot(row: AiTransaction): boolean {
  return isPotMovement(row as Transaction);
}
function limits(args: Obj): { from: string | null; to: string | null } {
  const from = args.from === null ? null : iso(args.from),
    to = args.to === null ? null : iso(args.to);
  if (from && to && from > to) {
    throw new Error('Invalid date order.');
  }
  return { from, to };
}
function safeObject(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}
export function toolSession(
  workspace: AiWorkspace,
  today: string,
  labels: boolean,
) {
  iso(today);
  const nonce = Math.random().toString(36).slice(2, 12).padEnd(10, 'x');
  const indexed = uniqueTransactions(
    workspace.transactions as Transaction[],
  ).map((row, index) => {
    iso(row.date);
    integer(row.amount);
    return { row: { ...row }, ref: `ev_${nonce}_${index.toString(36)}` };
  });
  const rows = indexed.filter(item => item.row.date <= today);
  const inRange = (args: Obj) => {
    const { from, to } = limits(args);
    return rows.filter(
      ({ row }) => (!from || row.date >= from) && (!to || row.date <= to),
    );
  };
  const base = (name: string): Obj => ({
    tool: name,
    sourceRetrievedAt: workspace.lastSync || 'unknown',
    sourceCoverage: 'unknown',
    bankDataVerified: false,
    scope: { observedThrough: today, fullImportedHistoryAvailable: true },
  });
  const issued = new Set<string>();
  const execute = (name: string, args: Obj): Obj => {
    if (!TOOLS.some(tool => tool.name === name)) {
      throw new Error('Only declared read-only tools are available.');
    }
    if (
      name === 'summarize_transactions' ||
      name === 'category_totals' ||
      name === 'browse_transactions'
    ) {
      only(
        args,
        name === 'browse_transactions'
          ? ['from', 'to', 'offset', 'limit']
          : ['from', 'to'],
      );
    } else {
      only(args, name === 'assess_transaction' ? ['ref'] : []);
    }
    const result = base(name);
    if (name === 'summarize_transactions') {
      const selected = inRange(args);
      let debits = 0,
        credits = 0,
        pots = 0;
      for (const { row } of selected) {
        if (pot(row)) {
          pots++;
        } else if (row.amount < 0) {
          debits = add(debits, -row.amount);
        } else {
          credits = add(credits, row.amount);
        }
      }
      return {
        ...result,
        scope: { ...(result.scope as Obj), ...limits(args) },
        transactionCount: selected.length,
        moneyOutPence: debits,
        moneyInPence: credits,
        potMovementCount: pots,
        futureRowsExcluded: indexed.length - rows.length,
        evidenceRefs: selected.slice(0, 80).map(item => item.ref),
        latestObservedDate:
          selected
            .map(item => item.row.date)
            .sort()
            .pop() ?? 'unknown',
      };
    }
    if (name === 'browse_transactions') {
      const offset = integer(args.offset),
        limit = integer(args.limit);
      if (offset < 0 || offset > 50000 || limit < 1 || limit > 20) {
        throw new Error('Invalid page bound.');
      }
      const scope = limits(args);
      const selected = indexed
        .filter(
          ({ row }) =>
            (!scope.from || row.date >= scope.from) &&
            (!scope.to || row.date <= scope.to),
        )
        .sort((a, b) => b.row.date.localeCompare(a.row.date));
      return {
        ...result,
        total: selected.length,
        offset,
        items: selected
          .slice(offset, offset + limit)
          .map(({ row, ref }) => ({
            ref,
            date: row.date,
            amountPence: row.amount,
            internalMovement: pot(row),
            recordedDateInFuture: row.date > today,
            countsAsObservedThroughToday: row.date <= today,
            ...(labels
              ? {
                  name: pot(row) ? 'Internal movement' : row.name.slice(0, 120),
                  category: pot(row)
                    ? 'Separate internal movement'
                    : category(row, workspace).slice(0, 120),
                  type: pot(row) ? 'Pot transfer' : row.type.slice(0, 80),
                }
              : {}),
          })),
      };
    }
    if (name === 'category_totals') {
      const map = new Map<
        string,
        { debitsPence: number; creditsPence: number; evidenceRefs: string[] }
      >();
      for (const { row, ref } of inRange(args)) {
        if (pot(row)) {
          continue;
        }
        const key = normal(category(row, workspace));
        const entry = map.get(key) ?? {
          debitsPence: 0,
          creditsPence: 0,
          evidenceRefs: [],
        };
        if (row.amount < 0) {
          entry.debitsPence = add(entry.debitsPence, -row.amount);
        } else {
          entry.creditsPence = add(entry.creditsPence, row.amount);
        }
        if (entry.evidenceRefs.length < 20) {
          entry.evidenceRefs.push(ref);
        }
        map.set(key, entry);
      }
      return {
        ...result,
        scope: { ...(result.scope as Obj), ...limits(args) },
        totalCategories: map.size,
        categories: [...map]
          .sort((a, b) => b[1].debitsPence - a[1].debitsPence)
          .slice(0, 20)
          .map(([key, value], index) => ({
            category: labels ? key.slice(0, 120) : `Category ${index + 1}`,
            ...value,
          })),
      };
    }
    if (name === 'assess_transaction') {
      const ref = text(args.ref, 100),
        selected = indexed.find(item => item.ref === ref);
      if (!issued.has(ref) || !selected) {
        throw new Error('Use a reference returned by a previous local tool.');
      }
      const assessment = assessTransaction(
        workspace as Workspace,
        selected.row.id,
        today,
      );
      return {
        ...result,
        ref,
        date: selected.row.date,
        amountPence: selected.row.amount,
        internalMovement: assessment.potMovement,
        creditUnclassified: assessment.creditUnclassified,
        recordedDateInFuture: selected.row.date > today,
        countsAsObservedThroughToday: selected.row.date <= today,
        ...(labels && !assessment.potMovement
          ? {
              name: selected.row.name.slice(0, 120),
              category: assessment.category.slice(0, 120),
            }
          : {}),
        budgetImpacts: assessment.budgetImpacts.map(item => ({
          budgetIndex: item.budgetIndex,
          ...(labels ? { category: item.category.slice(0, 120) } : {}),
          essentialByUser: item.essential,
          beforeRemainingPence: item.beforeRemainingPence,
          afterRemainingPence: item.afterRemainingPence,
          countedDebitPence: item.countedDebitPence,
          creditsPence: item.creditsPence,
          remainingIsCash: false,
          evidenceRefs: [ref],
        })),
      };
    }
    const plan = safeObject(readPlan(workspace as Workspace));
    if (name === 'budget_status') {
      const statuses = budgetStatus(workspace as Workspace, today);
      return {
        ...result,
        totalBudgets: statuses.length,
        budgets: statuses.slice(0, 30).map(status => {
          const evidence = rows
            .filter(
              ({ row }) =>
                !pot(row) &&
                row.date >= status.start &&
                row.date <= status.end &&
                normal(category(row, workspace)) === normal(status.category),
            )
            .slice(0, 20)
            .map(item => item.ref);
          return {
            budgetIndex: status.budgetIndex,
            category: labels
              ? status.category.slice(0, 120)
              : `Budget ${status.budgetIndex + 1}`,
            from: status.start,
            to: status.end,
            limitPence: status.limitPence,
            spentPence: status.grossDebitsPence,
            creditsPence: status.creditsPence,
            remainingPence: status.remainingPence,
            essentialByUser: status.essential,
            remainingIsCash: false,
            evidenceRefs: evidence,
          };
        }),
      };
    }
    if (name === 'planning_context') {
      const commitments = Array.isArray(plan.commitments)
        ? plan.commitments
        : [];
      const reportedAmount = plan.reportedBalancePence;
      const date = plan.reportedBalanceDate;
      const valid =
        Number.isSafeInteger(reportedAmount) &&
        typeof date === 'string' &&
        /^\d{4}-\d{2}-\d{2}$/.test(date);
      return {
        ...result,
        reportedBalancePence: valid ? integer(reportedAmount) : null,
        reportedBalanceDate: valid ? String(date) : 'unknown',
        balanceVerifiedByBank: false,
        balanceIsCurrent: valid && date === today,
        protectedReservePence: Number.isSafeInteger(plan.reservePence)
          ? integer(plan.reservePence)
          : 0,
        commitmentsCompleteByUser: plan.commitmentsComplete === true,
        verifiedAffordability: false,
        commitments: commitments.slice(0, 30).map((raw, index) => {
          const item = safeObject(raw);
          return {
            index,
            ...(labels
              ? { name: String(item.label ?? 'Commitment').slice(0, 120) }
              : {}),
            amountPence: Number.isSafeInteger(item.amountPence)
              ? integer(item.amountPence)
              : null,
            dueDate:
              typeof item.dueDate === 'string' &&
              /^\d{4}-\d{2}-\d{2}$/.test(item.dueDate)
                ? item.dueDate
                : 'unknown',
            confirmedByUser: item.confirmed === true,
            paidStatusUnknown: true,
          };
        }),
      };
    }
    const candidates: Obj[] = [],
      grouped = new Map<string, typeof rows>();
    for (const item of rows) {
      if (pot(item.row) || item.row.amount >= 0) {
        continue;
      }
      const key = `${item.row.name.toLowerCase().trim()}\u0000${
        item.row.amount
      }`;
      const previous = grouped.get(key) ?? [];
      previous.push(item);
      grouped.set(key, previous);
    }
    for (const group of grouped.values()) {
      const sorted = [...group].sort((a, b) =>
        a.row.date.localeCompare(b.row.date),
      );
      for (
        let index = 1;
        index < sorted.length && candidates.length < 12;
        index++
      ) {
        const first = sorted[index - 1],
          second = sorted[index],
          gap =
            (Date.parse(second.row.date) - Date.parse(first.row.date)) /
            86400000;
        if (gap <= 1 || (gap >= 26 && gap <= 35) || (gap >= 6 && gap <= 8)) {
          candidates.push({
            kind: gap <= 1 ? 'duplicate_like' : 'recurring_looking',
            estimated: true,
            requiresReview: true,
            confirmedFraud: false,
            confirmedDuplicate: false,
            amountPence: -second.row.amount,
            firstDate: first.row.date,
            lastDate: second.row.date,
            ...(labels ? { name: second.row.name.slice(0, 120) } : {}),
            evidenceRefs: [first.ref, second.ref],
          });
        }
      }
    }
    for (const flag of reviewFlags(workspace as Workspace, today)
      .filter(item => item.kind === 'unusually_large_debit')
      .slice(0, Math.max(0, 12 - candidates.length))) {
      const evidenceRefs = flag.evidenceTransactionIds
        .map(id => indexed.find(item => item.row.id === id)?.ref)
        .filter((ref): ref is string => !!ref);
      candidates.push({
        kind: flag.kind,
        estimated: true,
        requiresReview: true,
        confirmedFraud: false,
        confirmedDuplicate: false,
        explanation: flag.explanation,
        evidenceRefs,
      });
    }
    return { ...result, candidates, noFuturePaymentsVerified: true };
  };
  return (name: string, args: Obj) => {
    const value = execute(name, args);
    refsIn(value).forEach(ref => issued.add(ref));
    return value;
  };
}
export function refsIn(value: Json): Set<string> {
  const refs = new Set<string>();
  const visit = (item: Json) => {
    if (Array.isArray(item)) {
      item.forEach(visit);
    } else if (item && typeof item === 'object') {
      for (const [key, entry] of Object.entries(item)) {
        if (key === 'ref' && typeof entry === 'string') {
          refs.add(entry);
        } else if (key === 'evidenceRefs' && Array.isArray(entry)) {
          entry.forEach(ref => {
            if (typeof ref === 'string') {
              refs.add(ref);
            }
          });
        } else {
          visit(entry);
        }
      }
    }
  };
  visit(value);
  return refs;
}
export function pointerWithRefs(
  source: Obj,
  path: string,
): { value: Json; refs: string[] } {
  if (!path.startsWith('/') || path.length > 512) {
    throw new Error('Invalid fact reference.');
  }
  const parts = path.slice(1).split('/');
  if (parts.length > 12) {
    throw new Error('Invalid fact reference.');
  }
  let value: Json = source;
  const ancestors: Obj[] = [source];
  for (let index = 0; index < parts.length; index++) {
    const raw = parts[index];
    if (/~(?:[^01]|$)/.test(raw)) {
      throw new Error('Invalid fact escape.');
    }
    const key = raw.replace(/~1/g, '/').replace(/~0/g, '~');
    if (Array.isArray(value)) {
      if (!/^(0|[1-9][0-9]{0,6})$/.test(key) || Number(key) >= value.length) {
        throw new Error('Missing fact.');
      }
      value = value[Number(key)];
    } else {
      const record = object(value);
      if (!Object.prototype.hasOwnProperty.call(record, key)) {
        throw new Error('Missing fact.');
      }
      value = record[key];
    }
    if (
      index < parts.length - 1 &&
      value &&
      typeof value === 'object' &&
      !Array.isArray(value)
    ) {
      ancestors.push(value);
    }
  }
  if (value === null || typeof value === 'object') {
    throw new Error('Facts must reference existing scalar data.');
  }
  let refs: string[] = [];
  for (const ancestor of [...ancestors].reverse()) {
    if (
      Array.isArray(ancestor.evidenceRefs) ||
      typeof ancestor.ref === 'string'
    ) {
      refs = [
        ...refsIn({
          evidenceRefs: ancestor.evidenceRefs ?? [],
          ref: ancestor.ref ?? null,
        }),
      ];
      break;
    }
  }
  return { value, refs };
}
