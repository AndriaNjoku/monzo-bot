import React from 'react';
import renderer, { act, ReactTestRenderer } from 'react-test-renderer';
import { Text, TextInput } from 'react-native';
import { Button } from '../src/ui/kit';
import { Companion } from '../App';
import * as finance from '../src/domain';
import { runtime } from '../src/services/runtime';
import { ask, preparePlan } from '../src/ai';

jest.mock('react-native-safe-area-context', () => {
  const { View } = require('react-native');
  return { SafeAreaProvider: View, SafeAreaView: View };
});
jest.mock('../src/services/runtime', () => ({
  runtime: {
    load: jest.fn(),
    save: jest.fn(),
    connect: jest.fn(),
    discover: jest.fn(),
    importSheet: jest.fn(),
    refresh: jest.fn(),
    disconnect: jest.fn(),
    demo: jest.fn(),
    readKey: jest.fn(),
    saveKey: jest.fn(),
    forgetKey: jest.fn(),
    voice: jest.fn(),
    cancelVoice: jest.fn(),
    notificationStatus: jest.fn(),
    configureNotifications: jest.fn(),
    deleteAll: jest.fn(),
    exportData: jest.fn(),
    importData: jest.fn(),
    calendar: jest.fn(),
  },
}));
jest.mock('../src/ai', () => ({
  ...jest.requireActual('../src/ai'),
  ask: jest.fn(),
  preparePlan: jest.fn(),
}));
let saved: finance.Workspace;
let tree: ReactTestRenderer;
const call = (method: unknown) => method as jest.Mock;
const flat = (children: unknown): string =>
  Array.isArray(children)
    ? children.map(flat).join('')
    : children === null ||
      children === undefined ||
      typeof children === 'boolean'
    ? ''
    : typeof children === 'string' || typeof children === 'number'
    ? String(children)
    : '';
const button = (label: string) =>
  tree.root.findAll(
    node => node.type === Button && flat(node.props.children) === label,
  )[0];
const field = (label: string) =>
  tree.root
    .findAllByType(TextInput)
    .find(node => node.props.accessibilityLabel === label)!;
const text = () =>
  tree.root
    .findAllByType(Text)
    .map(node => flat(node.props.children))
    .join(' ');
async function press(label: string) {
  const target = button(label);
  expect(target).toBeDefined();
  await act(async () => {
    target.props.onPress();
  });
}
async function tab(label: string) {
  const target = tree.root.findAll(
    node =>
      node.props.accessibilityRole === 'tab' &&
      node.props.accessibilityLabel === label &&
      typeof node.props.onPress === 'function',
  )[0];
  expect(target).toBeDefined();
  await act(async () => target.props.onPress());
}
async function edit(label: string, value: string) {
  expect(field(label)).toBeDefined();
  await act(async () => field(label).props.onChangeText(value));
}
async function toggle(label: string, value: boolean) {
  const target = tree.root.findAll(
    node =>
      node.props.accessibilityLabel === label &&
      typeof node.props.onValueChange === 'function',
  )[0];
  expect(target).toBeDefined();
  await act(async () => target.props.onValueChange(value));
}
async function mount() {
  await act(async () => {
    tree = renderer.create(<Companion />);
  });
}
beforeEach(() => {
  jest.clearAllMocks();
  saved = finance.emptyWorkspace();
  saved.lastSync = new Date().toISOString();
  saved.transactions = Array.from({ length: 1007 }, (_, i) => ({
    id: `synthetic-ui-${i}`,
    date: finance.plusDays('2020-01-01', i),
    name: i === 0 ? 'Ancient bicycle' : `Example shop ${i}`,
    category: 'Groceries',
    type: 'Card payment',
    amount: -100,
    raw: {
      Emoji: i === 0 ? '🚲' : '🛒',
      'Notes and #tags': i === 0 ? 'Synthetic old note' : '',
    },
  }));
  call(runtime.load).mockImplementation(async () => finance.copy(saved));
  call(runtime.save).mockImplementation(async value => {
    saved = finance.copy(value);
  });
  call(runtime.readKey).mockResolvedValue('');
  call(runtime.saveKey).mockResolvedValue(undefined);
  call(runtime.forgetKey).mockResolvedValue(undefined);
  call(runtime.notificationStatus).mockResolvedValue({
    enabled: false,
    permission: false,
    persistent: false,
  });
  call(runtime.configureNotifications).mockResolvedValue(undefined);
  call(runtime.calendar).mockResolvedValue(undefined);
  call(runtime.connect).mockResolvedValue([]);
});
afterEach(async () => {
  if (tree) {
    await act(async () => tree.unmount());
  }
});

