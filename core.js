// All monetary values are integer minor units. No network or storage in this module.
export function minor(value) {
  const s = String(value).trim();
  if (!/^-?\d+(\.\d{1,2})?$/.test(s)) throw new Error('Amount must have at most two decimal places');
  const [whole, fraction = ''] = s.replace('-', '').split('.');
  const n = Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
  if (!Number.isSafeInteger(n)) throw new Error('Amount exceeds supported precision');
  return s.startsWith('-') ? -n : n;
}
export function csv(text) {
  const rows = []; let row = [], cell = '', quoted = false;
  text = text.replace(/^\uFEFF/, '');
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === '"') {
      if (quoted && text[i + 1] === '"') { cell += '"'; i++; }
      else if (!quoted && cell) throw new Error('Malformed CSV quoting');
      else quoted = !quoted;
    } else if (c === ',' && !quoted) { row.push(cell); cell = ''; }
    else if ((c === '\n' || c === '\r') && !quoted) {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(cell); if (row.some(x => x !== '')) rows.push(row); row = []; cell = '';
    } else cell += c;
  }
  if (quoted) throw new Error('Unclosed CSV quote');
  row.push(cell); if (row.some(x => x !== '')) rows.push(row);
  return rows;
}
export function isoDate(s) {
  const parts = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(s);
  const v = parts ? `${parts[3]}-${parts[2]}-${parts[1]}` : s;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(v) || !Number.isFinite(Date.parse(v)) || new Date(v).toISOString().slice(0, 10) !== v) throw new Error('Invalid date');
  return v;
}
export function importCSV(text, previous = []) {
  const [headers, ...rows] = csv(text);
  if (!headers || new Set(headers).size !== headers.length) throw new Error('Missing or duplicate headers');
  for (const h of ['Transaction ID', 'Date', 'Name', 'Category', 'Amount', 'Currency']) if (!headers.includes(h)) throw new Error(`Missing column: ${h}`);
  const byId = new Map(previous.map(t => [t.id, t])); let duplicates = 0;
  rows.forEach((r, index) => {
    try {
      if (r.length !== headers.length) throw new Error('Column count mismatch');
      const raw = Object.fromEntries(headers.map((h, i) => [h, r[i]]));
      const id = raw['Transaction ID'].trim();
      if (!id) throw new Error('Missing transaction ID');
      if (raw.Currency !== 'GBP') throw new Error('Only GBP account currency is currently supported');
      const t = {id, date: isoDate(raw.Date), name: raw.Name, category: raw.Category || 'Uncategorised', amount: minor(raw.Amount), currency: raw.Currency, raw};
      if (byId.has(id)) {
        const old = byId.get(id);
        if (JSON.stringify(old.raw) !== JSON.stringify(raw)) throw new Error('Conflicting transaction ID; import cancelled');
        duplicates++; return;
      }
      byId.set(id, t);
    } catch (e) { throw new Error(`Row ${index + 2}: ${e.message}`); }
  });
  return {transactions: [...byId.values()].sort((a,b) => a.date.localeCompare(b.date)), duplicates};
}
export function summary(transactions, start, end, overrides = {}) {
  start = isoDate(start); end = isoDate(end);
  if (start > end) throw new Error('Start date must precede end date');
  const selected = transactions.filter(t => t.date >= start && t.date <= end);
  const categories = Object.create(null); let inflows = 0, outflows = 0;
  for (const t of selected) {
    if (t.amount > 0) inflows += t.amount;
    else outflows -= t.amount;
    const category = overrides[t.id] || t.category;
    categories[category] = (categories[category] || 0) - t.amount;
  }
  // Positive credits reduce category outflow, but are not assumed to be salary/refunds.
  return {categories, inflows, outflows, net: inflows - outflows, count: selected.length};
}
export function budgetAlerts(report, budgets, threshold = 0.8) {
  if (!(threshold > 0 && threshold <= 1)) throw new Error('Threshold must be between 0 and 1');
  return Object.entries(budgets).flatMap(([category, limit]) => {
    if (!Number.isSafeInteger(limit) || limit <= 0) throw new Error('Budget must be positive minor units');
    const spent = report.categories[category] || 0;
    return spent >= Math.ceil(limit * threshold) ? [{id: category, category, spent, limit, remaining: limit - spent, level: spent > limit ? 'over' : 'threshold'}] : [];
  });
}
export function freshness(importedAt, sourceAsOf, now = new Date()) {
  // Source coverage is explicitly user supplied; latest transaction != last sync.
  const a = Date.parse(importedAt), b = Date.parse(sourceAsOf), n = +now;
  if (![a,b,n].every(Number.isFinite) || b > n || a > n) return {state:'unknown'};
  return {state: n - b > 24 * 3600000 ? 'stale' : 'recent', sourceAsOf, importedAt};
}
export function notificationPayload(alert, fresh) {
  return {tag: 'private-budget-check', title: 'Budget check available', body: fresh.state === 'recent' ? 'Open your private dashboard to review a budget alert.' : 'Your export needs a freshness check. Open your private dashboard.', data: {route: '#alerts'}};
}
export const money = n => new Intl.NumberFormat('en-GB', {style:'currency',currency:'GBP'}).format(n / 100);
