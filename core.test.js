import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {minor,csv,isoDate,importCSV,summary,budgetAlerts,freshness,notificationPayload} from './core.js';
const fixture=readFileSync(new URL('./fixtures/synthetic.csv',import.meta.url),'utf8');
const tx=importCSV(fixture).transactions;
test('decimal precision and rejection',()=>{assert.equal(minor('-0.01'),-1);assert.equal(minor('54.28'),5428);for(const v of ['1.234','NaN','1e3','9007199254740993'])assert.throws(()=>minor(v));});
test('quoted multiline CSV and BOM',()=>{assert.deepEqual(csv('\uFEFFa,b\r\n"x,y","a""b\nc"'),[['a','b'],['x,y','a"b\nc']]);assert.throws(()=>csv('"unclosed'));});
test('calendar validation',()=>{assert.equal(isoDate('29/02/2024'),'2024-02-29');assert.throws(()=>isoDate('31/02/2026'));assert.throws(()=>isoDate('02/29/2026'));});
test('deduplicates repeated imports without merging distinct payments',()=>{const result=importCSV(fixture,tx);assert.equal(result.transactions.length,7);assert.equal(result.duplicates,7);assert.equal(tx.filter(t=>t.name==='Demo Grocer').length,4);});
test('conflicting import is atomic',()=>{const before=JSON.stringify(tx);assert.throws(()=>importCSV(fixture.replace('-54.28','-55.28'),tx),/Conflicting/);assert.equal(JSON.stringify(tx),before);});
test('rejects unsupported currencies and malformed headers/rows',()=>{assert.throws(()=>importCSV(fixture.replace(',GBP,',',EUR,')),/GBP/);assert.throws(()=>importCSV('Date,Amount\n2026-11-01,10'),/Missing/);assert.throws(()=>importCSV(fixture+'bad,row\n'),/Column/);});
test('exact totals, credit offsets and period boundaries',()=>{const s=summary(tx,'2026-11-01','2026-11-30');assert.equal(s.categories.Groceries,17339);assert.equal(s.inflows,240425);assert.equal(s.outflows,22854);assert.equal(s.net,217571);assert.equal(summary(tx,'2026-11-02','2026-11-02').count,1);assert.throws(()=>summary(tx,'2026-12-01','2026-11-01'));});
test('category correction preserves raw evidence',()=>{const s=summary(tx,'2026-11-01','2026-11-30',{'syn-002':'Essentials'});assert.equal(s.categories.Essentials,5428);assert.equal(tx[1].raw.Category,'Groceries');});
test('budget thresholds, exactly full and over',()=>{assert.equal(budgetAlerts({categories:{Food:7999}},{Food:10000}).length,0);assert.equal(budgetAlerts({categories:{Food:8000}},{Food:10000})[0].level,'threshold');assert.equal(budgetAlerts({categories:{Food:10000}},{Food:10000})[0].remaining,0);assert.equal(budgetAlerts({categories:{Food:10001}},{Food:10000})[0].level,'over');assert.throws(()=>budgetAlerts({categories:{}},{Food:0}));});
test('freshness needs source coverage, not transaction recency',()=>{const now=new Date('2026-11-13T12:00:00Z');assert.equal(freshness(now.toISOString(),'',now).state,'unknown');assert.equal(freshness(now.toISOString(),'2026-11-11T00:00:00Z',now).state,'stale');assert.equal(freshness(now.toISOString(),'2026-11-13T10:00:00Z',now).state,'recent');assert.equal(freshness(now.toISOString(),'2026-11-14T00:00:00Z',now).state,'unknown');});
test('notification payload excludes financial amounts and categories',()=>{const p=notificationPayload({id:'Private category',category:'Private category',spent:12345},{state:'stale'});assert.ok(!JSON.stringify(p).includes('Private category'));assert.ok(!JSON.stringify(p).includes('12345'));assert.match(p.body,/freshness/);});

test('arbitrary category names cannot modify object prototypes',()=>{const t={...tx[1],category:'__proto__'};const s=summary([t],'2026-11-01','2026-11-30');assert.equal(s.categories['__proto__'],5428);assert.equal(Object.getPrototypeOf(s.categories),null);});
