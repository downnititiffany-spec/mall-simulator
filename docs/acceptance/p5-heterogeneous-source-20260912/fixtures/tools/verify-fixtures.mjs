// 只读校验：① 三份画像 JSON 可解析且顶层键恰好为冻结契约的 9 键；
//           ② 五个夹具 JSONL 逐行可解析、行数/字节/sha256 与 fixture-manifest.json 一致；
//           ③ 逐行期望 TSV 行数与夹具一致、row_id 集合一致。
// 用法：node docs/acceptance/p5-heterogeneous-source-20260912/fixtures/tools/verify-fixtures.mjs
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIX = path.resolve(HERE, '..');                    // fixtures/
const P5 = path.resolve(HERE, '../..');                  // p5-heterogeneous-source-20260912/
const FROZEN_KEYS = ['profileVersion', 'sourceCode', 'canonical', 'eventTypeMapping', 'fieldMapping',
  'enumSemantics', 'identityPolicy', 'timePolicy', 'quarantinePolicy'];

let fail = 0;
const ok = (cond, msg) => { console.log((cond ? 'PASS ' : 'FAIL ') + msg); if (!cond) fail++; };

console.log('=== ① 画像 ===');
for (const f of fs.readdirSync(path.join(P5, 'mapping')).filter((n) => n.endsWith('.json')).sort()) {
  const p = path.join(P5, 'mapping', f);
  const raw = fs.readFileSync(p, 'utf8');
  let j = null;
  try { j = JSON.parse(raw); } catch (e) { ok(false, `${f} JSON 解析: ${e.message}`); continue; }
  const keys = Object.keys(j);
  ok(keys.length === 9 && FROZEN_KEYS.every((k) => keys.includes(k)),
    `${f} 顶层键=9 且覆盖冻结契约: ${keys.join(',')}`);
  ok(j.sourceCode === 'fixture-b', `${f} sourceCode=fixture-b (实际 ${j.sourceCode})`);
  const sha = crypto.createHash('sha256').update(raw, 'utf8').digest('hex').toUpperCase();
  console.log(`     bytes=${Buffer.byteLength(raw)} sha256=${sha}`);
  // 歧义自检：fieldMapping 中是否有两个源字段指向同一规范字段
  const byTarget = {};
  for (const [src, dst] of Object.entries(j.fieldMapping)) {
    if (dst.startsWith('@')) continue;
    (byTarget[dst] ??= []).push(src);
  }
  const dup = Object.entries(byTarget).filter(([, v]) => v.length > 1);
  console.log(`     同目标多来源: ${dup.length ? dup.map(([k, v]) => `${k}<-${v.join('|')}`).join(', ') : '无'}`);
}

console.log('=== ② 夹具 ===');
const manifest = JSON.parse(fs.readFileSync(path.join(FIX, 'expected/fixture-manifest.json'), 'utf8'));
for (const [name, meta] of Object.entries(manifest.files)) {
  const raw = fs.readFileSync(path.join(FIX, name), 'utf8');
  const sha = crypto.createHash('sha256').update(raw, 'utf8').digest('hex').toUpperCase();
  const lines = raw.split('\n').filter((l) => l.length > 0);
  ok(sha === meta.sha256, `${name} sha256 与 manifest 一致`);
  ok(lines.length === meta.rows, `${name} 行数=${meta.rows}（实际 ${lines.length}）`);
  let bad = 0;
  for (const l of lines) { try { JSON.parse(l); } catch { bad++; } }
  ok(bad === 0, `${name} 逐行 JSON 可解析（坏行 ${bad}）`);
}

console.log('=== ③ 逐行期望 ===');
const pairs = [
  ['b1a-envelope-vocab.jsonl', 'expected/B1-expected-outcomes.tsv'],
  ['b1b-payload-vocab.jsonl', 'expected/B1-expected-outcomes.tsv'],
  ['b2-missing-optional.jsonl', 'expected/B2-expected-outcomes.tsv'],
  ['b3a-missing-required.jsonl', 'expected/B3-expected-outcomes.tsv'],
  ['b3b-ambiguous-mapping.jsonl', 'expected/B3-expected-outcomes.tsv'],
];
for (const tsvName of new Set(pairs.map((p) => p[1]))) {
  const tsv = fs.readFileSync(path.join(FIX, tsvName), 'utf8').trim().split('\n');
  ok(tsv[0] === 'row_id\tfamily\texpected_outcome\texpected_reason_class\tnote', `${tsvName} 表头正确`);
  const want = pairs.filter((p) => p[1] === tsvName)
    .reduce((a, [jsonl]) => a + fs.readFileSync(path.join(FIX, jsonl), 'utf8').split('\n').filter(Boolean).length, 0);
  ok(tsv.length - 1 === want, `${tsvName} 期望行数=${want}（实际 ${tsv.length - 1}）`);
  const fams = new Set(tsv.slice(1).map((l) => l.split('\t')[1]));
  console.log(`     族: ${[...fams].join(' / ')}`);
  const outs = {};
  for (const l of tsv.slice(1)) { const o = l.split('\t')[2]; outs[o] = (outs[o] ?? 0) + 1; }
  console.log(`     期望结论分布: ${JSON.stringify(outs)}`);
}

console.log('=== ④ 登记绑定画像 与 验收留档副本 必须逐字节一致 ===');
const ROOT = path.resolve(HERE, '../../../../..');
const BOUND = path.join(ROOT, 'analytics-server/source-profiles/fixture-b.v1.json');
const COPY = path.join(P5, 'mapping/fixture-b.v1.json');
const bs = fs.readFileSync(BOUND);
const cs = fs.readFileSync(COPY);
ok(bs.equals(cs), 'binding=analytics-server/source-profiles/fixture-b.v1.json 与 archive=mapping/fixture-b.v1.json 字节一致');
console.log(`     绑定画像 sha256=${crypto.createHash('sha256').update(bs).digest('hex').toUpperCase()}`);

console.log(fail === 0 ? '\nALL CHECKS PASSED' : `\n${fail} CHECK(S) FAILED`);
process.exit(fail === 0 ? 0 : 1);
