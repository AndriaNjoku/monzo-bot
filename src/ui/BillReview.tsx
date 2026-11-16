import React, { useEffect, useMemo, useRef, useState } from 'react';
import { View } from 'react-native';
import {
  money,
  parseMoney,
  todayISO,
  workspaceRevision,
  type Workspace,
} from '../domain';
import {
  billCandidates,
  confirmBill,
  readBillSettings,
  setBillEnabled,
  type BillCandidate,
} from '../domain/bills';
import { Body, Button, Card, Field, Note, Sheet, Toggle } from './kit';

type Commit = (next: Workspace, expectedRevision?: string) => Promise<void>;
type Review = { candidate: BillCandidate; revision: string };
const message = (error: unknown) =>
  error instanceof Error && error.message.length < 400
    ? error.message
    : 'Unable to save this change. Your previous data is preserved.';

/** Local evidence review only. No network, provider, notification or payment executor. */
export function BillReview({
  data,
  commit,
}: {
  data: Workspace;
  commit: Commit;
}) {
  const settings = useMemo(() => {
    try {
      return { enabled: readBillSettings(data).enabled, error: '' };
    } catch (e) {
      return { enabled: false, error: message(e) };
    }
  }, [data]);
  const enabled = settings.enabled;
  const day = todayISO();
  const scan = useMemo(() => {
    try {
      return {
        candidates: enabled ? billCandidates(data, day) : [],
        error: '',
      };
    } catch (error) {
      return { candidates: [], error: message(error) };
    }
  }, [data, day, enabled]);
  const [review, setReview] = useState<Review | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [saving, setSaving] = useState(false);
  const pending = useRef(false);
  const active = useRef(true);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  async function toggle(value: boolean) {
    if (pending.current) return;
    pending.current = true;
    setSaving(true);
    setError('');
    setNotice('');
    try {
      await commit(setBillEnabled(data, value), workspaceRevision(data));
      if (active.current) {
        setReview(null);
        setNotice(
          value
            ? 'Local bill review enabled.'
            : 'Local bill review disabled. Saved commitments are unchanged.',
        );
      }
    } catch (e) {
      if (active.current) setError(message(e));
    } finally {
      pending.current = false;
      if (active.current) setSaving(false);
    }
  }
  return (
    <Card title="Bill review">
      <Body>
        Spot possible regular bills in your saved transactions. This is local
        analysis, not a live bank connection or a complete list of bills.
      </Body>
      <Toggle
        label="Allow local bill analysis"
        value={enabled}
        disabled={saving || !!settings.error}
        onChange={value => void toggle(value)}
        hint="Off by default. Turn off any time. No AI, network requests, sharing, payments or creditor contact."
      />
      {notice ? <Note>{notice}</Note> : null}
      {error || scan.error || settings.error ? (
        <Note error>{error || scan.error || settings.error}</Note>
      ) : null}
      {enabled && (
        <>
          <Body>
            Only repeated, equal-amount weekly or monthly debits qualify.
            Variable bills and irregular payments may be missed. Estimates are
            not confirmed obligations.
          </Body>
          {!scan.error && scan.candidates.length === 0 && (
            <Body>
              No candidates found. This does not mean you have no upcoming
              bills.
            </Body>
          )}
          {scan.candidates.slice(0, 10).map(candidate => (
            <View key={candidate.id}>
              <Body muted={false}>
                {candidate.label}: {money(candidate.amountPence)} (
                {candidate.cadence})
              </Body>
              <Body>
                Estimated next date: {candidate.estimatedDueDate}. Not
                bank-confirmed.
              </Body>
              <Body>
                Source retrieval: {candidate.retrievalStatus}. Recorded evidence
                can be incomplete even after a recent refresh.
              </Body>
              <Button
                secondary
                disabled={saving}
                onPress={() => {
                  setError('');
                  setNotice('');
                  setReview({ candidate, revision: workspaceRevision(data) });
                }}
              >
                {`Review ${candidate.label}`}
              </Button>
            </View>
          ))}
          {scan.candidates.length > 10 && (
            <Body>
              Showing 10 of {scan.candidates.length} candidates. Confirming a
              candidate removes it from this inbox.
            </Body>
          )}
        </>
      )}
      {review && (
        <BillEditor
          key={review.candidate.id}
          data={data}
          commit={commit}
          review={review}
          onClose={() => setReview(null)}
          onSaved={() => {
            setReview(null);
            setNotice(
              'Commitment saved to your local plan. No payment was made.',
            );
          }}
        />
      )}
    </Card>
  );
}

function BillEditor({
  data,
  commit,
  review,
  onClose,
  onSaved,
}: {
  data: Workspace;
  commit: Commit;
  review: Review;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { candidate, revision } = review;
  const [label, setLabel] = useState(candidate.label);
  const [amount, setAmount] = useState(
    (candidate.amountPence / 100).toFixed(2),
  );
  const [dueDate, setDueDate] = useState(candidate.estimatedDueDate);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const pending = useRef(false);
  const active = useRef(true);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  const close = () => {
    if (!pending.current) onClose();
  };
  async function confirm() {
    if (pending.current) return;
    pending.current = true;
    setSaving(true);
    setError('');
    try {
      if (candidate.reviewedOn !== todayISO())
        throw new Error(
          'The review day changed. Close and reopen this candidate.',
        );
      const next = confirmBill(
        data,
        candidate.id,
        { label, dueDate, amountPence: parseMoney(amount) },
        revision,
        todayISO(),
      );
      await commit(next, revision);
      if (active.current) onSaved();
    } catch (e) {
      if (active.current) setError(message(e));
    } finally {
      pending.current = false;
      if (active.current) setSaving(false);
    }
  }
  return (
    <Sheet title="Review possible bill" onClose={close}>
      <Note>{candidate.explanation}</Note>
      <Body>Evidence in your imported history:</Body>
      {candidate.evidence.map(item => (
        <Body key={item.id}>
          {item.date}: {money(item.amountPence)}
        </Body>
      ))}
      <Body>
        Source retrieval: {candidate.retrievalStatus}. An estimate can be wrong,
        already paid or cancelled. Verify the amount and due date yourself.
      </Body>
      <Field
        label="Bill name"
        value={label}
        editable={!saving}
        onChangeText={setLabel}
        maxLength={120}
      />
      <Field
        label="Bill amount (GBP)"
        value={amount}
        editable={!saving}
        onChangeText={setAmount}
        keyboardType="decimal-pad"
      />
      <Field
        label="Bill due date (yyyy-MM-dd)"
        value={dueDate}
        editable={!saving}
        onChangeText={setDueDate}
        autoCapitalize="none"
      />
      <Note>
        Confirming records these edited details as a user-confirmed commitment
        in your local plan. It does not pay, schedule a bank payment, send a
        reminder or contact anyone. Check existing commitments to avoid counting
        the same bill twice. Your list will need a fresh completeness review.
      </Note>
      {error ? <Note error>{error}</Note> : null}
      <Button disabled={saving} onPress={() => void confirm()}>
        Confirm local commitment
      </Button>
      <Button secondary disabled={saving} onPress={close}>
        Cancel bill review
      </Button>
    </Sheet>
  );
}
