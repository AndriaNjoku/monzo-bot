import React from 'react';
import renderer, { act, ReactTestRenderer } from 'react-test-renderer';
import { Text } from 'react-native';
import { BillReview } from '../src/ui/BillReview';
import { Button, Field, Sheet, Toggle } from '../src/ui/kit';
import { createWorkspace, readPlan, type Workspace } from '../src/domain';
import { readBillSettings, setBillEnabled } from '../src/domain/bills';
let tree: ReactTestRenderer;
let data: Workspace;
let commit: jest.Mock;
const flat = (value: any): string =>
  Array.isArray(value)
    ? value.map(flat).join('')
    : typeof value === 'string' || typeof value === 'number'
    ? String(value)
    : '';
const text = () =>
  tree.root
    .findAllByType(Text)
    .map(n => flat(n.props.children))
    .join(' ');
const button = (name: string) =>
  tree.root.findAllByType(Button).find(n => flat(n.props.children) === name)!;
const field = (name: string) =>
  tree.root.findAllByType(Field).find(n => n.props.label === name)!;
async function mount(enabled = false) {
  if (enabled) data = setBillEnabled(data, true);
  await act(async () => {
    tree = renderer.create(<BillReview data={data} commit={commit} />);
  });
}
async function press(name: string) {
  await act(async () => {
    button(name).props.onPress();
  });
}
async function refresh() {
  await act(async () => {
    tree.update(<BillReview data={data} commit={commit} />);
  });
}
beforeEach(() => {
  jest.useFakeTimers().setSystemTime(new Date('2026-11-16T12:00:00Z'));
  data = {
    ...createWorkspace(),
    lastSync: '2026-11-16T11:00:00Z',
    transactions: ['2026-09-20', '2026-10-20', '2026-11-15'].map((date, i) => ({
      id: 'bill-' + i,
      date,
      name: 'Example Internet',
      category: 'Bills',
      type: 'Direct Debit',
      amount: -3000,
      raw: {},
    })),
  };
  // Strict same-day monthly evidence; next estimated date is after today.
  data.transactions[0].date = '2026-09-15';
  data.transactions[1].date = '2026-10-15';
  commit = jest.fn(async (next: Workspace) => {
    data = next;
  });
});
afterEach(async () => {
  if (tree) await act(async () => tree.unmount());
  jest.useRealTimers();
});
test('off by default, explicit opt-in and revocation preserve data', async () => {
  await mount();
  expect(tree.root.findByType(Toggle).props.value).toBe(false);
  expect(text()).not.toContain('Review Example Internet');
  await act(async () => tree.root.findByType(Toggle).props.onChange(true));
  await refresh();
  expect(readBillSettings(data).enabled).toBe(true);
  expect(text()).toContain('Review Example Internet');
  await act(async () => tree.root.findByType(Toggle).props.onChange(false));
  await refresh();
  expect(readBillSettings(data).enabled).toBe(false);
  expect(data.transactions).toHaveLength(3);
  expect(text()).not.toContain('Review Example Internet');
});
test('shows evidence, allows edits and records only explicit local confirmation', async () => {
  await mount(true);
  await press('Review Example Internet');
  expect(text()).toContain('2026-09-15');
  expect(text()).toContain('not a verified bill');
  await act(async () => {
    field('Bill name').props.onChangeText('Home internet');
    field('Bill amount (GBP)').props.onChangeText('35.50');
    field('Bill due date (yyyy-MM-dd)').props.onChangeText('2026-12-16');
  });
  expect(commit).not.toHaveBeenCalled();
  await press('Confirm local commitment');
  expect(readPlan(data).commitments).toEqual([
    {
      label: 'Home internet',
      dueDate: '2026-12-16',
      amountPence: 3550,
      confirmed: true,
    },
  ]);
  expect(text()).toContain('No payment was made');
  expect(tree.root.findAllByType(Sheet)).toHaveLength(0);
});
test('cancel and Android close do not save and reopening discards edits', async () => {
  await mount(true);
  await press('Review Example Internet');
  await act(async () => field('Bill name').props.onChangeText('Discard this'));
  await press('Cancel bill review');
  expect(commit).not.toHaveBeenCalled();
  await press('Review Example Internet');
  expect(field('Bill name').props.value).toBe('Example Internet');
  await act(async () => tree.root.findByType(Sheet).props.onClose());
  expect(commit).not.toHaveBeenCalled();
});
test('rejects a changed workspace after opening review', async () => {
  await mount(true);
  await press('Review Example Internet');
  data = { ...data, lastSync: '2026-11-16T11:30:00Z' };
  await refresh();
  await press('Confirm local commitment');
  expect(commit).not.toHaveBeenCalled();
  expect(text()).toContain('source or plan changed');
});
test('rejects stale source and day rollover, never saving either', async () => {
  data.lastSync = '2026-10-01T11:00:00Z';
  await mount(true);
  await press('Review Example Internet');
  await press('Confirm local commitment');
  expect(text()).toContain('Refresh the source');
  jest.setSystemTime(new Date('2026-11-17T12:00:00Z'));
  await press('Confirm local commitment');
  expect(text()).toContain('review day changed');
  expect(commit).not.toHaveBeenCalled();
});
test('rapid double confirm invokes commit once and prevents close during save', async () => {
  let resolve!: () => void;
  commit.mockImplementation(
    () =>
      new Promise<void>(done => {
        resolve = done;
      }),
  );
  await mount(true);
  await press('Review Example Internet');
  const action = button('Confirm local commitment').props.onPress;
  await act(async () => {
    action();
    action();
  });
  expect(commit).toHaveBeenCalledTimes(1);
  await act(async () => tree.root.findByType(Sheet).props.onClose());
  expect(tree.root.findAllByType(Sheet)).toHaveLength(1);
  await act(async () => resolve());
  expect(tree.root.findAllByType(Sheet)).toHaveLength(0);
});
test('invalid edit leaves review open and saved plan untouched', async () => {
  await mount(true);
  await press('Review Example Internet');
  await act(async () => field('Bill amount (GBP)').props.onChangeText('-3'));
  await press('Confirm local commitment');
  expect(commit).not.toHaveBeenCalled();
  expect(readPlan(data).commitments).toHaveLength(0);
  expect(tree.root.findAllByType(Sheet)).toHaveLength(1);
});

test('malformed settings fail closed with an error instead of crashing', async () => {
  data.companionBills = { enabled: 'yes' };
  await mount();
  expect(text()).toContain('Invalid local bill settings');
  expect(tree.root.findByType(Toggle).props.value).toBe(false);
  expect(commit).not.toHaveBeenCalled();
});
