import type { Transaction } from '../domain';
import { device } from '../platform/native';

export type SheetChoice = { id: string; name: string; modifiedTime?: string };
export type GoogleTransport = (
  url: string,
  token: string,
  signal?: AbortSignal,
) => Promise<any>;
const DRIVE = 'https://www.googleapis.com/drive/v3/files';
const SHEETS = 'https://sheets.googleapis.com/v4/spreadsheets/';
const required = [
  'Transaction ID',
  'Date',
  'Name',
  'Category',
  'Amount',
  'Currency',
];
const MAX_BYTES = 10 * 1024 * 1024;
const idPattern = /^[A-Za-z0-9_-]{15,200}$/;
const safeId = (id: string) => {
  if (!idPattern.test(id)) {
    throw new Error('Invalid spreadsheet selection.');
  }
  return id;
};
export const googleGet: GoogleTransport = async (url, token, signal) => {
  if (
    !(url.startsWith(DRIVE + '?') || url.startsWith(SHEETS)) ||
    !token ||
    /[\r\n]/.test(token)
  ) {
    throw new Error('Google authorization is required.');
  }
  if (signal?.aborted) {
    throw new Error('Google request cancelled.');
  }
  // Native transport rejects redirects and bounds bytes while reading, before JSON allocation.
  const text = await device.googleRequest(url, token);
  if (signal?.aborted) {
    throw new Error('Google request cancelled.');
  }
  if (text.length > MAX_BYTES || utf8Length(text) > MAX_BYTES) {
    throw new Error(
      'Google response exceeds the 10 MB limit. Nothing was imported.',
    );
  }
  try {
    return JSON.parse(text);
  } catch {
    throw new Error('Google returned an invalid response.');
  }
};
function utf8Length(value: string) {
  let n = 0;
  for (const c of value) {
    const code = c.codePointAt(0)!;
    n += code < 128 ? 1 : code < 2048 ? 2 : code < 65536 ? 3 : 4;
  }
  return n;
}

