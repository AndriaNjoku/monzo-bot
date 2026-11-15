import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  AppState,
  FlatList,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StatusBar,
  Text,
  View,
  Share,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import * as finance from './src/domain';
import {
  ask,
  preparePlan,
  PROVIDERS,
  type Answer,
  type ProviderId,
} from './src/ai';
import { runtime } from './src/services/runtime';
import type { SheetChoice } from './src/services/drive';
import {
  Body,
  Button,
  Card,
  Field,
  Heading,
  Loading,
  Note,
  palette,
  Sheet,
  styles,
  Toggle,
} from './src/ui/kit';

type Tab = 'Home' | 'Activity' | 'Plan' | 'Ask' | 'Settings';
type Workspace = finance.Workspace;
type Commit = (next: Workspace, expectedRevision?: string) => Promise<void>;
const today = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(
    2,
    '0',
  )}-${String(d.getDate()).padStart(2, '0')}`;
};
function monthScope() {
  const d = new Date();
  const first = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(
    2,
    '0',
  )}-01`;
  const last = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate();
  return [first, `${first.slice(0, 8)}${last}`];
}
const pounds = (value: number | null) =>
  value === null ? '' : (value / 100).toFixed(2);
function errorText(error: unknown) {
  return error instanceof Error && error.message.length < 400
    ? error.message
    : 'Check the entered details and try again. Nothing was changed.';
}
const enabledProvider = (id: unknown): ProviderId =>
  id === 'anthropic' ? 'anthropic' : 'openai';

export default function App() {
  return (
    <SafeAreaProvider>
      <Companion />
    </SafeAreaProvider>
  );
}
export function Companion() {
  const [data, setData] = useState<Workspace | null>(null);
  const current = useRef<Workspace | null>(null);
  const [tab, setTab] = useState<Tab>('Home');
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [sources, setSources] = useState<SheetChoice[] | null>(null);
  const [connection, setConnection] = useState(false);
  const [provider, setProvider] = useState<ProviderId>('openai');
  const providerRef = useRef<ProviderId>('openai');
  const [key, setKey] = useState('');
  const [keyLoading, setKeyLoading] = useState(true);
  const [remember, setRemember] = useState(false);
  const [models, setModels] = useState<Record<ProviderId, string>>({
    openai: PROVIDERS.find(p => p.id === 'openai')!.defaultModel,
    anthropic: PROVIDERS.find(p => p.id === 'anthropic')!.defaultModel,
  });
  const epoch = useRef(0);
  const keyEpoch = useRef(0);
  const invalidateKeyLoads = useCallback(() => {
    keyEpoch.current += 1;
  }, []);
  const alive = useRef(true);
  const request = useRef<AbortController | null>(null);
  const changed = useCallback(() => {
    epoch.current++;
    request.current?.abort();
    request.current = null;
  }, []);
  const accept = useCallback(
    (value: Workspace) => {
      changed();
      current.current = value;
      setData(value);
    },
    [changed],
  );
  const recall = useCallback(async (id: ProviderId) => {
    const generation = ++keyEpoch.current;
    setKey('');
    setRemember(false);
    setKeyLoading(true);
    try {
      const saved = await runtime.readKey(id);
      if (alive.current && generation === keyEpoch.current) {
        setKey(saved);
        setRemember(!!saved);
      }
    } catch {
      if (alive.current && generation === keyEpoch.current) {
        setNotice(
          'The saved provider key could not be read. Enter it again or forget the saved key.',
        );
      }
    } finally {
      if (alive.current && generation === keyEpoch.current) {
        setKeyLoading(false);
      }
    }
  }, []);
  const open = useCallback(async () => {
    setLoading(true);
    setLoadError(false);
    try {
      const value = await runtime.load();
      if (!alive.current) {
        return;
      }
      accept(value);
      const id = enabledProvider(value.companionAiProvider);
      setProvider(id);
      providerRef.current = id;
      const savedModels = value.companionModels as
        | Record<string, string>
        | undefined;
      setModels(old => ({
        openai:
          savedModels?.openai || String(value.companionModel || old.openai),
        anthropic: savedModels?.anthropic || old.anthropic,
      }));
      await recall(id);
    } catch {
      if (alive.current) {
        setLoadError(true);
      }
    } finally {
      if (alive.current) {
        setLoading(false);
      }
    }
  }, [accept, recall]);
  useEffect(() => {
    alive.current = true;
    void open();
    const listener = AppState.addEventListener('change', state => {
      if (state !== 'active') {
        changed();
        setKey('');
        setRemember(false);
        keyEpoch.current++;
      } else if (current.current) {
        void recall(providerRef.current);
      }
    });
    return () => {
      alive.current = false;
      invalidateKeyLoads();
      changed();
      listener.remove();
    };
  }, [open, changed, recall, invalidateKeyLoads]);
  const commit: Commit = async (next, expectedRevision) => {
    setBusy(true);
    try {
      if (
        expectedRevision &&
        (!current.current ||
          finance.workspaceRevision(current.current) !== expectedRevision)
      ) {
        throw new Error(
          'Your source or plan changed. Review current data before saving.',
        );
      }
      await runtime.save(next);
      if (alive.current) {
        accept(next);
      }
    } finally {
      if (alive.current) {
        setBusy(false);
      }
    }
  };
  const run = async (
    action: () => Promise<void>,
    failure = 'That action could not finish. Your previous data is preserved.',
  ) => {
    if (busy) {
      return;
    }
    setBusy(true);
    setNotice('');
    try {
      await action();
    } catch {
      if (alive.current) {
        setNotice(failure);
      }
    } finally {
      if (alive.current) {
        setBusy(false);
      }
    }
  };
  const changeProvider = (id: ProviderId) => {
    if (id === provider) {
      return;
    }
    changed();
    providerRef.current = id;
    setProvider(id);
    void recall(id);
  };
  const providerSession: ProviderSession = {
    provider,
    key,
    keyLoading,
    blocked: busy,
    model: models[provider],
    remember,
    choose: changeProvider,
    setKey: value => {
      changed();
      setKey(value);
    },
    setModel: value => {
      changed();
      setModels(old => ({ ...old, [provider]: value }));
    },
    setRemember: value => {
      changed();
      setRemember(value);
    },
    prepare: async source => {
      if (
        keyLoading ||
        busy ||
        !key.trim() ||
        !models[provider].match(/^[A-Za-z0-9._:-]{1,100}$/)
      ) {
        throw new Error(
          'Enter the selected provider key and a valid model ID.',
        );
      }
      if (
        !current.current ||
        finance.workspaceRevision(current.current) !==
          finance.workspaceRevision(source)
      ) {
        throw new Error(
          'Your source or plan changed. Review the current workspace before sending.',
        );
      }
      if (remember) {
        await runtime.saveKey(provider, key.trim());
      } else {
        await runtime.forgetKey(provider);
      }
      if (
        !current.current ||
        finance.workspaceRevision(current.current) !==
          finance.workspaceRevision(source)
      ) {
        throw new Error(
          'Your source or plan changed. Review the current workspace before sending.',
        );
      }
      const next = {
        ...source,
        companionAiProvider: provider,
        companionModels: { ...models },
        ...(provider === 'openai' ? { companionModel: models[provider] } : {}),
      };
      await runtime.save(next);
      current.current = next;
      setData(next);
    },
  };
  const aiGuard: AiGuard = {
    begin: () => {
      changed();
      const controller = new AbortController();
      request.current = controller;
      return {
        epoch: epoch.current,
        signal: controller.signal,
        day: today(),
        revision: finance.workspaceRevision(current.current!),
      };
    },
    valid: token =>
      alive.current &&
      token.epoch === epoch.current &&
      token.day === today() &&
      !!current.current &&
      token.revision === finance.workspaceRevision(current.current),
    cancel: changed,
  };
  const connect = () => {
    setConnection(true);
    setSources(null);
    void run(async () => {
      const choices = await runtime.connect();
      setSources(choices);
    }, 'Google could not be connected. Check authorization and try again; no source was selected.');
  };
  if (loading) {
    return (
      <SafeAreaView style={styles.root}>
        <Loading text="Opening your private workspace…" />
      </SafeAreaView>
    );
  }
  if (loadError || !data) {
    return (
      <SafeAreaView style={styles.root}>
        <View style={styles.screen}>
          <Heading>Your data stays safe</Heading>
          <Note error>
            The saved workspace could not be opened. It has not been reset or
            overwritten.
          </Note>
          <Button onPress={() => void open()}>Try opening again</Button>
        </View>
      </SafeAreaView>
    );
  }
  return (
    <SafeAreaView style={styles.root} edges={['top', 'bottom']}>
      <StatusBar barStyle="dark-content" />
      <View style={styles.header}>
        <View>
          <Text style={styles.brand}>
            monzo bot<Text style={{ color: palette.coral }}> ●</Text>
          </Text>
          <Text style={styles.eyebrow}>YOUR MONEY, WITH A LITTLE HELP</Text>
        </View>
        <Pressable
          accessibilityLabel="Open connection"
          onPress={() => setConnection(true)}
        >
          <Text style={{ fontSize: 22 }}>↗</Text>
        </Pressable>
      </View>
      {notice ? (
        <View style={{ paddingHorizontal: 20 }}>
          <Note error>{notice}</Note>
          <Button secondary onPress={() => setNotice('')}>
            Dismiss notice
          </Button>
        </View>
      ) : null}
      {tab === 'Home' && (
        <Home
          data={data}
          commit={commit}
          busy={busy}
          onConnect={connect}
          onDemo={() =>
            Alert.alert(
              'Load synthetic demo data?',
              'This replaces this device’s current financial workspace, including any local plans. Export a private copy first if you want to keep it. Your Google source is unchanged.',
              [
                { text: 'Keep my workspace', style: 'cancel' },
                {
                  text: 'Replace with synthetic demo',
                  onPress: () =>
                    void run(async () => {
                      await commit(runtime.demo());
                    }),
                },
              ],
            )
          }
          onTab={setTab}
          onRefresh={() =>
            void run(async () => {
              accept(await runtime.refresh(data));
            })
          }
        />
      )}
      {tab === 'Activity' && <Activity data={data} commit={commit} />}
      {tab === 'Plan' && (
        <PlanScreen
          data={data}
          commit={commit}
          busy={busy}
          session={providerSession}
          guard={aiGuard}
        />
      )}
      {tab === 'Ask' && (
        <AskScreen data={data} session={providerSession} guard={aiGuard} />
      )}
      {tab === 'Settings' && (
        <Settings
          data={data}
          busy={busy}
          commit={commit}
          connect={connect}
          onRefresh={() =>
            void run(async () => {
              accept(await runtime.refresh(data));
            })
          }
          onDisconnect={() =>
            void run(async () => {
              accept(await runtime.disconnect(data));
            })
          }
          onForget={id =>
            void run(async () => {
              changed();
              const generation = ++keyEpoch.current;
              setKey('');
              setRemember(false);
              setKeyLoading(true);
              try {
                await runtime.forgetKey(id);
                setNotice(
                  id === 'all'
                    ? 'All saved provider keys removed.'
                    : `${
                        id === 'openai' ? 'OpenAI' : 'Anthropic'
                      } key removed.`,
                );
              } finally {
                if (alive.current && generation === keyEpoch.current) {
                  setKeyLoading(false);
                }
              }
            })
          }
          provider={provider}
          onImport={() =>
            void run(async () => {
              accept(await runtime.importData());
            })
          }
          onExport={() => void run(runtime.exportData)}
          onDelete={() =>
            Alert.alert(
              'Delete this device’s workspace?',
              'This removes imported records, local plans and remembered provider keys on this device. Your source spreadsheet is not changed.',
              [
                { text: 'Keep my data', style: 'cancel' },
                {
                  text: 'Delete local data',
                  style: 'destructive',
                  onPress: () =>
                    void run(async () => {
                      changed();
                      const generation = ++keyEpoch.current;
                      setKey('');
                      setRemember(false);
                      setKeyLoading(true);
                      try {
                        accept(await runtime.deleteAll());
                      } finally {
                        if (alive.current && generation === keyEpoch.current) {
                          setKeyLoading(false);
                        }
                      }
                    }),
                },
              ],
            )
          }
        />
      )}
      <View style={styles.nav}>
        {(['Home', 'Activity', 'Plan', 'Ask', 'Settings'] as Tab[]).map(
          (name, index) => (
            <Pressable
              accessibilityRole="tab"
              accessibilityState={{ selected: tab === name }}
              accessibilityLabel={name}
              key={name}
              onPress={() => {
                changed();
                setTab(name);
              }}
              style={[
                styles.navItem,
                tab === name && { backgroundColor: palette.paleCoral },
              ]}
            >
              <Text style={{ color: palette.ink, fontSize: 19 }}>
                {['⌂', '↕', '◒', '✳', '⚙'][index]}
              </Text>
              <Text
                style={[styles.navText, tab === name && { color: palette.ink }]}
              >
                {name}
              </Text>
            </Pressable>
          ),
        )}
      </View>
      {connection && (
        <Sheet
          title="Your Google connection"
          onClose={() => {
            setConnection(false);
            setSources(null);
          }}
        >
          <Body>
            Read your full transaction export. This independent app does not
            connect directly to your bank or modify your spreadsheet.
          </Body>
          <Note>
            Google authorization is read-only. Each spreadsheet has its own
            identity; no personal spreadsheet is hard-coded.
          </Note>
          {busy && <Loading text="Checking Google…" />}
          <Button disabled={busy} onPress={connect}>
            Connect Google & find spreadsheets
          </Button>
          {sources !== null && sources.length === 0 && (
            <Note>
              No matching spreadsheet candidates were found. Check that your
              export is a native Google Sheet in this Google account, then try
              discovery again.
            </Note>
          )}
          {sources &&
            sources.map(choice => (
              <Card key={choice.id} title={choice.name}>
                <Body>
                  {choice.modifiedTime
                    ? `Last modified: ${choice.modifiedTime}`
                    : 'Select to inspect for compatible transaction tabs.'}
                </Body>
                <Button
                  disabled={busy}
                  onPress={() =>
                    void run(async () => {
                      accept(await runtime.importSheet(choice));
                      setConnection(false);
                      setSources(null);
                    })
                  }
                >
                  Use this spreadsheet
                </Button>
              </Card>
            ))}
          {data.spreadsheetId ? (
            <Button
              secondary
              disabled={busy}
              onPress={() =>
                void run(async () => {
                  accept(await runtime.refresh(data));
                  setConnection(false);
                })
              }
            >
              Refresh current source
            </Button>
          ) : null}
          <Body>
            Reading an export does not prove it includes every payment. Coverage
            remains unverified, even after a successful refresh.
          </Body>
        </Sheet>
      )}
    </SafeAreaView>
  );
}

