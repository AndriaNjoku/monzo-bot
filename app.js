import {importCSV,summary,budgetAlerts,freshness,notificationPayload,minor,money} from './core.js';
const $ = id => document.getElementById(id);
let transactions = [], overrides = {}, budgets = {}, importedAt = '', notified = new Set(), enabled = false;
function element(tag, text, cls) { const e = document.createElement(tag); e.textContent = text; if(cls)e.className=cls; return e; }
function announce(text) { $('status').textContent = text; }
function render() {
  const report = summary(transactions, $('start').value, $('end').value, overrides);
  const fresh = freshness(importedAt, $('asof').value);
  $('freshness').textContent = `Export freshness: ${fresh.state}. Imported: ${importedAt || 'never'}. Latest transaction: ${transactions.at(-1)?.date || 'none'}. This is not a live bank feed.`;
  $('stats').replaceChildren(...[['Money in',report.inflows],['Money out',report.outflows],['Net movement',report.net]].map(([label,n])=>{const box=element('div',label,'stat');box.append(element('strong',money(n)));return box;}));
  $('chart').replaceChildren();
  const entries=Object.entries(report.categories).filter(([,n])=>n>0).sort((a,b)=>b[1]-a[1]);
  const max=Math.max(1,...entries.map(([,n])=>n));
  for(const [c,n] of entries){const row=element('div',`${c} · ${money(n)}`);const bar=element('div','','bar');bar.style.width=`${n/max*100}%`;row.append(bar);$('chart').append(row);}
  const alerts=budgetAlerts(report,budgets,Number($('threshold').value)/100);
  $('alert-list').replaceChildren(...(alerts.length?alerts.map(a=>element('p',`${a.category}: ${money(a.spent)} of ${money(a.limit)}. ${a.level==='over'?'Over budget':'Threshold reached'}; remaining ${money(a.remaining)}. Based on imported rows only.`,'alert')):[element('p',Object.keys(budgets).length?'No budget thresholds reached.':'Set a category budget to start.') ]));
  if(enabled)for(const alert of alerts){const key=[$('start').value,$('end').value,alert.id,alert.limit,alert.level].join('|');if(!notified.has(key)){const p=notificationPayload(alert,fresh);new Notification(p.title,p);notified.add(key);}}
  $('transactions').replaceChildren(...transactions.filter(t=>t.date >= $('start').value && t.date <= $('end').value).map(t=>{const row=document.createElement('tr');row.append(element('td',t.date),element('td',t.name));const cell=document.createElement('td'),input=document.createElement('input');input.value=overrides[t.id]||t.category;input.maxLength=80;input.setAttribute('aria-label',`Category for ${t.name} on ${t.date}`);input.onchange=()=>{overrides[t.id]=input.value.trim()||t.category;safeRender();};cell.append(input);row.append(cell,element('td',money(t.amount)));return row;}));
}
function safeRender(){try{render();}catch(e){announce(e.message);}}
function load(text,demo=false){const result=importCSV(text,transactions);transactions=result.transactions;importedAt=new Date().toISOString();announce(`${demo?'SYNTHETIC DEMO. ':''}${transactions.length} transactions in memory; ${result.duplicates} duplicate rows skipped.`);safeRender();}
$('file').onchange=async e=>{try{const f=e.target.files[0];if(!f)return;if(f.size>5000000)throw new Error('File limit is 5 MB');load(await f.text());}catch(err){announce(err.message);}e.target.value='';};
$('demo').onclick=async()=>{try{load(await (await fetch('./fixtures/synthetic.csv')).text(),true);}catch(e){announce(e.message);}};
$('clear').onclick=()=>{transactions=[];overrides={};budgets={};importedAt='';notified.clear();$('asof').value='';announce('Workspace cleared.');safeRender();};
for(const id of ['start','end','asof','threshold'])$(id).onchange=safeRender;
$('budget').onclick=()=>{try{const category=$('category').value.trim(),limit=minor($('limit').value);if(!category||limit<=0)throw new Error('Enter a category and positive budget');budgets[category]=limit;safeRender();}catch(e){announce(e.message);}};
$('notify').onclick=async()=>{if(!('Notification'in window)){announce('Notifications are unavailable in this browser.');return;}enabled=await Notification.requestPermission()==='granted';announce(enabled?'Generic notifications enabled for this open page.':'Notifications not enabled.');safeRender();};
safeRender();
