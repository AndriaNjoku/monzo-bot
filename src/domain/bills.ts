/** Local-only bill evidence. No bank, network, notification or AI execution. */
import {
  type Workspace,
  type Transaction,
  type Commitment,
  normal,
  uniqueTransactions,
  daysBetween,
  isoDate,
  plusDays,
  calendarMonth,
  freshness,
  sha256,
  workspaceRevision,
  readPlan,
  upsertCommitment,
  copy,
} from './index';

export type BillCandidate = {
  id: string;
  evidenceId: string;
  label: string;
  amountPence: number;
  cadence: 'weekly' | 'monthly';
  estimatedDueDate: string;
  evidence: { id: string; date: string; amountPence: number }[];
  retrievalStatus: 'recent' | 'stale' | 'unknown';
  reviewedOn: string;
  explanation: string;
};
type BillSettings = { enabled: boolean; confirmedCandidateIds: string[] };
export function readBillSettings(w: Workspace): BillSettings {
  const value = w.companionBills;
  if (value === undefined) return { enabled: false, confirmedCandidateIds: [] };
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid local bill settings');
  const settings = value as Partial<BillSettings>;
  if (
    typeof settings.enabled !== 'boolean' ||
    !Array.isArray(settings.confirmedCandidateIds) ||
    settings.confirmedCandidateIds.length > 500 ||
    settings.confirmedCandidateIds.some(
      id => typeof id !== 'string' || !/^[a-f0-9]{64}$/.test(id),
    )
  )
    throw new Error('Invalid local bill settings');
  return {
    enabled: settings.enabled,
    confirmedCandidateIds: [...settings.confirmedCandidateIds],
  };
}
export function setBillEnabled(w: Workspace, enabled: boolean): Workspace {
  if (typeof enabled !== 'boolean')
    throw new Error('Choose an explicit bill review permission');
  return { ...copy(w), companionBills: { ...readBillSettings(w), enabled } };
}
function externalDebit(t: Transaction, today: string): boolean {
  return (
    t.amount < 0 &&
    t.date <= today &&
    !!normal(t.name) &&
    !/pot|transfer|refund|reversal|cash withdrawal|internal/i.test(t.type) &&
    (t.currency === undefined || t.currency === 'GBP') &&
    !Object.entries(t.raw).some(
      ([key, value]) =>
        /^(?:currency|original currency|local currency)$/i.test(key.trim()) &&
        value.trim() !== '' &&
        value.trim().toUpperCase() !== 'GBP',
    )
  );
}
export function billCandidates(w: Workspace, today: string): BillCandidate[] {
  isoDate(today);
  const settings = readBillSettings(w);
  if (!settings.enabled) return [];
  // Source validation rejects conflicting IDs/foreign account currencies rather than guessing.
  const groups = new Map<string, Transaction[]>();
  for (const t of uniqueTransactions(w.transactions)) {
    if (!externalDebit(t, today)) continue;
    const key = normal(t.name);
    const rows = groups.get(key) || [];
    rows.push(t);
    groups.set(key, rows);
  }
  const candidates: BillCandidate[] = [];
  for (const [merchant, rows] of groups) {
    rows.sort(
      (a, b) => b.date.localeCompare(a.date) || a.id.localeCompare(b.id),
    );
    const evidence = rows.slice(0, 3).reverse();
    if (
      evidence.length !== 3 ||
      evidence.some(t => t.amount !== evidence[0].amount)
    )
      continue;
    // Same-day duplicate debits are ambiguous, including a fourth row on the evidence boundary.
    const dateCounts = new Map<string, number>();
    for (const row of rows)
      dateCounts.set(row.date, (dateCounts.get(row.date) || 0) + 1);
    if (evidence.some(t => dateCounts.get(t.date) !== 1)) continue;
    const gaps = evidence
      .slice(1)
      .map((t, i) => daysBetween(evidence[i].date, t.date));
    const monthly =
      gaps.every(gap => gap >= 26 && gap <= 35) &&
      evidence.every(
        t =>
          Math.abs(
            Number(t.date.slice(8)) - Number(evidence[2].date.slice(8)),
          ) <= 3,
      );
    const weekly = gaps.every(gap => gap >= 6 && gap <= 8);
    if (!monthly && !weekly) continue;
    const last = evidence[2];
    const cadence = monthly ? 'monthly' : 'weekly';
    const nextMonth = calendarMonth(last.date, 1);
    const estimatedDueDate = monthly
      ? nextMonth.start.slice(0, 8) +
        String(
          Math.min(Number(last.date.slice(8)), Number(nextMonth.end.slice(8))),
        ).padStart(2, '0')
      : plusDays(last.date, 7);
    // Do not roll old evidence forward into fabricated upcoming bills.
    if (daysBetween(estimatedDueDate, today) > 7) continue;
    const evidenceId = sha256(
      JSON.stringify([merchant, evidence.map(t => [t.id, t.date, t.amount])]),
    );
    const id = sha256(JSON.stringify([evidenceId, today]));
    if (settings.confirmedCandidateIds.includes(evidenceId)) continue;
    candidates.push({
      id,
      evidenceId,
      label: last.name.trim(),
      amountPence: -last.amount,
      cadence,
      estimatedDueDate,
      evidence: evidence.map(t => ({
        id: t.id,
        date: t.date,
        amountPence: -t.amount,
      })),
      retrievalStatus: freshness(w.lastSync),
      reviewedOn: today,
      explanation:
        'Three matching recorded debits suggest a pattern, not a verified bill. The next date is an estimate. Check the merchant, amount and due date yourself.',
    });
  }
  return candidates
    .sort(
      (a, b) =>
        a.estimatedDueDate.localeCompare(b.estimatedDueDate) ||
        a.id.localeCompare(b.id),
    )
    .slice(0, 50);
}
export function confirmBill(
  w: Workspace,
  candidateId: string,
  edited: Pick<Commitment, 'label' | 'dueDate' | 'amountPence'>,
  expectedRevision: string,
  today: string,
): Workspace {
  isoDate(today);
  const settings = readBillSettings(w);
  if (!settings.enabled) throw new Error('Local bill review is disabled');
  if (workspaceRevision(w) !== expectedRevision)
    throw new Error('Your source or plan changed. Review this bill again.');
  const candidate = billCandidates(w, today).find(c => c.id === candidateId);
  if (!candidate)
    throw new Error(
      'This bill review expired or was already confirmed. Review again.',
    );
  if (candidate.retrievalStatus !== 'recent')
    throw new Error(
      'Refresh the source before confirming this inferred bill. You may enter a known commitment manually.',
    );
  isoDate(edited.dueDate);
  if (edited.dueDate < today || daysBetween(today, edited.dueDate) > 366)
    throw new Error('Choose a due date within the next year');
  const plan = readPlan(w);
  if (
    plan.commitments.some(
      c =>
        normal(c.label) === normal(edited.label) &&
        c.dueDate === edited.dueDate,
    )
  )
    throw new Error(
      'A commitment for this merchant and date already exists. Review it in Plan.',
    );
  if (settings.confirmedCandidateIds.length >= 500)
    throw new Error(
      'Bill review history is full. Enter the commitment manually.',
    );
  const next = upsertCommitment(w, -1, { ...edited, confirmed: true });
  return {
    ...next,
    companionBills: {
      ...settings,
      confirmedCandidateIds: [
        ...settings.confirmedCandidateIds,
        candidate.evidenceId,
      ],
    },
  };
}