export async function discoverSheets(
  token: string,
  transport: GoogleTransport = googleGet,
  signal?: AbortSignal,
): Promise<SheetChoice[]> {
  const found: SheetChoice[] = [];
  let next = '';
  const pages = new Set<string>();
  for (let page = 0; page < 5; page++) {
    const url =
      DRIVE +
      '?q=' +
      encodeURIComponent(
        "mimeType='application/vnd.google-apps.spreadsheet' and trashed=false",
      ) +
      '&spaces=drive&pageSize=100&fields=' +
      encodeURIComponent('nextPageToken,files(id,name,modifiedTime)') +
      (next ? '&pageToken=' + encodeURIComponent(next) : '');
    const result = await transport(url, token, signal);
    if (!Array.isArray(result.files)) {
      throw new Error('Google did not return a valid spreadsheet list.');
    }
    for (const f of result.files) {
      if (
        !f ||
        typeof f.id !== 'string' ||
        !idPattern.test(f.id) ||
        typeof f.name !== 'string'
      ) {
        throw new Error('Google returned an invalid spreadsheet choice.');
      }
      if (!found.some(x => x.id === f.id)) {
        found.push({
          id: f.id,
          name: f.name,
          modifiedTime:
            typeof f.modifiedTime === 'string' ? f.modifiedTime : undefined,
        });
      }
    }
    next = typeof result.nextPageToken === 'string' ? result.nextPageToken : '';
    if (!next) {
      return found.sort(
        (a, b) =>
          Number(/monzo/i.test(b.name)) - Number(/monzo/i.test(a.name)) ||
          a.name.localeCompare(b.name),
      );
    }
    if (pages.has(next)) {
      throw new Error('Google repeated a listing page. No selection was made.');
    }
    pages.add(next);
  }
  throw new Error(
    'More than 500 spreadsheets are present. Discovery stopped without silently selecting a file.',
  );
}
function scalarRows(value: unknown): string[][] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.map(row => {
    if (!Array.isArray(row)) {
      throw new Error('Unsupported spreadsheet row.');
    }
    const cells = row.map(c => {
      if (c == null) {
        return '';
      }
      if (!['string', 'number', 'boolean'].includes(typeof c)) {
        throw new Error('Unsupported spreadsheet cell.');
      }
      return String(c);
    });
    while (cells.length && cells[cells.length - 1] === '') {
      cells.pop();
    }
    return cells;
  });
}
function headers(row: string[]) {
  const h = row.map(x => x.trim());
  if (h[0]?.startsWith('\uFEFF')) {
    h[0] = h[0].slice(1);
  }
  return h;
}
function matches(h: string[]) {
  return (
    h.length > 0 &&
    new Set(h).size === h.length &&
    !h.includes('') &&
    required.every(x => h.includes(x))
  );
}
function date(value: string) {
  let iso = value;
  const uk = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(value);
  if (uk) {
    iso = uk[3] + '-' + uk[2] + '-' + uk[1];
  }
  if (
    !/^\d{4}-\d{2}-\d{2}$/.test(iso) ||
    !Number.isFinite(Date.parse(iso + 'T00:00:00Z')) ||
    new Date(iso + 'T00:00:00Z').toISOString().slice(0, 10) !== iso
  ) {
    throw new Error('Invalid transaction date.');
  }
  return iso;
}
function sourceMoney(value: string) {
  const v = value.trim();
  if (!/^[+-]?(?:\d+|\d{1,3}(?:,\d{3})+)(?:\.\d{1,2})?$/.test(v)) {
    throw new Error('Invalid GBP amount precision.');
  }
  const sign = v.startsWith('-') ? -1 : 1;
  const parts = v.replace(/^[+-]/, '').replace(/,/g, '').split('.');
  const amount =
    sign * (Number(parts[0]) * 100 + Number((parts[1] || '').padEnd(2, '0')));
  if (!Number.isSafeInteger(amount)) {
    throw new Error('Transaction amount exceeds integer precision.');
  }
  return amount;
}
export function rowsToTransactions(
  rows: string[][],
  previous: Transaction[] = [],
): Transaction[] {
  if (!rows.length || rows.length > 50000) {
    throw new Error(
      'Expected a complete transaction export of at most 50,000 rows.',
    );
  }
  const h = headers(rows[0]);
  if (!matches(h) || h.length > 26) {
    throw new Error('Missing, duplicate or unsupported Monzo export columns.');
  }
  const byId = new Map<string, Transaction>();
  const evidence = (t: Transaction) =>
    JSON.stringify([
      t.date,
      t.name,
      t.category,
      t.type,
      t.amount,
      Object.entries(t.raw).sort(([a], [b]) => a.localeCompare(b)),
    ]);
  const add = (t: Transaction) => {
    const prior = byId.get(t.id);
    if (prior && evidence(prior) !== evidence(t)) {
      throw new Error(
        'A transaction ID changed. Import cancelled for reconciliation; existing data is preserved.',
      );
    }
    if (!prior) {
      byId.set(t.id, t);
    }
  };
  previous.forEach(add);
  rows.slice(1).forEach((row, offset) => {
    if (row.every(x => !x.trim())) {
      return;
    }
    try {
      if (row.length > h.length) {
        throw new Error('Extra cells.');
      }
      const raw: Record<string, string> = {};
      h.forEach((k, i) => {
        raw[k] = row[i] ?? '';
      });
      if (!raw['Transaction ID'].trim() || raw.Currency.trim() !== 'GBP') {
        throw new Error('Missing identity or unsupported currency.');
      }
      add({
        id: raw['Transaction ID'].trim(),
        date: date(raw.Date.trim()),
        name: raw.Name,
        category: raw.Category.trim() || 'Uncategorised',
        type: raw.Type || '',
        amount: sourceMoney(raw.Amount),
        raw,
      });
    } catch {
      throw new Error(
        'Transaction row ' +
          (offset + 2) +
          ' failed validation or conflicts with existing evidence. Nothing was imported.',
      );
    }
  });
  if (byId.size > 100000) {
    throw new Error(
      'The local history exceeds its safety limit. Nothing was imported.',
    );
  }
  return [...byId.values()].sort(
    (a, b) => a.date.localeCompare(b.date) || a.id.localeCompare(b.id),
  );
}
export async function readSheet(
  choice: SheetChoice,
  token: string,
  previous: Transaction[] = [],
  transport: GoogleTransport = googleGet,
  signal?: AbortSignal,
): Promise<{
  transactions: Transaction[];
  title: string;
  tabs: string[];
  retrievedAt: string;
}> {
  const id = safeId(choice.id);
  const meta = await transport(
    SHEETS +
      id +
      '?fields=' +
      encodeURIComponent(
        'properties(title,locale),sheets(properties(title,sheetType,hidden,gridProperties(rowCount,columnCount)))',
      ),
    token,
    signal,
  );
  if (meta.properties?.locale !== 'en_GB' || !Array.isArray(meta.sheets)) {
    throw new Error(
      'Use a United Kingdom locale native Google spreadsheet with readable transaction tabs.',
    );
  }
  const tabs = meta.sheets
    .map((x: any) => x.properties)
    .filter(
      (p: any) => p && !p.hidden && (!p.sheetType || p.sheetType === 'GRID'),
    );
  if (tabs.length > 10) {
    throw new Error(
      'More than ten visible tabs need review. No partial import was saved.',
    );
  }
  let transactions = previous;
  const matched: string[] = [];
  for (const tab of tabs) {
    if (typeof tab.title !== 'string') {
      throw new Error('Invalid spreadsheet tab.');
    }
    const quoted = "'" + tab.title.replace(/'/g, "''") + "'";
    const url = SHEETS + id + '/values/';
    const head = await transport(
      url +
        encodeURIComponent(quoted + '!A1:Z1') +
        '?majorDimension=ROWS&valueRenderOption=FORMATTED_VALUE',
      token,
      signal,
    );
    const h = headers(scalarRows(head.values)[0] || []);
    if (!matches(h)) {
      continue;
    }
    const grid = tab.gridProperties;
    if (
      !grid ||
      !Number.isInteger(grid.rowCount) ||
      grid.rowCount < 1 ||
      grid.rowCount > 50000 ||
      !Number.isInteger(grid.columnCount) ||
      grid.columnCount < 1 ||
      grid.columnCount > 26
    ) {
      throw new Error(
        'Transaction tab exceeds the complete 50,000-row/26-column import limits.',
      );
    }
    const result = await transport(
      url +
        encodeURIComponent(quoted + '!A1:Z' + grid.rowCount) +
        '?majorDimension=ROWS&valueRenderOption=FORMATTED_VALUE',
      token,
      signal,
    );
    const rows = scalarRows(result.values);
    if (
      !rows.length ||
      rows.length > grid.rowCount ||
      JSON.stringify(headers(rows[0])) !== JSON.stringify(h)
    ) {
      throw new Error(
        'Export headers changed or response is incomplete. Nothing was saved.',
      );
    }
    transactions = rowsToTransactions(rows, transactions);
    matched.push(tab.title);
  }
  if (!matched.length) {
    throw new Error(
      'No visible tab contains the required Monzo transaction export fields.',
    );
  }
  return {
    transactions,
    title: String(meta.properties.title || choice.name),
    tabs: matched,
    retrievedAt: new Date().toISOString(),
  };
}
export function parseCsv(
  text: string,
  previous: Transaction[] = [],
): Transaction[] {
  if (utf8Length(text) > MAX_BYTES) {
    throw new Error('CSV exceeds 10 MB.');
  }
  const rows: string[][] = [];
  let row: string[] = [],
    cell = '',
    quoted = false,
    closed = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          cell += '"';
          i++;
        } else {
          quoted = false;
          closed = true;
        }
      } else {
        cell += c;
      }
      continue;
    }
    if (c === '"') {
      if (cell || closed) {
        throw new Error('Invalid CSV quoting.');
      }
      quoted = true;
    } else if (c === ',' || c === '\n' || c === '\r') {
      row.push(cell);
      cell = '';
      closed = false;
      if (c !== ',') {
        rows.push(row);
        row = [];
        if (c === '\r' && text[i + 1] === '\n') {
          i++;
        }
      }
    } else {
      if (closed) {
        throw new Error('Invalid CSV after closing quote.');
      }
      cell += c;
    }
  }
  if (quoted) {
    throw new Error('CSV has an unfinished quoted field.');
  }
  if (cell || row.length) {
    row.push(cell);
    rows.push(row);
  }
  return rowsToTransactions(rows, previous);
}