test('full history remains searchable beyond a page and source emoji is displayed', async () => {
  await mount();
  await tab('Activity');
  await edit('Search names, categories, notes or pots', 'Ancient bicycle');
  expect(text()).toContain('Ancient bicycle');
  expect(text()).toContain('🚲');
  expect(text()).toContain('1');
  expect(saved.transactions).toHaveLength(1007);
}, 20000);
test('a budget is saved as a dated local allocation without filtering source history', async () => {
  await mount();
  await tab('Plan');
  await press('Create your first budget');
  await edit('Category', 'Transport');
  await edit('Budget amount (£)', '125.50');
  await press('Save allocation');
  expect(finance.readPlan(saved).budgets[0].limitPence).toBe(12550);
  expect(saved.transactions).toHaveLength(1007);
});
test('provider consent is fresh, edits revoke it and no AI request happens automatically', async () => {
  await mount();
  await tab('Ask');
  await edit('OpenAI API key', 'synthetic-private-key');
  await edit(
    'What would you like help with?',
    'Help me understand recorded spending',
  );
  const consent =
    'Allow this question and selected financial facts to be processed by OpenAI';
  expect(button('Ask OpenAI with local evidence').props.disabled).toBe(true);
  await toggle(consent, true);
  expect(button('Ask OpenAI with local evidence').props.disabled).toBe(false);
  await edit('OpenAI model ID', 'synthetic-model');
  expect(button('Ask OpenAI with local evidence').props.disabled).toBe(true);
  expect(ask).not.toHaveBeenCalled();
  expect(JSON.stringify(saved)).not.toContain('synthetic-private-key');
  const anthropic = tree.root.findAll(
    node =>
      node.props.accessibilityLabel === 'Use Anthropic' &&
      typeof node.props.onPress === 'function',
  )[0];
  await act(async () => anthropic.props.onPress());
  expect(field('Anthropic API key').props.value).toBe('');
  expect(button('Ask Anthropic with local evidence').props.disabled).toBe(true);
});
test('offline purchase plans need impact review and explicit duplicate-safe confirmation', async () => {
  await mount();
  await tab('Plan');
  await press('Plan from a message');
  const original = 'Next month I might buy a bicycle for £120';
  await edit('Your plan, in your own words', original);
  await press('Review draft offline');
  expect(preparePlan).not.toHaveBeenCalled();
  expect(saved.conversationBudgets).toBeUndefined();
  await edit('Name this allocation', 'Bicycle');
  await edit('Category', 'Shopping');
  await press('Add to the allocation');
  expect(button('Confirm & save local plan').props.disabled).toBe(true);
  await press('Review impact');
  expect(text()).toContain('Cannot confirm affordability');
  expect(saved.conversationBudgets).toBeUndefined();
  expect(button('Confirm & save local plan').props.disabled).toBe(false);
  await edit('Name this allocation', 'Bicycle plan');
  expect(button('Confirm & save local plan').props.disabled).toBe(true);
  await press('Review impact');
  const confirm = button('Confirm & save local plan');
  await act(async () => {
    confirm.props.onPress();
    confirm.props.onPress();
  });
  expect(saved.conversationBudgets!.records).toHaveLength(1);
  expect(saved.conversationBudgets!.records[0].proposal.originalText).toBe(
    original,
  );
  expect(finance.readPlan(saved).budgets[0].limitPence).toBe(12000);
  expect(runtime.calendar).not.toHaveBeenCalled();
  expect(saved.transactions).toHaveLength(1007);
  await press('Review calendar reminder');
  expect(runtime.calendar).not.toHaveBeenCalled();
  await press('Open calendar editor with this draft');
  expect(runtime.calendar).toHaveBeenCalledTimes(1);
  expect(call(runtime.calendar).mock.calls[0][2]).not.toContain('£');
  expect(call(runtime.calendar).mock.calls[0][2]).not.toContain(original);
});
test('AI preparation sends no request before separately named consent and opens editable review only', async () => {
  await mount();
  await tab('Plan');
  await press('Plan from a message');
  const original = 'Next month I might buy a bicycle for £120 in Shopping';
  await edit('Your plan, in your own words', original);
  await press('Use AI to prepare draft');
  await edit('OpenAI API key', 'synthetic-plan-key');
  expect(button('Send to OpenAI & prepare draft').props.disabled).toBe(true);
  expect(preparePlan).not.toHaveBeenCalled();
  call(preparePlan).mockResolvedValue({
    draft: finance.parseDraft(original, finance.todayISO()),
    name: 'bicycle',
    category: 'Shopping',
    provider: 'openai',
    model: 'synthetic-model',
  });
  await toggle(
    'Allow this message and reference date to be processed by OpenAI',
    true,
  );
  await press('Send to OpenAI & prepare draft');
  expect(preparePlan).toHaveBeenCalledTimes(1);
  const options = call(preparePlan).mock.calls[0][0];
  expect(options.message).toBe(original);
  expect(options.workspace).toBeUndefined();
  expect(options.consent.provider).toBe('openai');
  expect(field('Name this allocation').props.value).toBe('bicycle');
  expect(saved.conversationBudgets).toBeUndefined();
  expect(button('Confirm & save local plan').props.disabled).toBe(true);
  expect(JSON.stringify(saved)).not.toContain('synthetic-plan-key');
});
test('Google discovery exposes an honest no-match state rather than choosing a personal ID', async () => {
  await mount();
  await tab('Settings');
  await press('Connect or change Google spreadsheet');
  expect(runtime.connect).toHaveBeenCalledTimes(1);
  expect(text()).toContain('No matching spreadsheet');
  expect(runtime.importSheet).not.toHaveBeenCalled();
});

