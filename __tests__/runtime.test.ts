import { createWorkspace } from '../src/domain';
let mockStored: string | null = null;
let mockPicked = '';
const mockNative = {
  loadWorkspace: jest.fn(async () => mockStored),
  saveWorkspace: jest.fn(async (s: string) => {
    mockStored = s;
  }),
  deleteWorkspace: jest.fn(async () => {
    mockStored = null;
  }),
  importWorkspace: jest.fn(async () => mockPicked),
  exportWorkspace: jest.fn(async () => undefined),
  readProviderKey: jest.fn(async () => 'synthetic-provider-key'),
  saveProviderKey: jest.fn(async () => undefined),
  forgetProviderKey: jest.fn(async () => undefined),
  authorizeGoogle: jest.fn(async () => ({
    accessToken: 'synthetic-google-token',
  })),
  disconnectGoogle: jest.fn(async () => undefined),
  recognizeSpeech: jest.fn(async () => 'Synthetic editable transcript'),
  notificationStatus: jest.fn(async () => ({
    enabled: false,
    permission: false,
    persistent: false,
  })),
  configureNotifications: jest.fn(async () => undefined),
  openCalendarDraft: jest.fn(async () => undefined),
};
jest.mock('../src/platform/native', () => ({ device: mockNative }));
let runtime: typeof import('../src/services/runtime').runtime;
beforeEach(async () => {
  jest.resetModules();
  jest.clearAllMocks();
  mockStored = null;
  mockPicked = '';
  runtime = require('../src/services/runtime').runtime;
});
it('initialises a stable encrypted workspace rather than inventing a source identity on each load', async () => {
  const first = await runtime.load();
  const second = await runtime.load();
  expect(first.workspaceId).toBe(second.workspaceId);
  expect(mockNative.saveWorkspace).toHaveBeenCalledTimes(1);
  expect(first.transactions).toEqual([]);
});
it('retains legacy financial data and leaves credentials outside JSON', async () => {
  const legacy = createWorkspace();
  legacy.mode = 'connected';
  legacy.spreadsheetId = 'synthetic_selected_sheet';
  mockStored = JSON.stringify(legacy);
  const loaded = await runtime.load();
  expect(loaded).toEqual(legacy);
  await runtime.readKey('anthropic');
  await runtime.saveKey('anthropic', 'synthetic-new-key');
  expect(mockNative.readProviderKey).toHaveBeenCalledWith('anthropic');
  expect(mockNative.saveProviderKey).toHaveBeenCalledWith(
    'anthropic',
    'synthetic-new-key',
  );
  expect(mockStored).not.toContain('synthetic-new-key');
});
it('rejects stale writes and never changes existing records on conflict', async () => {
  const loaded = await runtime.load();
  const externallyChanged = {
    ...loaded,
    workspaceId: 'synthetic-other-source',
  };
  mockStored = JSON.stringify(externallyChanged);
  await expect(runtime.save({ ...loaded, authorized: true })).rejects.toThrow(
    /changed/,
  );
  expect(JSON.parse(mockStored!).workspaceId).toBe('synthetic-other-source');
});
it('deleting invalidates queued edits and removes remembered keys via the native boundary', async () => {
  const old = await runtime.load();
  const pending = runtime.save({ ...old, mode: 'demo' });
  const deletion = runtime.deleteAll();
  await expect(pending).rejects.toThrow(/cancelled/);
  const blank = await deletion;
  expect(mockNative.deleteWorkspace).toHaveBeenCalledTimes(1);
  expect(blank.transactions).toEqual([]);
  expect(blank.workspaceId).not.toBe(old.workspaceId);
  expect(JSON.parse(mockStored!).workspaceId).toBe(blank.workspaceId);
});
it('does not recreate a removed source when storage vanished outside the JS queue', async () => {
  const old = await runtime.load();
  mockStored = null;
  await expect(runtime.save(old)).rejects.toThrow(/changed/);
  expect(mockStored).toBeNull();
});
it('financial JSON cannot become an API key container', async () => {
  const current = await runtime.load();
  await expect(
    runtime.save({ ...current, companionApiKey: 'synthetic-secret' }),
  ).rejects.toThrow(/credential/);
  expect(mockStored).not.toContain('synthetic-secret');
});
it('CSV import preserves source evidence but changes the source identity and does not trust Google authorization', async () => {
  const original = await runtime.load();
  mockPicked =
    'Transaction ID,Date,Name,Category,Amount,Currency,Emoji\nsynthetic-1,15/11/2026,Example,Shopping,-12.34,GBP,🛒';
  const imported = await runtime.importData();
  expect(imported.transactions[0].amount).toBe(-1234);
  expect(imported.workspaceId).not.toBe(original.workspaceId);
  expect(imported.authorized).toBe(false);
  expect(imported.mode).toBe('import');
  expect(imported.transactions[0].raw.Emoji).toBe('🛒');
});
it('JSON import clears device grants without silently dropping reviewed local records', async () => {
  await runtime.load();
  const imported = createWorkspace();
  imported.authorized = true;
  mockPicked = JSON.stringify(imported);
  const result = await runtime.importData();
  expect(result.authorized).toBe(false);
  expect(result.workspaceId).toBe(imported.workspaceId);
});
it('provider forgetting is explicit and isolated, voice just returns an editable transcript', async () => {
  await runtime.forgetKey('openai');
  expect(mockNative.forgetProviderKey).toHaveBeenCalledWith('openai');
  await runtime.forgetKey('all');
  expect(mockNative.forgetProviderKey).toHaveBeenCalledWith('all');
  expect(await runtime.voice()).toBe('Synthetic editable transcript');
  expect(mockNative.saveWorkspace).not.toHaveBeenCalled();
});

it('restoring a workspace preserves bill audit data but requires fresh local scan opt-in', async () => {
  const data = createWorkspace();
  data.companionBills = {
    enabled: true,
    confirmedCandidateIds: ['a'.repeat(64)],
  };
  mockPicked = JSON.stringify(data);
  const restored = await runtime.importData();
  expect(restored.companionBills).toEqual({
    enabled: false,
    confirmedCandidateIds: ['a'.repeat(64)],
  });
  expect(restored.transactions).toEqual(data.transactions);
});

it('rejects malformed bill permissions before persisting an imported workspace', async () => {
  const data = createWorkspace();
  mockStored = JSON.stringify(data);
  data.companionBills = { enabled: 'yes', confirmedCandidateIds: [] };
  mockPicked = JSON.stringify(data);
  await expect(runtime.importData()).rejects.toThrow(
    'Invalid local bill settings',
  );
  expect(mockNative.saveWorkspace).not.toHaveBeenCalled();
});