type ProviderSession = {
  provider: ProviderId;
  key: string;
  model: string;
  keyLoading: boolean;
  blocked: boolean;
  remember: boolean;
  choose: (id: ProviderId) => void;
  setKey: (value: string) => void;
  setModel: (value: string) => void;
  setRemember: (value: boolean) => void;
  prepare: (source: Workspace) => Promise<void>;
};
type Token = {
  epoch: number;
  day: string;
  revision: string;
  signal: AbortSignal;
};
type AiGuard = {
  begin: () => Token;
  valid: (token: Token) => boolean;
  cancel: () => void;
};
function ProviderFields({
  session,
  revoke,
}: {
  session: ProviderSession;
  revoke: () => void;
}) {
  const provider = PROVIDERS.find(p => p.id === session.provider)!;
  return (
    <>
      <View style={styles.tabs}>
        {PROVIDERS.map(p => (
          <Pressable
            accessibilityRole="radio"
            accessibilityState={{ checked: p.id === session.provider }}
            accessibilityLabel={`Use ${p.name}`}
            key={p.id}
            onPress={() => {
              revoke();
              session.choose(p.id);
            }}
            style={[
              styles.tab,
              p.id === session.provider && {
                borderColor: palette.coral,
                backgroundColor: palette.paleCoral,
              },
            ]}
          >
            <Text style={styles.label}>{p.name}</Text>
          </Pressable>
        ))}
      </View>
      <Body>{provider.host} · Your own API account</Body>
      <Field
        label={`${provider.name} API key`}
        value={session.key}
        onChangeText={value => {
          revoke();
          session.setKey(value);
        }}
        secureTextEntry
        autoCapitalize="none"
        autoCorrect={false}
        autoComplete="off"
        importantForAutofill="noExcludeDescendants"
        textContentType="none"
        editable={!session.keyLoading}
        maxLength={4000}
      />
      {session.keyLoading && <Body>Reading this provider’s saved key…</Body>}
      <Field
        label={`${provider.name} model ID`}
        value={session.model}
        onChangeText={value => {
          revoke();
          session.setModel(value);
        }}
        autoCapitalize="none"
        autoCorrect={false}
        maxLength={100}
      />
      <Body>{provider.note}</Body>
      <Toggle
        label={`Remember this ${provider.name} key on this device`}
        value={session.remember}
        onChange={value => {
          revoke();
          session.setRemember(value);
        }}
        hint="Encrypted provider-specific storage. Leaving this off removes this provider’s saved key when you send."
      />
      <Note>
        API charges apply to your account. Your chat subscription may not
        include API usage. No automatic retries or background AI requests.
      </Note>
    </>
  );
}
function SourceNote({ data }: { data: Workspace }) {
  const overview = finance.overview(data);
  const status = finance.freshness(data.lastSync);
  return (
    <>
      <View style={styles.statRow}>
        <View style={styles.pill}>
          <Text style={styles.pillText}>
            {data.mode === 'demo'
              ? 'SYNTHETIC DEMO'
              : status === 'recent'
              ? 'RECENTLY RETRIEVED'
              : 'REFRESH RECOMMENDED'}
          </Text>
        </View>
        <Text style={styles.rowSub}>
          {overview.transactionCount.toLocaleString()} records
        </Text>
      </View>
      <Body>
        {data.lastSync
          ? `Retrieved ${new Date(data.lastSync).toLocaleString()}`
          : 'No successful source retrieval recorded.'}
        {overview.lastDate ? ` · Latest recorded ${overview.lastDate}` : ''}
      </Body>
      <Text style={{ fontSize: 11, color: palette.muted }}>
        Export coverage unverified · This is not a live bank balance.
      </Text>
    </>
  );
}
function TxRow({
  transaction,
  data,
  onPress,
}: {
  transaction: finance.Transaction;
  data: Workspace;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${transaction.name}, ${
        transaction.date
      }, ${finance.money(transaction.amount)}`}
      style={styles.row}
      onPress={onPress}
    >
      <Text style={styles.rowIcon}>
        {finance.transactionEmoji(transaction, data)}
      </Text>
      <View style={{ flex: 1 }}>
        <Text style={styles.rowName}>
          {transaction.name || 'Unnamed transaction'}
        </Text>
        <Text style={styles.rowSub}>
          {transaction.date} ·{' '}
          {data.overrides?.[transaction.id] ||
            transaction.category ||
            'Uncategorized'}
        </Text>
      </View>
      <Text
        style={[
          styles.rowMoney,
          transaction.amount > 0 && { color: '#28735E' },
        ]}
      >
        {finance.money(transaction.amount)}
      </Text>
    </Pressable>
  );
}
function TransactionDetails({
  transaction,
  data,
  commit,
  onClose,
}: {
  transaction: finance.Transaction;
  data: Workspace;
  commit: Commit;
  onClose: () => void;
}) {
  const impacts = finance.assessTransaction(data, transaction.id, today());
  const [category, setCategory] = useState(
    finance.effectiveCategory(transaction, data),
  );
  const [emoji, setEmoji] = useState(
    data.categoryEmojiOverrides?.[category] || '',
  );
  const form = useFormSave(data, commit, onClose);
  return (
    <Sheet
      title={`${finance.transactionEmoji(transaction, data)} ${
        transaction.name || 'Transaction'
      }`}
      onClose={onClose}
    >
      <Text style={styles.amount}>{finance.money(transaction.amount)}</Text>
      <Body>
        {transaction.date} · {transaction.type}
      </Body>
      <Card title="How it fits your plan">
        <Body>
          {impacts.potMovement
            ? 'This is an internal Pot movement. It stays visible but is excluded from external spending.'
            : impacts.creditUnclassified
            ? 'This credit remains unclassified. It is not automatically treated as income or a refund.'
            : 'Recorded spending and matching allocations are compared locally.'}
        </Body>
        {impacts.budgetImpacts.map(impact => (
          <View key={impact.budgetIndex}>
            <Text style={styles.label}>
              {finance.categoryEmoji(impact.category)} {impact.category}{' '}
              {impact.essential ? '· Essential' : ''}
            </Text>
            <Body>
              Allocation before this record:{' '}
              {finance.money(impact.beforeRemainingPence)}
              {`\n`}After this record:{' '}
              {finance.money(impact.afterRemainingPence)}
            </Body>
          </View>
        ))}
        {!impacts.budgetImpacts.length && (
          <Body>
            No matching dated allocation. You can add a budget in Plan.
          </Body>
        )}
        <Body>
          This uses the current imported snapshot with and without this record,
          not a reconstructed historical bank balance.
        </Body>
      </Card>
      <Card title="Your local category">
        <Field
          label="Correct category"
          value={category}
          onChangeText={setCategory}
          maxLength={120}
        />
        <Field
          label="Category emoji, optional"
          value={emoji}
          onChangeText={setEmoji}
          maxLength={24}
        />
        <Body>
          Only your local classification changes. Original export fields and
          transaction IDs stay unchanged. A category emoji applies to that
          category; original transaction emojis remain visible.
        </Body>
        {form.error && <Note error>{form.error}</Note>}
        <Button
          disabled={form.saving}
          onPress={() =>
            void form.save(() => {
              const clean = category.trim();
              if (!clean) {
                throw new Error('Choose a category.');
              }
              const icons = { ...data.categoryEmojiOverrides };
              if (emoji.trim()) {
                icons[clean] = finance.validateEmoji(emoji.trim());
              } else {
                delete icons[clean];
              }
              return finance.validateWorkspace({
                ...data,
                overrides: { ...data.overrides, [transaction.id]: clean },
                categoryEmojiOverrides: icons,
              });
            })
          }
        >
          Save local category
        </Button>
      </Card>
      <Heading small>Original export details</Heading>
      {Object.entries(transaction.raw || {})
        .filter(([, value]) => value !== '')
        .map(([label, value]) => (
          <View key={label}>
            <Text style={styles.label}>{label}</Text>
            <Body>{String(value)}</Body>
          </View>
        ))}
      <Note>No source cells or recorded payments are changed.</Note>
    </Sheet>
  );
}
function ReviewCandidates({
  data,
  onTransaction,
}: {
  data: Workspace;
  onTransaction: (transaction: finance.Transaction) => void;
}) {
  const flags = finance.reviewFlags(data, today());
  if (!flags.length) {
    return null;
  }
  return (
    <Card title="Worth a look">
      <Note>
        Patterns to review, never a fraud verdict. These records can be
        perfectly legitimate.
      </Note>
      {flags.slice(0, 3).map((flag, i) => {
        const row = data.transactions.find(
          t => t.id === flag.evidenceTransactionIds[0],
        );
        return (
          <View key={`${flag.kind}-${i}`}>
            <Text style={styles.label}>
              {flag.kind === 'possible_duplicate'
                ? '↔ Similar payments'
                : '🔎 Larger than usual'}
            </Text>
            <Body>{flag.explanation}</Body>
            {row && (
              <TxRow
                transaction={row}
                data={data}
                onPress={() => onTransaction(row)}
              />
            )}
          </View>
        );
      })}
    </Card>
  );
}
function human(value: string) {
  return value.replace(/([A-Z])/g, ' $1').replace(/^./, ch => ch.toUpperCase());
}
function displayValue(value: unknown): string {
  if (value === null || value === undefined) {
    return 'Unknown';
  }
  if (Array.isArray(value)) {
    return value.length
      ? value
          .map(item =>
            typeof item === 'object'
              ? Object.entries(item)
                  .map(
                    ([k, v]) =>
                      `${human(k)}: ${
                        k.endsWith('Pence') && typeof v === 'number'
                          ? finance.money(v)
                          : displayValue(v)
                      }`,
                  )
                  .join(' · ')
              : String(item),
          )
          .join('\n')
      : 'None recorded';
  }
  if (typeof value === 'object') {
    return Object.entries(value)
      .map(
        ([k, v]) =>
          `${human(k)}: ${
            k.endsWith('Pence') && typeof v === 'number'
              ? finance.money(v)
              : displayValue(v)
          }`,
      )
      .join('\n');
  }
  return typeof value === 'boolean' ? (value ? 'Yes' : 'No') : String(value);
}
function Home({
  data,
  commit,
  busy,
  onConnect,
  onDemo,
  onTab,
  onRefresh,
}: {
  data: Workspace;
  commit: Commit;
  busy: boolean;
  onConnect: () => void;
  onDemo: () => void;
  onTab: (tab: Tab) => void;
  onRefresh: () => void;
}) {
  const overview = finance.overview(data);
  const [detail, setDetail] = useState<finance.Transaction | null>(null);
  const categories = Object.entries(overview.categoryDebits).sort(
    (a, b) => b[1] - a[1],
  );
  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <Heading>{'Your money,\nmore clearly.'}</Heading>
      <SourceNote data={data} />
      {!overview.transactionCount ? (
        <Card title="Make your transactions useful" tint>
          <Body>
            Connect your Monzo export for the whole picture: clear spending,
            personal budgets and a little help deciding what comes next.
          </Body>
          <Button disabled={busy} onPress={onConnect}>
            Connect your transaction export
          </Button>
          <Button secondary disabled={busy} onPress={onDemo}>
            Explore with synthetic demo data
          </Button>
        </Card>
      ) : (
        <>
          <Card tint>
            <Text style={styles.eyebrow}>RECORDED MONEY OUT · ALL HISTORY</Text>
            <Text style={styles.amount}>
              {finance.money(overview.moneyOut)}
            </Text>
            <Body>
              {overview.firstDate} → {overview.lastDate}
            </Body>
            <View style={styles.divider} />
            <View style={styles.statRow}>
              <View>
                <Text style={styles.label}>Money in · Unclassified</Text>
                <Heading small>{finance.money(overview.moneyIn)}</Heading>
              </View>
              <View>
                <Text style={styles.label}>Pot movements</Text>
                <Heading small>{overview.potMovementCount}</Heading>
              </View>
            </View>
            <Body>
              Gross external spending. Credits and Pot transfers stay separate;
              these totals are not your account balance.
            </Body>
          </Card>
          <ReviewCandidates data={data} onTransaction={setDetail} />
          <Card title="A little clarity">
            <Body>
              See where each payment fits, protect your essentials and turn a
              future purchase into a plan you can review.
            </Body>
            <Button onPress={() => onTab('Plan')}>
              Build your spending plan
            </Button>
            <Button secondary onPress={() => onTab('Ask')}>
              Explore with your AI companion
            </Button>
          </Card>
          <Card title="Recent activity">
            {finance
              .searchHistory(data, '')
              .slice(0, 4)
              .map(t => (
                <TxRow
                  key={t.id}
                  transaction={t}
                  data={data}
                  onPress={() => setDetail(t)}
                />
              ))}
            <Button secondary onPress={() => onTab('Activity')}>
              Explore your whole history
            </Button>
          </Card>
          {categories.length > 0 && (
            <Card title="Where it went">
              {categories.slice(0, 7).map(([category, amount]) => (
                <View style={{ gap: 7 }} key={category}>
                  <View style={styles.statRow}>
                    <Text style={styles.label}>
                      {finance.categoryEmoji(
                        category,
                        data.categoryEmojiOverrides,
                      )}{' '}
                      {category}
                    </Text>
                    <Text style={styles.label}>{finance.money(amount)}</Text>
                  </View>
                  <View style={styles.barTrack}>
                    <View
                      style={[
                        styles.barFill,
                        {
                          width: `${Math.max(
                            1,
                            Math.round(
                              (amount / Math.max(overview.moneyOut, 1)) * 100,
                            ),
                          )}%`,
                        },
                      ]}
                    />
                  </View>
                </View>
              ))}
              <Body>Share of all-history gross external spending.</Body>
            </Card>
          )}
          <Button secondary disabled={busy} onPress={onRefresh}>
            Refresh your export
          </Button>
        </>
      )}
      {detail && (
        <TransactionDetails
          transaction={detail}
          data={data}
          commit={commit}
          onClose={() => setDetail(null)}
        />
      )}
      <Body>
        Independent, unofficial open-source companion. Not affiliated with or
        endorsed by Monzo Bank.
      </Body>
    </ScrollView>
  );
}
function Activity({ data, commit }: { data: Workspace; commit: Commit }) {
  const [query, setQuery] = useState('');
  const [count, setCount] = useState(80);
  const [detail, setDetail] = useState<finance.Transaction | null>(null);
  const rows = finance.searchHistory(data, query);
  return (
    <View style={{ flex: 1 }}>
      <View style={{ paddingHorizontal: 20, paddingTop: 14 }}>
        <Heading>Your whole history</Heading>
        <Body>
          Search every imported row. No date range hides your transactions.
        </Body>
        <Field
          label="Search names, categories, notes or pots"
          value={query}
          onChangeText={value => {
            setQuery(value);
            setCount(80);
          }}
        />
        <Body>{rows.length.toLocaleString()} matching records</Body>
      </View>
      <FlatList
        data={rows.slice(0, count)}
        keyExtractor={t => t.id}
        contentContainerStyle={{ paddingHorizontal: 20, paddingBottom: 20 }}
        renderItem={({ item }) => (
          <TxRow
            transaction={item}
            data={data}
            onPress={() => setDetail(item)}
          />
        )}
        ItemSeparatorComponent={() => <View style={styles.divider} />}
        ListEmptyComponent={
          <Note>
            No transactions match this search. Your other history is still
            available.
          </Note>
        }
        ListFooterComponent={
          rows.length > count ? (
            <Button secondary onPress={() => setCount(n => n + 80)}>
              Show more transactions
            </Button>
          ) : undefined
        }
      />
      {detail && (
        <TransactionDetails
          transaction={detail}
          data={data}
          commit={commit}
          onClose={() => setDetail(null)}
        />
      )}
    </View>
  );
}

function PlanScreen({
  data,
  commit,
  busy,
  session,
  guard,
}: {
  data: Workspace;
  commit: Commit;
  busy: boolean;
  session: ProviderSession;
  guard: AiGuard;
}) {
  const plan = finance.readPlan(data);
  const [editor, setEditor] = useState<
    'budget' | 'cash' | 'commitment' | 'conversation' | null
  >(null);
  const [index, setIndex] = useState(-1);
  const statuses = finance.budgetStatus(data, today());
  const [error, setError] = useState('');
  const remove = async (kind: 'budget' | 'commitment', value: number) => {
    try {
      await commit(
        kind === 'budget'
          ? finance.removeBudget(data, value)
          : finance.removeCommitment(data, value),
      );
    } catch (e) {
      setError(errorText(e));
    }
  };
  const records = data.conversationBudgets?.records || [];
  const otherSource =
    !!data.conversationBudgets?.reviewSourceToken &&
    data.conversationBudgets.reviewSourceToken !== finance.sourceToken(data);
  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <Heading>{'Give your money\na purpose.'}</Heading>
      <Body>
        Protect what matters. Plan what comes next. Your full transaction
        history stays connected.
      </Body>
      {error && <Note error>{error}</Note>}
      <Card tint title="Something on your mind?">
        <Body>
          “Next month I might buy a bicycle for £120.” Turn your words into a
          tentative plan, then review how it fits.
        </Body>
        <Button disabled={busy} onPress={() => setEditor('conversation')}>
          Plan from a message
        </Button>
      </Card>
      <View style={styles.statRow}>
        <Heading small>Your allocations</Heading>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Create a budget"
          disabled={busy}
          onPress={() => {
            setIndex(-1);
            setEditor('budget');
          }}
        >
          <Text style={[styles.label, { color: '#8D423B' }]}>
            ＋ Add budget
          </Text>
        </Pressable>
      </View>
      {statuses.length === 0 && (
        <Card>
          <Body>
            Start with a category you care about. Budgets have their own dates;
            they never filter your history or move money.
          </Body>
          <Button
            onPress={() => {
              setIndex(-1);
              setEditor('budget');
            }}
          >
            Create your first budget
          </Button>
        </Card>
      )}
      {statuses.map((status, i) => (
        <Card key={`${status.category}-${status.start}-${i}`}>
          <View style={styles.statRow}>
            <Heading small>
              {finance.categoryEmoji(
                status.category,
                data.categoryEmojiOverrides,
              )}{' '}
              {status.category}
            </Heading>
            <Text style={styles.label}>
              {status.essential ? 'ESSENTIAL' : 'FLEXIBLE'}
            </Text>
          </View>
          <Text style={styles.amount}>
            {finance.money(status.remainingPence)}
          </Text>
          <Body>
            allocation remaining · {status.start} → {status.end}
          </Body>
          <View style={styles.barTrack}>
            <View
              style={[
                styles.barFill,
                {
                  width: `${Math.max(
                    0,
                    Math.min(
                      100,
                      (status.grossDebitsPence /
                        Math.max(status.limitPence, 1)) *
                        100,
                    ),
                  )}%`,
                  backgroundColor:
                    status.remainingPence < 0 ? palette.red : palette.coral,
                },
              ]}
            />
          </View>
          <Body>
            {finance.money(status.grossDebitsPence)} recorded debits of{' '}
            {finance.money(status.limitPence)} allocated. Credits{' '}
            {finance.money(status.creditsPence)} stay separate.
          </Body>
          {status.dailyGuidePence !== null && (
            <Body>
              Allocation guide: {finance.money(status.dailyGuidePence)} per
              remaining day. This is not available bank cash.
            </Body>
          )}
          <View style={styles.tabs}>
            <View style={{ flex: 1 }}>
              <Button
                secondary
                disabled={busy}
                onPress={() => {
                  setIndex(status.budgetIndex);
                  setEditor('budget');
                }}
              >
                Edit budget
              </Button>
            </View>
            <View style={{ flex: 1 }}>
              <Button
                secondary
                disabled={busy}
                onPress={() =>
                  Alert.alert(
                    'Remove this allocation?',
                    'Recorded transactions and source data stay unchanged.',
                    [
                      { text: 'Keep', style: 'cancel' },
                      {
                        text: 'Remove',
                        style: 'destructive',
                        onPress: () =>
                          void remove('budget', status.budgetIndex),
                      },
                    ],
                  )
                }
              >
                Remove
              </Button>
            </View>
          </View>
        </Card>
      ))}
      <Card title="Your cash context">
        <Body>
          {plan.reportedBalancePence === null
            ? 'No reported cash balance. Your export does not establish a current bank balance.'
            : `You reported ${finance.money(plan.reportedBalancePence)} on ${
                plan.reportedBalanceDate
              }. This is not verified bank cash.`}
        </Body>
        <Body>Protected reserve: {finance.money(plan.reservePence)}</Body>
        <Note>
          {plan.commitmentsComplete
            ? 'You marked the commitments list complete. Unrecorded spending may still change your position.'
            : 'Your commitments list is not marked complete. The app will withhold a complete cash-headroom figure.'}
        </Note>
        {plan.priorities.length > 0 && (
          <Body>Your priorities: {plan.priorities.join(' · ')}</Body>
        )}
        <Button secondary disabled={busy} onPress={() => setEditor('cash')}>
          Edit balance, reserve & priorities
        </Button>
      </Card>
      <Card title="Upcoming commitments">
        <Body>
          Only payments you explicitly confirm are treated as known commitments.
          Nothing here schedules or makes a payment.
        </Body>
        {plan.commitments.map((item, i) => (
          <View key={`${item.label}-${i}`}>
            <View style={styles.statRow}>
              <Text style={styles.label}>{item.label}</Text>
              <Text style={styles.label}>
                {finance.money(item.amountPence)}
              </Text>
            </View>
            <Body>
              {item.dueDate} ·{' '}
              {item.confirmed ? 'Confirmed by you' : 'Unconfirmed'}
            </Body>
            <View style={styles.tabs}>
              <View style={{ flex: 1 }}>
                <Button
                  secondary
                  disabled={busy}
                  onPress={() => {
                    setIndex(i);
                    setEditor('commitment');
                  }}
                >
                  Edit commitment
                </Button>
              </View>
              <View style={{ flex: 1 }}>
                <Button
                  secondary
                  disabled={busy}
                  onPress={() =>
                    Alert.alert(
                      'Remove this commitment?',
                      'Removing a local entry does not cancel a bank payment.',
                      [
                        { text: 'Keep', style: 'cancel' },
                        {
                          text: 'Remove',
                          style: 'destructive',
                          onPress: () => void remove('commitment', i),
                        },
                      ],
                    )
                  }
                >
                  Remove
                </Button>
              </View>
            </View>
          </View>
        ))}
        {!plan.commitments.length && <Body>No commitments recorded yet.</Body>}
        <Button
          secondary
          disabled={busy}
          onPress={() => {
            setIndex(-1);
            setEditor('commitment');
          }}
        >
          Add commitment
        </Button>
      </Card>
      {otherSource && records.length > 0 && (
        <Note>
          Saved conversational plans belong to another export. They are
          preserved, but are not reused with this source. Return to that export
          or reconcile them explicitly before planning here.
        </Note>
      )}
      {!otherSource && records.length > 0 && (
        <Card title="Plans you reviewed">
          <Body>
            Tentative allocations, not completed purchases. A recorded debit may
            overlap a plan; fulfilment has not been inferred.
          </Body>
          {records
            .slice(-20)
            .reverse()
            .map(record => (
              <View key={record.proposal.id}>
                <Text style={styles.label}>
                  {finance.categoryEmoji(
                    record.proposal.category,
                    data.categoryEmojiOverrides,
                  )}{' '}
                  {record.proposal.name} ·{' '}
                  {finance.money(record.proposal.amountPence)}
                </Text>
                <Body>
                  {record.proposal.from} → {record.proposal.to} ·{' '}
                  {record.status === 'cancelled'
                    ? 'Cancelled local plan'
                    : 'Tentative'}
                </Body>
                <Body>Your words: {record.proposal.originalText}</Body>
                <View style={styles.divider} />
              </View>
            ))}
          {records.length > 20 && (
            <Body>Latest 20 plans shown. Older records remain stored.</Body>
          )}
        </Card>
      )}
      <Note>
        Allocations are not cash. Overdraft is not money available to spend.
        This app cannot confidently establish affordability from an incomplete
        export.
      </Note>
      {editor === 'budget' && (
        <BudgetEditor
          data={data}
          commit={commit}
          index={index}
          onClose={() => setEditor(null)}
        />
      )}{' '}
      {editor === 'cash' && (
        <CashEditor
          data={data}
          commit={commit}
          onClose={() => setEditor(null)}
        />
      )}{' '}
      {editor === 'commitment' && (
        <CommitmentEditor
          data={data}
          commit={commit}
          index={index}
          onClose={() => setEditor(null)}
        />
      )}{' '}
      {editor === 'conversation' && (
        <ConversationEditor
          data={data}
          commit={commit}
          session={session}
          guard={guard}
          onClose={() => {
            guard.cancel();
            setEditor(null);
          }}
        />
      )}
    </ScrollView>
  );
}
function useFormSave(data: Workspace, commit: Commit, close: () => void) {
  const revision = useRef(finance.workspaceRevision(data));
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const active = useRef(true);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  return {
    error,
    saving,
    save: async (make: () => Workspace) => {
      if (saving) {
        return;
      }
      setError('');
      setSaving(true);
      try {
        if (revision.current !== finance.workspaceRevision(data)) {
          throw new Error(
            'Your source or plan changed. Close and reopen this form before saving. Your previous data is preserved.',
          );
        }
        await commit(make(), revision.current);
        if (active.current) {
          close();
        }
      } catch (e) {
        if (active.current) {
          setError(errorText(e));
        }
      } finally {
        if (active.current) {
          setSaving(false);
        }
      }
    },
  };
}
function BudgetEditor({
  data,
  commit,
  index,
  onClose,
}: {
  data: Workspace;
  commit: Commit;
  index: number;
  onClose: () => void;
}) {
  const previous = finance.readPlan(data).budgets[index];
  const scope = monthScope();
  const [category, setCategory] = useState(previous?.category || '');
  const [amount, setAmount] = useState(
    previous ? pounds(previous.limitPence) : '',
  );
  const [start, setStart] = useState(previous?.start || scope[0]);
  const [end, setEnd] = useState(previous?.end || scope[1]);
  const [essential, setEssential] = useState(previous?.essential || false);
  const form = useFormSave(data, commit, onClose);
  return (
    <Sheet
      title={previous ? 'Edit your allocation' : 'Create an allocation'}
      onClose={onClose}
    >
      <Body>
        Choose a category and explicit dates. This is a local allocation; your
        transactions and bank account are unchanged.
      </Body>
      <Field
        label="Category"
        value={category}
        onChangeText={setCategory}
        maxLength={120}
      />
      <Field
        label="Budget amount (£)"
        value={amount}
        onChangeText={setAmount}
        keyboardType="decimal-pad"
      />
      <Field
        label="First day (yyyy-MM-dd)"
        value={start}
        onChangeText={setStart}
        autoCapitalize="none"
      />
      <Field
        label="Last day (yyyy-MM-dd)"
        value={end}
        onChangeText={setEnd}
        autoCapitalize="none"
      />
      <Toggle
        label="Protect as essential"
        value={essential}
        onChange={setEssential}
      />
      {form.error && <Note error>{form.error}</Note>}
      <Button
        disabled={form.saving}
        onPress={() =>
          void form.save(() =>
            finance.upsertBudget(data, index, {
              category,
              start,
              end,
              limitPence: finance.parseMoney(amount),
              essential,
            }),
          )
        }
      >
        Save allocation
      </Button>
    </Sheet>
  );
}
function CashEditor({
  data,
  commit,
  onClose,
}: {
  data: Workspace;
  commit: Commit;
  onClose: () => void;
}) {
  const plan = finance.readPlan(data);
  const [balance, setBalance] = useState(pounds(plan.reportedBalancePence));
  const [date, setDate] = useState(plan.reportedBalanceDate || today());
  const [reserve, setReserve] = useState(pounds(plan.reservePence));
  const [priorities, setPriorities] = useState(plan.priorities.join('\n'));
  const [complete, setComplete] = useState(plan.commitmentsComplete);
  const form = useFormSave(data, commit, onClose);
  return (
    <Sheet title="Your cash context" onClose={onClose}>
      <Note>
        These figures are supplied by you, not verified by a bank. Leave cash
        blank if unknown. Overdraft is not usable cash.
      </Note>
      <Field
        label="Reported cash balance (£), optional"
        value={balance}
        onChangeText={setBalance}
        keyboardType="numbers-and-punctuation"
      />
      <Field
        label="Balance date (yyyy-MM-dd)"
        value={date}
        onChangeText={setDate}
        autoCapitalize="none"
      />
      <Field
        label="Protected reserve (£)"
        value={reserve}
        onChangeText={setReserve}
        keyboardType="decimal-pad"
      />
      <Field
        label="Your priorities, one per line"
        value={priorities}
        onChangeText={setPriorities}
        multiline
        maxLength={2000}
      />
      <Toggle
        label="I have reviewed my complete commitments list"
        value={complete}
        onChange={setComplete}
        hint="This is your declaration, not a guarantee of complete source coverage."
      />
      {form.error && <Note error>{form.error}</Note>}
      <Button
        disabled={form.saving}
        onPress={() =>
          void form.save(() =>
            finance.updateContext(data, {
              reportedBalancePence: balance.trim()
                ? finance.parseMoney(balance)
                : null,
              reportedBalanceDate: balance.trim() ? date : '',
              reservePence: finance.parseMoney(reserve),
              priorities: priorities
                .split('\n')
                .map(v => v.trim())
                .filter(Boolean),
              commitmentsComplete: complete,
            }),
          )
        }
      >
        Save cash context
      </Button>
    </Sheet>
  );
}
function CommitmentEditor({
  data,
  commit,
  index,
  onClose,
}: {
  data: Workspace;
  commit: Commit;
  index: number;
  onClose: () => void;
}) {
  const previous = finance.readPlan(data).commitments[index];
  const [label, setLabel] = useState(previous?.label || '');
  const [amount, setAmount] = useState(
    previous ? pounds(previous.amountPence) : '',
  );
  const [dueDate, setDueDate] = useState(previous?.dueDate || '');
  const [confirmed, setConfirmed] = useState(previous?.confirmed || false);
  const form = useFormSave(data, commit, onClose);
  return (
    <Sheet
      title={previous ? 'Edit commitment' : 'Add a known commitment'}
      onClose={onClose}
    >
      <Body>
        Record what you know. An entry does not create, cancel or execute a
        payment. Unconfirmed details remain unconfirmed.
      </Body>
      <Field
        label="Commitment name"
        value={label}
        onChangeText={setLabel}
        maxLength={120}
      />
      <Field
        label="Amount (£)"
        value={amount}
        onChangeText={setAmount}
        keyboardType="decimal-pad"
      />
      <Field
        label="Due date (yyyy-MM-dd)"
        value={dueDate}
        onChangeText={setDueDate}
        autoCapitalize="none"
      />
      <Toggle
        label="I confirm this payment and amount"
        value={confirmed}
        onChange={setConfirmed}
      />
      <Note>
        Changing the list clears your earlier completeness declaration. Review
        the full list again in cash context.
      </Note>
      {form.error && <Note error>{form.error}</Note>}
      <Button
        disabled={form.saving}
        onPress={() =>
          void form.save(() =>
            finance.upsertCommitment(data, index, {
              label,
              dueDate,
              amountPence: finance.parseMoney(amount),
              confirmed,
            }),
          )
        }
      >
        Save commitment
      </Button>
    </Sheet>
  );
}

function ConversationEditor({
  data,
  commit,
  session,
  guard,
  onClose,
}: {
  data: Workspace;
  commit: Commit;
  session: ProviderSession;
  guard: AiGuard;
  onClose: () => void;
}) {
  const [stage, setStage] = useState<'message' | 'ai' | 'draft' | 'saved'>(
    'message',
  );
  const [message, setMessage] = useState('');
  const [draft, setDraft] = useState<finance.Draft | null>(null);
  const [name, setName] = useState('');
  const [category, setCategory] = useState('');
  const [amount, setAmount] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [mode, setMode] = useState<finance.Mode | null>(null);
  const [review, setReview] = useState<{
    proposal: finance.Proposal;
    result: finance.AffordabilityResult;
  } | null>(null);
  const [consent, setConsent] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [provenance, setProvenance] = useState(
    'Offline rules · Editable draft',
  );
  const [savedProposal, setSavedProposal] = useState<finance.Proposal | null>(
    null,
  );
  const savedSource = useRef('');
  const [handoff, setHandoff] = useState<'calendar' | 'note' | null>(null);
  const consentDay = useRef(today());
  const alive = useRef(true);
  const saving = useRef(false);
  const voiceActive = useRef(false);
  const cancelAi = guard.cancel;
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      if (voiceActive.current) {
        voiceActive.current = false;
        void runtime.cancelVoice().catch(() => undefined);
      }
      cancelAi();
    };
  }, [cancelAi]);
  useEffect(() => {
    setConsent(false);
    setReview(null);
  }, [session.provider, session.key, session.model, session.remember]);
  const revision = finance.workspaceRevision(data);
  useEffect(() => {
    setReview(null);
  }, [revision]);
  const dirty = (update: () => void) => {
    update();
    setReview(null);
    setError('');
  };
  const fill = (
    value: finance.Draft,
    title: string,
    proposedName = '',
    proposedCategory = '',
  ) => {
    setDraft(value);
    setName(proposedName);
    setCategory(proposedCategory);
    setAmount(pounds(value.amountPence));
    setFrom(value.from || '');
    setTo(value.to || '');
    setMode(null);
    setReview(null);
    setError('');
    setProvenance(title);
    setStage('draft');
  };
  const offline = () => {
    try {
      fill(
        finance.parseDraft(message, today()),
        'Offline rules · Editable draft',
      );
    } catch (e) {
      setError(errorText(e));
    }
  };
  const send = async () => {
    if (
      !consent ||
      pending ||
      consentDay.current !== today() ||
      session.keyLoading ||
      session.blocked
    ) {
      return;
    }
    setPending(true);
    setError('');
    const token = guard.begin();
    try {
      await session.prepare(data);
      if (!guard.valid(token)) {
        throw new Error('Changed');
      }
      const result = await preparePlan({
        provider: session.provider,
        key: session.key.trim(),
        model: session.model.trim(),
        message,
        today: token.day,
        consent: {
          provider: session.provider,
          processing: true,
          labels: false,
        },
        signal: token.signal,
      });
      if (!alive.current || !guard.valid(token)) {
        return;
      }
      fill(
        result.draft,
        `AI-assisted draft · ${
          PROVIDERS.find(p => p.id === result.provider)!.name
        } · ${result.model} · Untrusted suggestions`,
        result.name,
        result.category,
      );
    } catch {
      if (alive.current && guard.valid(token)) {
        setError(
          'The selected provider did not return an accepted draft. Your words remain here. Edit them or use the offline option; no plan was saved.',
        );
      }
    } finally {
      if (alive.current) {
        setPending(false);
        setConsent(false);
      }
    }
  };
  const voice = () =>
    Alert.alert(
      'Use on-device voice?',
      'Android’s on-device recognizer will fill an editable transcript. No cloud fallback or audio file is used. Check the words before reviewing or separately approving AI processing.',
      [
        { text: 'Keep typing', style: 'cancel' },
        {
          text: 'Use microphone',
          onPress: () => {
            voiceActive.current = true;
            setPending(true);
            void runtime
              .voice()
              .then(value => {
                if (alive.current) {
                  setMessage(value);
                  setConsent(false);
                }
              })
              .catch(() => {
                if (alive.current) {
                  setError(
                    'On-device recognition is unavailable or permission was not granted. Type or edit your message instead.',
                  );
                }
              })
              .finally(() => {
                voiceActive.current = false;
                if (alive.current) {
                  setPending(false);
                }
              });
          },
        },
      ],
    );
  const check = () => {
    if (!draft) {
      return;
    }
    setError('');
    try {
      if (!mode) {
        throw new Error(
          'Choose whether this adds to or uses an existing allocation.',
        );
      }
      const proposal = finance.reviewProposal(
        draft,
        {
          name,
          category,
          from,
          to,
          amountPence: finance.parseMoney(amount),
          mode,
        },
        today(),
      );
      const result = finance.assessAffordability(data, proposal, today());
      setReview({ proposal, result });
    } catch (e) {
      setReview(null);
      setError(errorText(e));
    }
  };
  const confirm = async () => {
    if (!review || !review.result.canSave || saving.current) {
      return;
    }
    saving.current = true;
    setPending(true);
    setError('');
    try {
      const next = finance.applyProposal(
        data,
        review.proposal,
        review.result,
        true,
        today(),
      );
      await commit(next, review.result.reviewRevision);
      if (alive.current) {
        setSavedProposal(review.proposal);
        savedSource.current = finance.sourceToken(data);
        setStage('saved');
      }
    } catch (e) {
      if (alive.current) {
        setError(errorText(e));
        setReview(null);
      }
    } finally {
      saving.current = false;
      if (alive.current) {
        setPending(false);
      }
    }
  };
  const provider = PROVIDERS.find(p => p.id === session.provider)!;
  return (
    <Sheet
      title={
        stage === 'saved'
          ? 'Your plan is saved'
          : stage === 'draft'
          ? 'Review your proposed plan'
          : stage === 'ai'
          ? `AI draft · ${provider.name}`
          : 'Plan in your own words'
      }
      onClose={() => {
        guard.cancel();
        onClose();
      }}
    >
      {stage === 'message' && (
        <>
          <Body>
            Tell your companion what you’re considering. Your message stays
            tentative; no purchase or payment is inferred.
          </Body>
          <Field
            label="Your plan, in your own words"
            value={message}
            onChangeText={setMessage}
            multiline
            maxLength={4000}
          />
          <Body>For example: “Next month I might buy a bicycle for £120.”</Body>
          <Button secondary disabled={pending} onPress={voice}>
            Speak a draft
          </Button>
          <Note>
            Review draft uses local rules and sends nothing to an AI service.
            Optional AI preparation asks separately before sharing your message.
          </Note>
          <Button disabled={pending || !message.trim()} onPress={offline}>
            Review draft offline
          </Button>
          <Button
            secondary
            disabled={pending || !message.trim()}
            onPress={() => {
              consentDay.current = today();
              setConsent(false);
              setError('');
              setStage('ai');
            }}
          >
            Use AI to prepare draft
          </Button>
        </>
      )}
      {stage === 'ai' && (
        <>
          <Note>
            Exactly what is sent to {provider.name}: the message below verbatim,
            reference date {consentDay.current}, and instructions for tentative
            draft fields. No imported transactions, balances, budgets, source
            identifiers or recordings are sent. Anything private you type is
            shared as written.
          </Note>
          <Field
            label="Planning message to send"
            value={message}
            onChangeText={value => {
              guard.cancel();
              setConsent(false);
              setMessage(value);
            }}
            multiline
            editable={!pending}
            maxLength={4000}
          />
          <ProviderFields session={session} revoke={() => setConsent(false)} />
          <Toggle
            label={`Allow this message and reference date to be processed by ${provider.name}`}
            value={consent}
            onChange={setConsent}
            disabled={pending}
          />
          <Body>
            One request. The returned amount and dates must pass local
            validation. Missing or ambiguous details stay unresolved. No budget
            changes automatically.
          </Body>
          {consentDay.current !== today() && (
            <Note error>
              The disclosed date changed. Return to your message and reopen AI
              preparation before sending.
            </Note>
          )}
          <Button
            disabled={
              pending ||
              !consent ||
              !session.key.trim() ||
              session.keyLoading ||
              session.blocked ||
              consentDay.current !== today()
            }
            onPress={() => void send()}
          >
            Send to {provider.name} & prepare draft
          </Button>
          <Button
            secondary
            disabled={pending}
            onPress={() => {
              guard.cancel();
              setStage('message');
              setConsent(false);
            }}
          >
            Keep offline draft
          </Button>
        </>
      )}
      {stage === 'draft' && draft && (
        <>
          <Note>{provenance}</Note>
          <Text style={styles.label}>Your original words</Text>
          <Body muted={false}>{draft.originalText}</Body>
          {draft.issues.map((issue, i) => (
            <Note key={i}>{issue}</Note>
          ))}
          <Field
            label="Name this allocation"
            value={name}
            onChangeText={value => dirty(() => setName(value))}
            editable={!pending}
            maxLength={120}
          />
          <Field
            label="Category"
            value={category}
            onChangeText={value => dirty(() => setCategory(value))}
            editable={!pending}
            maxLength={120}
          />
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 7 }}>
            {[
              ...new Set([
                ...Object.keys(finance.overview(data).categoryDebits),
                ...finance.readPlan(data).budgets.map(b => b.category),
              ]),
            ]
              .slice(0, 15)
              .map(label => (
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={`Use category ${label}`}
                  key={label}
                  disabled={pending}
                  onPress={() => dirty(() => setCategory(label))}
                  style={[styles.pill, { backgroundColor: palette.white }]}
                >
                  <Text style={styles.pillText}>
                    {finance.categoryEmoji(label)} {label}
                  </Text>
                </Pressable>
              ))}
          </View>
          <Field
            label="Amount (£)"
            value={amount}
            onChangeText={value => dirty(() => setAmount(value))}
            keyboardType="decimal-pad"
            editable={!pending}
          />
          <Field
            label="First day included (yyyy-MM-dd)"
            value={from}
            onChangeText={value => dirty(() => setFrom(value))}
            autoCapitalize="none"
            editable={!pending}
          />
          <Field
            label="Last day included (yyyy-MM-dd)"
            value={to}
            onChangeText={value => dirty(() => setTo(value))}
            autoCapitalize="none"
            editable={!pending}
          />
          <Text style={styles.label}>How should this fit your plan?</Text>
          <Button
            secondary={mode !== 'ADD_ALLOCATION'}
            disabled={pending}
            onPress={() => dirty(() => setMode('ADD_ALLOCATION'))}
          >
            Add to the allocation
          </Button>
          <Button
            secondary={mode !== 'WITHIN_EXISTING_ALLOCATION'}
            disabled={pending}
            onPress={() => dirty(() => setMode('WITHIN_EXISTING_ALLOCATION'))}
          >
            Use part of an existing allocation
          </Button>
          <Body>
            Adding raises the category’s local budget limit. Using an existing
            allocation keeps its limit unchanged. Neither choice moves money or
            establishes available bank cash.
          </Body>
          <Button secondary disabled={pending} onPress={check}>
            Review impact
          </Button>
          {review ? (
            <Card title="Your local review">
              <Note>{review.result.summary}</Note>
              {review.result.explanations.map((text, i) => (
                <Body key={`e${i}`}>{text}</Body>
              ))}
              {review.result.warnings.map((text, i) => (
                <Note key={`w${i}`}>{text}</Note>
              ))}
              <Body>
                Source cutoff: {review.result.sourceCutoffDate || 'Unknown'} ·
                Retrieval: {review.result.retrievalStatus} · Export coverage
                unverified.
              </Body>
              <Body>
                Reviewed purchase: {finance.money(review.proposal.amountPence)}{' '}
                · {review.proposal.from} → {review.proposal.to}
              </Body>
              {!review.result.canSave && (
                <Note error>
                  The allocation cannot be saved in its current form. Resolve
                  the warnings, edit the fields and review again.
                </Note>
              )}
            </Card>
          ) : (
            <Note>
              Review the exact fields against your local plan before confirming.
              Any edit invalidates an earlier review.
            </Note>
          )}
          <Button
            disabled={pending || !review?.result.canSave}
            onPress={() => void confirm()}
          >
            Confirm & save local plan
          </Button>
          <Button
            secondary
            disabled={pending}
            onPress={() => {
              setReview(null);
              setStage('message');
            }}
          >
            Back to your message
          </Button>
        </>
      )}
      {stage === 'saved' && (
        <>
          <Text style={{ fontSize: 48, textAlign: 'center' }}>🌱</Text>
          <Heading small>Room for a thoughtful decision</Heading>
          <Body>
            Your tentative local plan is saved. No payment, borrowing, calendar
            event or note was created. Imported transactions were not changed.
          </Body>
          <Note>
            The original wording and duplicate-submit record are kept with your
            local plan. Recorded payments may overlap; this app does not assume
            a plan has been fulfilled.
          </Note>
          <Button secondary onPress={() => setHandoff('calendar')}>
            Review calendar reminder
          </Button>
          <Button secondary onPress={() => setHandoff('note')}>
            Review notes draft
          </Button>
          <Button onPress={onClose}>Back to your plan</Button>
        </>
      )}
      {handoff && savedProposal && (
        <Handoff
          proposal={savedProposal}
          kind={handoff}
          allowed={
            savedSource.current === finance.sourceToken(data) &&
            !!data.conversationBudgets?.records.some(
              r => r.proposal.id === savedProposal.id && r.status === 'active',
            )
          }
          onClose={() => setHandoff(null)}
        />
      )}{' '}
      {pending && (
        <Loading
          text={
            stage === 'ai'
              ? `Waiting for ${provider.name}… Closing ignores its response, but a sent request cannot be unsent.`
              : 'Preparing your draft…'
          }
        />
      )}{' '}
      {error && <Note error>{error}</Note>}
    </Sheet>
  );
}

function AskScreen({
  data,
  session,
  guard,
}: {
  data: Workspace;
  session: ProviderSession;
  guard: AiGuard;
}) {
  const [question, setQuestion] = useState('');
  const [consent, setConsent] = useState(false);
  const [labels, setLabels] = useState(false);
  const [pending, setPending] = useState(false);
  const [answer, setAnswer] = useState<Answer | null>(null);
  const [answerIdentity, setAnswerIdentity] = useState('');
  const [error, setError] = useState('');
  const alive = useRef(true);
  const provider = PROVIDERS.find(p => p.id === session.provider)!;
  const cancelAi = guard.cancel;
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      cancelAi();
    };
  }, [cancelAi]);
  useEffect(() => {
    setConsent(false);
    setLabels(false);
    setAnswer(null);
    setError('');
  }, [session.provider, session.key, session.model, session.remember]);
  const revision = finance.workspaceRevision(data);
  useEffect(() => {
    setConsent(false);
    setAnswer(null);
  }, [revision]);
  const revoke = () => {
    setConsent(false);
    setAnswer(null);
    setError('');
  };
  const send = async () => {
    if (!consent || pending || session.keyLoading || session.blocked) {
      return;
    }
    setPending(true);
    setAnswer(null);
    setError('');
    const token = guard.begin();
    const identity = `${provider.name} · ${session.model}`;
    try {
      await session.prepare(data);
      if (!guard.valid(token)) {
        return;
      }
      const result = await ask({
        provider: session.provider,
        key: session.key.trim(),
        model: session.model.trim(),
        question,
        workspace: data,
        today: token.day,
        consent: { provider: session.provider, processing: true, labels },
        signal: token.signal,
      });
      if (alive.current && guard.valid(token)) {
        setAnswer(result);
        setAnswerIdentity(identity);
      }
    } catch {
      if (alive.current && guard.valid(token)) {
        setError(
          `${provider.name} did not return an accepted grounded answer. Try a narrower question. No financial action was taken.`,
        );
      }
    } finally {
      if (alive.current) {
        setPending(false);
        setConsent(false);
      }
    }
  };
  return (
    <KeyboardAvoidingView
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      style={{ flex: 1 }}
    >
      <ScrollView
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={styles.screen}
      >
        <Heading>{'A little help\nwith the bigger picture.'}</Heading>
        <Body>
          Your companion can explore spending, budget pressure and payments
          worth reviewing. You stay in control.
        </Body>
        <Card title="Start with what matters">
          <View style={{ gap: 4 }}>
            {[
              'How does my recent spending fit my budgets?',
              'What flexible spending could I review to protect essentials?',
              'Which payments look unusual and why?',
            ].map(text => (
              <Button
                key={text}
                secondary
                disabled={pending}
                onPress={() => {
                  guard.cancel();
                  revoke();
                  setQuestion(text);
                }}
              >
                {text}
              </Button>
            ))}
          </View>
        </Card>
        <Card>
          <ProviderFields session={session} revoke={revoke} />
          <Field
            label="What would you like help with?"
            value={question}
            onChangeText={value => {
              guard.cancel();
              revoke();
              setQuestion(value);
            }}
            multiline
            editable={!pending}
            maxLength={2000}
          />
          <Toggle
            label="Share merchant, category and plan labels"
            value={labels}
            disabled={pending}
            onChange={value => {
              guard.cancel();
              setConsent(false);
              setLabels(value);
              setAnswer(null);
            }}
            hint="Off by default. Labels may contain personal names. Raw notes, addresses, receipts, Pot names and original transaction IDs stay out of tool results."
          />
          <Note>
            Sent to {provider.name}: your question as written, reference date,
            retrieval metadata and bounded numeric/date facts selected through
            local read-only tools. Labels are included only if you enable
            sharing above. Review personal details in your question before
            consenting.
          </Note>
          <Toggle
            label={`Allow this question and selected financial facts to be processed by ${provider.name}`}
            value={consent}
            disabled={pending}
            onChange={setConsent}
          />
          <Button
            disabled={
              pending ||
              !consent ||
              !question.trim() ||
              !session.key.trim() ||
              session.keyLoading ||
              session.blocked
            }
            onPress={() => void send()}
          >
            Ask {provider.name} with local evidence
          </Button>
          {pending && (
            <>
              <Loading
                text={`Working with ${provider.name}… Up to three bounded requests; no automatic retries.`}
              />
              <Button
                secondary
                onPress={() => {
                  guard.cancel();
                  setPending(false);
                  setConsent(false);
                  setError(
                    'Request stopped. An in-flight provider request may finish, but its answer will be ignored.',
                  );
                }}
              >
                Stop request
              </Button>
            </>
          )}
          {error && <Note error>{error}</Note>}
        </Card>
        {answer && (
          <Card title="Your companion’s response">
            <Body>
              {answerIdentity} · {answer.modelRequests} requests ·{' '}
              {answer.toolCalls} local read-only tool calls
            </Body>
            <Note>
              AI interpretation and suggestions are unverified. Calculated facts
              below are resolved locally from the imported source.
            </Note>
            <Body muted={false}>{answer.text}</Body>
            {answer.facts.map((fact, i) => (
              <View key={`${fact.toolCallId}-${fact.path}-${i}`}>
                <Text style={styles.label}>
                  Fact {i + 1}:{' '}
                  {fact.unit === 'GBP_pence' && typeof fact.value === 'number'
                    ? finance.money(fact.value)
                    : displayValue(fact.value)}
                </Text>
                <Body>
                  {human(
                    fact.path.split('/').filter(Boolean).slice(-1)[0] ||
                      'Source fact',
                  )}{' '}
                  · {fact.sourceRetrievedAt || 'Unknown retrieval'}
                </Body>
              </View>
            ))}
            {answer.suggestions.map((text, i) => (
              <Body key={`s${i}`}>• {text}</Body>
            ))}
            {!answer.complete && (
              <Note>
                The call limit was reached. No further requests were made.
              </Note>
            )}
            <Body>
              Source retrieval: {answer.sourceRetrievedAt || 'Unknown'} · Export
              coverage remains unverified.
            </Body>
            <Body>
              To prepare a budget change, open Plan → Plan from a message. Ask
              cannot save a budget, make a payment or contact anyone.
            </Body>
          </Card>
        )}
        <SourceNote data={data} />
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

function Settings({
  data,
  busy,
  commit,
  connect,
  onRefresh,
  onDisconnect,
  onForget,
  provider,
  onImport,
  onExport,
  onDelete,
}: {
  data: Workspace;
  busy: boolean;
  commit: Commit;
  connect: () => void;
  onRefresh: () => void;
  onDisconnect: () => void;
  onForget: (provider: ProviderId | 'all') => void;
  provider: ProviderId;
  onImport: () => void;
  onExport: () => void;
  onDelete: () => void;
}) {
  const [notifications, setNotifications] = useState(false);
  return (
    <ScrollView contentContainerStyle={styles.screen}>
      <Heading>Private by design.</Heading>
      <Body>Your source, your plan, your choice.</Body>
      <Card title="Connection & data">
        <SourceNote data={data} />
        <Button disabled={busy} onPress={connect}>
          Connect or change Google spreadsheet
        </Button>
        <Button secondary disabled={busy} onPress={onRefresh}>
          Refresh current export
        </Button>
        <Button secondary disabled={busy} onPress={onDisconnect}>
          Disconnect Google on this device
        </Button>
        <Body>
          Disconnect stops access here and keeps your local records. You can
          also revoke this app in your Google Account settings. Original source
          cells are never edited.
        </Body>
      </Card>
      <Card title="Private check-ins">
        <Body>
          {data.companionNotifications?.enabled
            ? 'Check-ins are enabled in your preferences. Android permissions and channel settings control delivery.'
            : 'Check-ins are off. Local insights remain available.'}
        </Body>
        <Body>
          Quiet on-device checks can remind you to refresh and review your
          budget. Android may delay background work. No remote push service or
          background AI calls.
        </Body>
        <Button disabled={busy} onPress={() => setNotifications(true)}>
          Notification preferences
        </Button>
      </Card>
      <Card title="Your AI keys">
        <Body>
          Keys are not saved by default. Remembered keys use separate encrypted
          storage for each provider and stay out of financial exports.
        </Body>
        <Button secondary disabled={busy} onPress={() => onForget(provider)}>
          Forget selected provider key
        </Button>
        <Button secondary disabled={busy} onPress={() => onForget('all')}>
          Forget all provider keys
        </Button>
      </Card>
      <Card title="Your private copy">
        <Body>
          Exports are plaintext financial data. A destination or app you choose
          may store or sync them outside this app’s encrypted storage.
        </Body>
        <Button
          secondary
          disabled={busy}
          onPress={() =>
            Alert.alert(
              'Export a private plaintext copy?',
              'Keep the destination private. This includes source transactions and confirmed local plans, but never provider keys.',
              [
                { text: 'Cancel', style: 'cancel' },
                { text: 'Choose destination', onPress: onExport },
              ],
            )
          }
        >
          Export private workspace
        </Button>
        <Button
          secondary
          disabled={busy}
          onPress={() =>
            Alert.alert(
              'Import a private workspace?',
              'Choose a previously exported workspace. Review the file carefully; importing replaces the local financial workspace and preserves provider credentials separately.',
              [
                { text: 'Keep current workspace', style: 'cancel' },
                { text: 'Choose file', onPress: onImport },
              ],
            )
          }
        >
          Import private workspace
        </Button>
      </Card>
      <Card title="Local deletion">
        <Body>
          Delete this device’s imported records, local plans and remembered
          provider keys. This does not delete your Google Sheet or change your
          bank account.
        </Body>
        <Button danger disabled={busy} onPress={onDelete}>
          Delete local workspace & keys
        </Button>
      </Card>
      <Body>
        Monzo Bot is an independent, unofficial open-source project. Not
        affiliated with or endorsed by Monzo Bank.
      </Body>
      <Note>
        React Native interface · Financial calculations run locally · Provider
        use is optional and explicitly approved.
      </Note>
      {notifications && (
        <NotificationEditor
          data={data}
          commit={commit}
          onClose={() => setNotifications(false)}
        />
      )}
    </ScrollView>
  );
}
function NotificationEditor({
  data,
  commit,
  onClose,
}: {
  data: Workspace;
  commit: Commit;
  onClose: () => void;
}) {
  const settings = data.companionNotifications;
  const openingRevision = useRef(finance.workspaceRevision(data));
  const [enabled, setEnabled] = useState(settings?.enabled || false);
  const [persistent, setPersistent] = useState(settings?.persistent || false);
  const [quietStart, setQuietStart] = useState(
    String(settings?.quietStart ?? 21),
  );
  const [quietEnd, setQuietEnd] = useState(String(settings?.quietEnd ?? 8));
  const [permission, setPermission] = useState<string>(
    'Checking Android permission…',
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    void runtime
      .notificationStatus()
      .then(status => {
        if (alive.current) {
          setPermission(
            status.permission
              ? 'Android notifications are allowed.'
              : 'Android permission or notification channel is off. Enabling will request permission when supported.',
          );
        }
      })
      .catch(() => {
        if (alive.current) {
          setPermission('Android notification status is unavailable.');
        }
      });
    return () => {
      alive.current = false;
    };
  }, []);
  const save = async () => {
    if (saving) {
      return;
    }
    setSaving(true);
    setError('');
    try {
      const start = Number(quietStart),
        end = Number(quietEnd);
      if (
        !/^\d{1,2}$/.test(quietStart) ||
        !/^\d{1,2}$/.test(quietEnd) ||
        start > 23 ||
        end > 23
      ) {
        throw new Error('Use whole hours from 0 to 23 for quiet hours.');
      }
      await runtime.configureNotifications({
        enabled,
        persistent,
        quietStart: start,
        quietEnd: end,
      });
      await commit(
        {
          ...data,
          companionNotifications: {
            enabled,
            persistent,
            quietStart: start,
            quietEnd: end,
            thresholdPercent: settings?.thresholdPercent || 80,
          },
        },
        openingRevision.current,
      );
      if (alive.current) {
        onClose();
      }
    } catch (e) {
      if (alive.current) {
        setError(errorText(e));
      }
    } finally {
      if (alive.current) {
        setSaving(false);
      }
    }
  };
  return (
    <Sheet title="Private check-ins" onClose={onClose}>
      <Body>
        Only generic reminders appear outside the app. No amounts, merchants,
        categories or account identifiers are displayed on the lock screen.
      </Body>
      <Toggle
        label="Enable private check-ins"
        value={enabled}
        onChange={setEnabled}
      />
      <Toggle
        label="Show a quiet companion status notification"
        value={persistent}
        onChange={setPersistent}
        hint="Optional persistent display. Android controls visibility and may let you dismiss it."
      />
      <Field
        label="Quiet hours start (0–23)"
        value={quietStart}
        onChangeText={setQuietStart}
        keyboardType="number-pad"
        maxLength={2}
      />
      <Field
        label="Quiet hours end (0–23)"
        value={quietEnd}
        onChangeText={setQuietEnd}
        keyboardType="number-pad"
        maxLength={2}
      />
      <Note>{permission}</Note>
      <Body>
        Work runs roughly hourly and can be delayed by battery or network
        restrictions. Six-hour cooldown and three-per-day limits prevent
        repeated check-ins. First imports establish a quiet baseline.
      </Body>
      {error && <Note error>{error}</Note>}
      <Button disabled={saving} onPress={() => void save()}>
        Save check-in preferences
      </Button>
    </Sheet>
  );
}

function Handoff({
  proposal,
  kind,
  allowed,
  onClose,
}: {
  proposal: finance.Proposal;
  kind: 'calendar' | 'note';
  allowed: boolean;
  onClose: () => void;
}) {
  const [amount, setAmount] = useState(false);
  const [day, setDay] = useState(proposal.from);
  const [error, setError] = useState('');
  const [opening, setOpening] = useState(false);
  const title = `Tentative plan: ${proposal.name}`;
  const description = `${proposal.category} · Planning window ${
    proposal.from
  } through ${proposal.to}. Tentative local plan, not a bank payment.${
    amount ? ` Planning amount ${finance.money(proposal.amountPence)}.` : ''
  }`;
  const open = async () => {
    if (!allowed || opening) {
      return;
    }
    setOpening(true);
    setError('');
    try {
      if (kind === 'calendar') {
        finance.isoDate(day);
        await runtime.calendar(title, day, description);
      } else {
        await Share.share({ title, message: `${title}\n${description}` });
      }
      onClose();
    } catch {
      setError(
        'The external editor could not be opened. Nothing was automatically saved.',
      );
    } finally {
      setOpening(false);
    }
  };
  return (
    <Sheet
      title={kind === 'calendar' ? 'Review calendar draft' : 'Review note text'}
      onClose={onClose}
    >
      <Note>
        Optional separate handoff. The next app may sync this information under
        its own policies. No event, note or message is created automatically.
      </Note>
      <Text style={styles.label}>{title}</Text>
      <Body>{description}</Body>
      {kind === 'calendar' && (
        <>
          <Field
            label="Reminder day (yyyy-MM-dd)"
            value={day}
            onChangeText={setDay}
            autoCapitalize="none"
          />
          <Body>
            This is an editable planning reminder, not an inferred payment due
            date. Choose a calendar and save in the external editor.
          </Body>
        </>
      )}
      <Toggle
        label="Include the planning amount in this handoff"
        value={amount}
        onChange={setAmount}
      />
      <Body>
        Original wording, source transaction details and account identifiers are
        excluded. The notes chooser can include sharing apps; choose the
        intended destination carefully.
      </Body>
      {!allowed && (
        <Note error>
          The saved plan is no longer current for this source. No external app
          will be opened.
        </Note>
      )}
      {error && <Note error>{error}</Note>}
      <Button disabled={!allowed || opening} onPress={() => void open()}>
        {kind === 'calendar'
          ? 'Open calendar editor with this draft'
          : 'Choose an app for this reviewed note'}
      </Button>
    </Sheet>
  );
}