test('switching providers ignores an in-flight planning response', async () => {
  await mount();
  await tab('Plan');
  await press('Plan from a message');
  const original = 'Next month buy a bicycle for £120 in Shopping';
  await edit('Your plan, in your own words', original);
  await press('Use AI to prepare draft');
  await edit('OpenAI API key', 'synthetic-cancel-key');
  let resolve: ((value: unknown) => void) | undefined;
  call(preparePlan).mockImplementation(
    () =>
      new Promise(done => {
        resolve = done;
      }),
  );
  await toggle(
    'Allow this message and reference date to be processed by OpenAI',
    true,
  );
  await press('Send to OpenAI & prepare draft');
  expect(preparePlan).toHaveBeenCalledTimes(1);
  const choose = tree.root.findAll(
    node =>
      node.props.accessibilityLabel === 'Use Anthropic' &&
      typeof node.props.onPress === 'function',
  )[0];
  await act(async () => choose.props.onPress());
  await act(async () =>
    resolve?.({
      draft: finance.parseDraft(original, finance.todayISO()),
      name: 'bicycle',
      category: 'Shopping',
      provider: 'openai',
      model: 'synthetic-model',
    }),
  );
  expect(field('Name this allocation')).toBeUndefined();
  expect(saved.conversationBudgets).toBeUndefined();
  expect(button('Send to Anthropic & prepare draft').props.disabled).toBe(true);
  expect(field('Anthropic API key').props.value).toBe('');
});

test('unreadable saved data is preserved without a demo or automatic reset', async () => {
  call(runtime.load).mockRejectedValue(new Error('Synthetic corruption'));
  await mount();
  expect(text()).toContain('It has not been reset or overwritten');
  expect(runtime.save).not.toHaveBeenCalled();
  expect(runtime.demo).not.toHaveBeenCalled();
  expect(button('Try opening again')).toBeDefined();
});
test('multiple spreadsheet candidates require explicit choice', async () => {
  call(runtime.connect).mockResolvedValue([
    { id: 'synthetic-sheet-one', name: 'Example export one' },
    { id: 'synthetic-sheet-two', name: 'Example export two' },
  ]);
  call(runtime.importSheet).mockResolvedValue({
    ...saved,
    spreadsheetId: 'synthetic-sheet-two',
  });
  await mount();
  await tab('Settings');
  await press('Connect or change Google spreadsheet');
  expect(text()).toContain('Example export one');
  expect(text()).toContain('Example export two');
  expect(runtime.importSheet).not.toHaveBeenCalled();
  const choices = tree.root.findAll(
    node =>
      node.type === Button &&
      flat(node.props.children) === 'Use this spreadsheet',
  );
  await act(async () => choices[1].props.onPress());
  expect(runtime.importSheet).toHaveBeenCalledWith({
    id: 'synthetic-sheet-two',
    name: 'Example export two',
  });
});
