import React, { useEffect, useRef, useState } from 'react';
import { Body, Button, Note, Sheet, Toggle } from './kit';

/** Consent only: continuing never sends a request or stores a provider key. */
export function AiPrivacyConsent({
  provider,
  host,
  purpose,
  labels = false,
  contextToken,
  label,
  value,
  disabled,
  onChange,
}: {
  provider: string;
  host: string;
  purpose: 'ask' | 'plan';
  labels?: boolean;
  contextToken: string;
  label: string;
  value: boolean;
  disabled?: boolean;
  onChange: (value: boolean) => void;
}) {
  const [review, setReview] = useState<string | null>(null);
  const reviewRef = useRef<string | null>(null);
  const current = useRef(contextToken);
  current.current = contextToken;
  const active = useRef(true);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  useEffect(() => {
    reviewRef.current = null;
    setReview(null);
    onChange(false);
  }, [contextToken, onChange]);
  const close = () => {
    reviewRef.current = null;
    setReview(null);
    onChange(false);
  };
  return (
    <>
      <Toggle
        label={label}
        value={value}
        disabled={disabled}
        onChange={next => {
          onChange(false);
          reviewRef.current = next && !disabled ? contextToken : null;
          setReview(reviewRef.current);
        }}
      />
      {review === contextToken && (
        <Sheet title={`Before using ${provider}`} onClose={close}>
          <Body>
            When you send, your own API key authenticates directly with{' '}
            {provider} ({host}). API usage is charged to your provider account.
          </Body>
          <Body>
            {purpose === 'ask'
              ? 'Your question, reference date and export retrieval time are sent. Tools may send individual transaction amounts/dates, spending totals, budgets, your reported balance, reserve and commitment amounts/dates.'
              : 'Only your planning message as written, the reference date and draft instructions are sent. Imported transactions, balances and budgets are not sent.'}
          </Body>
          {purpose === 'ask' && (
            <Body>
              {labels
                ? 'Label sharing is on: merchant, category, transaction-type and plan labels may include personal names.'
                : 'Label sharing is off: merchant, category, transaction-type and plan labels stay out of tool results.'}
            </Body>
          )}
          <Note>
            Anything private you type is shared as written. Your provider's
            privacy and retention terms apply. No payments or automatic plan
            changes.
          </Note>
          <Body>
            Continue allows this request only. Nothing is sent until you press
            the separate Send or Ask button.
          </Body>
          <Button
            disabled={disabled}
            onPress={() => {
              if (
                !active.current ||
                disabled ||
                current.current !== review ||
                reviewRef.current !== review
              )
                return;
              reviewRef.current = null;
              setReview(null);
              onChange(true);
            }}
          >
            Continue with {provider}
          </Button>
          <Button secondary onPress={close}>
            Cancel AI consent
          </Button>
        </Sheet>
      )}
    </>
  );
}
