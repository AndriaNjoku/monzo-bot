import { readBillSettings } from '../domain/bills';
import { device } from '../platform/native';
import {
  createWorkspace,
  demoWorkspace,
  validateWorkspace,
  workspaceRevision,
  type Workspace,
} from '../domain';
import { discoverSheets, readSheet, parseCsv, type SheetChoice } from './drive';

let accessToken = '';
let currentRevision = '';
let epoch = 0;
let serial: Promise<unknown> = Promise.resolve();
function exclusive<T>(work: () => Promise<T>): Promise<T> {
  const task = serial.then(work, work);
  serial = task.catch(() => undefined);
  return task;
}
function safeData(value: unknown): Workspace {
  const inspect = (node: unknown) => {
    if (!node || typeof node !== 'object') {
      return;
    }
    for (const [key, child] of Object.entries(node)) {
      if (
        /^(?:apiKey|companionApiKey|accessToken|refreshToken|idToken|providerKey|openaiKey|anthropicKey)$/i.test(
          key,
        )
      ) {
        throw new Error(
          'Financial data contains a credential field. It was not saved or exported.',
        );
      }
      inspect(child);
    }
  };
  inspect(value);
  const workspace = validateWorkspace(value);
  readBillSettings(workspace);
  return workspace;
}
async function load(): Promise<Workspace> {
  return exclusive(async () => {
    const json = await device.loadWorkspace();
    const data = json === null ? createWorkspace() : safeData(JSON.parse(json));
    if (json === null) {
      await device.saveWorkspace(JSON.stringify(data));
    }
    currentRevision = workspaceRevision(data);
    return data;
  });
}
async function save(data: Workspace): Promise<void> {
  const checked = safeData(data);
  const expected = currentRevision;
  const generation = epoch;
  return exclusive(async () => {
    if (generation !== epoch) {
      throw new Error(
        'This edit was cancelled because the workspace was deleted.',
      );
    }
    const stored = await device.loadWorkspace();
    if (
      expected &&
      (stored === null ||
        workspaceRevision(safeData(JSON.parse(stored))) !== expected)
    ) {
      throw new Error(
        'Your saved data changed. Refresh before applying this edit.',
      );
    }
    if (generation !== epoch) {
      throw new Error(
        'This edit was cancelled because the workspace was deleted.',
      );
    }
    await device.saveWorkspace(JSON.stringify(checked));
    currentRevision = workspaceRevision(checked);
  });
}
async function authorize() {
  const generation = epoch;
  const auth = await device.authorizeGoogle();
  if (generation !== epoch) {
    throw new Error(
      'Google connection was cancelled because the workspace changed.',
    );
  }
  accessToken = auth.accessToken;
  if (!accessToken) {
    throw new Error('Google did not grant read-only access.');
  }
}
async function importSheet(choice: SheetChoice): Promise<Workspace> {
  if (!accessToken) {
    await authorize();
  }
  const before = await load();
  const revision = workspaceRevision(before);
  const same = before.spreadsheetId === choice.id;
  const result = await readSheet(
    choice,
    accessToken,
    same ? before.transactions : [],
  );
  const latest = await load();
  if (workspaceRevision(latest) !== revision) {
    throw new Error(
      'The source or plan changed during import. Nothing was overwritten.',
    );
  }
  const next: Workspace = {
    ...before,
    workspaceId: same ? before.workspaceId : createWorkspace().workspaceId,
    mode: 'connected',
    authorized: true,
    spreadsheetId: choice.id,
    spreadsheetTitle: result.title,
    sheetTitle: result.tabs.join(', '),
    transactions: result.transactions,
    lastSync: result.retrievedAt,
    sourceAsOf: '',
  };
  await save(next);
  return next;
}
export const runtime = {
  load,
  save,
  async connect(): Promise<SheetChoice[]> {
    await authorize();
    return discoverSheets(accessToken);
  },
  async discover(): Promise<SheetChoice[]> {
    if (!accessToken) {
      await authorize();
    }
    return discoverSheets(accessToken);
  },
  importSheet,
  async refresh(data: Workspace): Promise<Workspace> {
    if (!data.spreadsheetId || typeof data.spreadsheetId !== 'string') {
      throw new Error(
        'Connect Google and choose your transaction export first.',
      );
    }
    // A foreground authorization may recover an expired token; no financial data is sent to an AI provider.
    await authorize();
    return importSheet({
      id: data.spreadsheetId,
      name: String(data.spreadsheetTitle || 'Selected export'),
    });
  },
  async disconnect(data: Workspace): Promise<Workspace> {
    await device.disconnectGoogle();
    accessToken = '';
    const next = { ...data, authorized: false };
    await save(next);
    return next;
  },
  demo: demoWorkspace,
  readKey: (provider: 'openai' | 'anthropic') =>
    device.readProviderKey(provider),
  saveKey: (provider: 'openai' | 'anthropic', key: string) =>
    device.saveProviderKey(provider, key),
  forgetKey: (provider: 'openai' | 'anthropic' | 'all') =>
    device.forgetProviderKey(provider),
  voice: () => device.recognizeSpeech(),
  cancelVoice: () => device.cancelSpeech(),
  notificationStatus: () => device.notificationStatus(),
  configureNotifications: (settings: {
    enabled: boolean;
    persistent: boolean;
    quietStart: number;
    quietEnd: number;
  }) => device.configureNotifications(settings),
  async deleteAll(): Promise<Workspace> {
    epoch++;
    accessToken = '';
    return exclusive(async () => {
      await device.deleteWorkspace();
      currentRevision = '';
      const blank = createWorkspace();
      await device.saveWorkspace(JSON.stringify(blank));
      currentRevision = workspaceRevision(blank);
      return blank;
    });
  },
  calendar: (title: string, day: string, description: string) =>
    device.openCalendarDraft(title, day, description),
  async exportData(): Promise<void> {
    await device.exportWorkspace();
  },
  async importData(): Promise<Workspace> {
    const generation = epoch;
    const text = await device.importWorkspace();
    if (generation !== epoch) {
      throw new Error('Import was cancelled because the workspace changed.');
    }
    const before = await load();
    const imported = text.trim().startsWith('{')
      ? safeData(JSON.parse(text))
      : {
          ...before,
          workspaceId: createWorkspace().workspaceId,
          mode: 'import',
          spreadsheetId: '',
          spreadsheetTitle: '',
          sheetTitle: '',
          transactions: parseCsv(text),
          lastSync: new Date().toISOString(),
          sourceAsOf: '',
        };
    // Imported Google grants are never trusted on a different device.
    const next = {
      ...imported,
      authorized: false,
      companionBills: {
        confirmedCandidateIds: [],
        ...(typeof imported.companionBills === 'object' &&
        imported.companionBills !== null
          ? imported.companionBills
          : {}),
        enabled: false,
      },
    };
    await save(next);
    return next;
  },
};
