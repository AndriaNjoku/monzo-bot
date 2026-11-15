import {
  discoverSheets,
  rowsToTransactions,
  readSheet,
  parseCsv,
  type GoogleTransport,
} from '../src/services/drive';
const h = [
  'Transaction ID',
  'Date',
  'Name',
  'Category',
  'Amount',
  'Currency',
  'Type',
  'Emoji',
  'Notes and #tags',
];
const row = [
  'synthetic-row',
  '15/11/2026',
  'Synthetic shop',
  'Groceries',
  '-12.34',
  'GBP',
  'Card payment',
  '🛒',
  'Synthetic only',
];
const choice = { id: 'synthetic_sheet_identifier', name: 'Monzo export' };
const meta = {
  properties: { title: 'Synthetic export', locale: 'en_GB' },
  sheets: [
    {
      properties: {
        title: 'Personal Account Transactions',
        gridProperties: { rowCount: 4, columnCount: 9 },
      },
    },
  ],
};
it('preserves complete source evidence, precise pence, stable IDs and original emojis', () => {
  const rows = rowsToTransactions([h, row]);
  expect(rows[0]).toMatchObject({
    id: 'synthetic-row',
    date: '2026-11-15',
    amount: -1234,
    raw: { Emoji: '🛒' },
  });
  expect(rowsToTransactions([h, row], rows)).toHaveLength(1);
  expect(() =>
    rowsToTransactions(
      [h, [...row.slice(0, 4), '-12.35', ...row.slice(5)]],
      rows,
    ),
  ).toThrow(/conflicts/);
  expect(rows).toHaveLength(1);
  expect(rows[0].amount).toBe(-1234);
});
it.each(['1.001', '1e3', 'NaN', '1,00', '9007199254740992', '£12'])(
  'rejects unsupported money %s without rounding',
  amount => {
    const changed = [...row];
    changed[4] = amount;
    expect(() => rowsToTransactions([h, changed])).toThrow();
  },
);
it('rejects invalid leap days, duplicate headers, unsupported currency and extra cells', () => {
  const bad = [...row];
  bad[1] = '29/02/2025';
  expect(() => rowsToTransactions([h, bad])).toThrow();
  const foreign = [...row];
  foreign[5] = 'USD';
  expect(() => rowsToTransactions([h, foreign])).toThrow();
  expect(() => rowsToTransactions([[...h, 'Date'], row])).toThrow();
  expect(() => rowsToTransactions([h, [...row, 'extra']])).toThrow();
});
it('parses quoted CSV notes and rejects malformed quotes rather than repairing evidence', () => {
  const csv =
    h.join(',') +
    '\r\n' +
    row.map((x, i) => (i === 8 ? '"Synthetic, quoted\nnote"' : x)).join(',');
  expect(parseCsv(csv)[0].raw['Notes and #tags']).toBe(
    'Synthetic, quoted\nnote',
  );
  expect(() => parseCsv(csv + '\n"unfinished')).toThrow(/unfinished/);
});
it('discovers paginated sheets, prioritises names but never silently chooses one', async () => {
  const calls: string[] = [];
  const transport: GoogleTransport = async url => {
    calls.push(url);
    return calls.length === 1
      ? {
          files: [{ id: 'synthetic_other_sheet', name: 'Other' }],
          nextPageToken: 'page2',
        }
      : { files: [choice] };
  };
  const found = await discoverSheets('synthetic-token', transport);
  expect(found).toHaveLength(2);
  expect(found[0]).toEqual(choice);
  expect(calls[1]).toContain('pageToken=page2');
  expect(
    calls.every(x =>
      x.startsWith('https://www.googleapis.com/drive/v3/files?'),
    ),
  ).toBe(true);
  expect(calls.join()).not.toContain('synthetic-token');
});
it('reports no matches and repeated pages safely', async () => {
  expect(
    await discoverSheets('synthetic-token', async () => ({ files: [] })),
  ).toEqual([]);
  await expect(
    discoverSheets('synthetic-token', async () => ({
      files: [],
      nextPageToken: 'repeat',
    })),
  ).rejects.toThrow(/repeated/);
});
it('imports all matching tabs and all rows, deduplicating only equal stable IDs', async () => {
  let call = 0;
  const transport: GoogleTransport = async () => {
    call++;
    if (call === 1) {
      return {
        ...meta,
        sheets: [
          meta.sheets[0],
          {
            properties: {
              ...meta.sheets[0].properties,
              title: 'Earlier history',
            },
          },
        ],
      };
    }
    if (call === 2 || call === 4) {
      return { values: [h] };
    }
    return {
      values: [
        h,
        row,
        [
          ...row.map((x, i) =>
            i === 0 ? 'synthetic-old-row' : i === 1 ? '01/01/2020' : x,
          ),
        ],
      ],
    };
  };
  const result = await readSheet(choice, 'synthetic-token', [], transport);
  expect(result.transactions).toHaveLength(2);
  expect(result.tabs).toHaveLength(2);
  expect(result.transactions[0].date).toBe('2020-01-01');
});
it('refuses changed headers, excess grids and foreign locale before persisting partial rows', async () => {
  await expect(
    readSheet(choice, 'synthetic-token', [], async () => ({
      ...meta,
      properties: { ...meta.properties, locale: 'en_US' },
    })),
  ).rejects.toThrow(/United Kingdom/);
  let n = 0;
  await expect(
    readSheet(choice, 'synthetic-token', [], async () =>
      ++n === 1 ? meta : n === 2 ? { values: [h] } : { values: [['changed']] },
    ),
  ).rejects.toThrow(/headers changed/);
  await expect(
    readSheet(
      { ...choice, id: '../../private' },
      'synthetic-token',
      [],
      async () => meta,
    ),
  ).rejects.toThrow(/Invalid spreadsheet/);
});
