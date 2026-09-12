// 逐行对账：夹具期望（冻结于 EXPECTED.md） vs 平台实测（批次 45）
// 用法: node reconcile-actual.mjs <batchId> <quarantine-reasons.tsv>
// 只读：不写 landing/、不改 DB。
//
// 隔离理由的"真命中 / 偶然正确"判据（写死在脚本里，避免事后按结果编故事）：
//   真命中  = 平台给出的理由正是该行被期望拒绝的**业务判据**（缺必需字段 / 载荷非对象）
//   偶然正确 = 结果对（被隔离）但理由来自**异构词汇未归一**，与"识别出缺必需字段或映射歧义"无关
import fs from 'node:fs';
import path from 'node:path';

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const FIX = path.resolve(HERE, '..');
const P5 = path.resolve(HERE, '../..');
const ROOT = path.resolve(HERE, '../../../../..');

const batchId = process.argv[2] ?? '45';
const reasonsPath = process.argv[3];
if (!reasonsPath) { console.error('缺少隔离原因 TSV 路径'); process.exit(2); }

const FILES = [
  ['b1a-envelope-vocab.jsonl', 'B1-expected-outcomes.tsv'],
  ['b1b-payload-vocab.jsonl', 'B1-expected-outcomes.tsv'],
  ['b2-missing-optional.jsonl', 'B2-expected-outcomes.tsv'],
  ['b3a-missing-required.jsonl', 'B3-expected-outcomes.tsv'],
  ['b3b-ambiguous-mapping.jsonl', 'B3-expected-outcomes.tsv'],
];

// 族 → 理由判定类别（仅对实测=QUARANTINE 的行生效）
const FAMILY_CLASS = {
  'B3A-1': '真命中（平台确实识别出缺必需字段）',
  'B3A-3': '真命中（平台确实识别出缺必需字段/载荷非对象）',
  'B3B-A1': '偶然正确（理由=规范字段 amount 缺失，源自未归一，非"识别歧义"）',
  'B3B-B': '偶然正确（理由=非法 behavior_type，源自异构枚举未归一，非"识别歧义"）',
  'B1A': '不符期望（期望经配置归一后接纳）',
  'B1B': '不符期望（期望经配置归一后接纳）',
  'B2': '不符期望（期望按显式规则接纳）',
};
const classOf = (family) => FAMILY_CLASS[family.split(/[-—(（]/)[0]] ?? FAMILY_CLASS[family.split('-').slice(0, 2).join('-')] ?? '未分类';

// 期望表：按「族前缀 → 文件」归位，保留 TSV 内顺序（＝夹具文件行序，由下方 positional 校验证明）
const FAMILY_FILE = { B1A: 'b1a-envelope-vocab.jsonl', B1B: 'b1b-payload-vocab.jsonl', B2: 'b2-missing-optional.jsonl', B3A: 'b3a-missing-required.jsonl', B3B: 'b3b-ambiguous-mapping.jsonl' };
const fileOfFamily = (family) => FAMILY_FILE[family.split('-')[0]];
const expectedByFile = new Map();
{
  const byTsv = new Map();
  for (const tsv of new Set(FILES.map((f) => f[1]))) {
    const lines = fs.readFileSync(path.join(FIX, 'expected', tsv), 'utf8').trim().split('\n');
    byTsv.set(tsv, lines.slice(1).map((l) => {
      const [rowId, family, outcome, reasonClass, note] = l.split('\t');
      return { rowId, family, outcome, reasonClass, note };
    }));
  }
  for (const [file, tsv] of FILES) {
    expectedByFile.set(file, byTsv.get(tsv).filter((e) => fileOfFamily(e.family) === file));
  }
}

// 平台隔离原因：按文件 + 文件内序号 qline 连接（qline 由 SQL 的 ROW_NUMBER() 给出，见导出文件注释）
const qr = new Map();
{
  const lines = fs.readFileSync(reasonsPath, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/).filter((l) => l && !l.startsWith('#'));
  const head = lines[0].split('\t');
  const ix = Object.fromEntries(head.map((h, i) => [h, i]));
  for (const l of lines.slice(1)) {
    const c = l.split('\t');
    (qr.get(c[ix.file]) ?? qr.set(c[ix.file], new Map()).get(c[ix.file]))
      .set(Number(c[ix.qline]), { eventId: c[ix.event_id], reason: c[ix.reason] });
  }
}

const rows = [];
let mism = 0, agree = 0, positionalBad = 0, joinBad = 0;
for (const [file, tsv] of FILES) {
  const raw = fs.readFileSync(path.join(FIX, file), 'utf8').trim().split('\n');
  const expect = expectedByFile.get(file);
  // positional 校验：夹具行序 与 期望表行序 必须一一对应（有 id 的行用 id 值自证）
  expect.forEach((e, i) => {
    const obj = JSON.parse(raw[i]);
    const idv = obj.event_id ?? obj.msg_id ?? obj.id;
    if (idv && idv !== e.rowId) positionalBad++;
  });
  const rd = (p) => (fs.existsSync(p) ? new Set(fs.readFileSync(p, 'utf8').split('\n').filter(Boolean)) : new Set());
  const acc = rd(path.join(ROOT, `landing/accepted/${batchId}/${file}`));
  const quaSet = rd(path.join(ROOT, `landing/quarantine/${batchId}/${file}`));
  let qIdx = 0;
  for (let i = 0; i < raw.length; i++) {
    const text = raw[i];
    const e = expect[i];
    const isAcc = acc.has(text), isQua = quaSet.has(text);
    const actual = isAcc ? 'ACCEPT' : (isQua ? 'QUARANTINE' : 'LOST');
    let reason = '';
    if (isQua) {
      const rec = qr.get(file)?.get(++qIdx);
      reason = rec?.reason ?? '（原因未取到）';
      const obj = JSON.parse(text);
      const idv = obj.event_id ?? obj.msg_id ?? obj.id ?? null;
      if (rec && rec.eventId !== '<NULL>' && idv !== null && rec.eventId !== idv) joinBad++;
    }
    const verdict = e.outcome !== actual ? '结果不符' : (actual === 'QUARANTINE' ? '结果符·理由=' + classOf(e.family) : '一致');
    if (e.outcome !== actual) mism++; else agree++;
    rows.push([file, String(i + 1), e.rowId, e.family, e.outcome, e.reasonClass, actual, reason, verdict, idOfRaw(text) === null ? 'pos' : 'id']);
  }
}
function idOfRaw(t) { const o = JSON.parse(t); return o.event_id ?? o.msg_id ?? o.id ?? null; }
console.log(`positional 自证：期望表行序与夹具行序不一致的行数 = ${positionalBad}（0 = 行序一一对应成立）`);
console.log(`隔离记录 event_id ↔ 行 id 连接不一致数 = ${joinBad}（0 = qline 连接可信）`);

const head = ['file', 'line', 'row_id', 'family', 'expected_outcome', 'expected_reason_class', 'actual_outcome', 'actual_reason', 'verdict', 'join'];
fs.writeFileSync(path.join(P5, 'raw', `actual-outcomes-batch${batchId}.tsv`),
  [head.join('\t'), ...rows.map((r) => r.join('\t'))].join('\n') + '\n', 'utf8');

const sum = (p) => rows.filter(p).length;
console.log(`批次 ${batchId} 逐行对账：共 ${rows.length} 行 → raw/actual-outcomes-batch${batchId}.tsv`);
const byFam = {};
for (const r of rows) {
  const b = (byFam[r[3]] ??= { n: 0, acc: 0, qua: 0, expAcc: 0, expQua: 0, mismatch: 0 });
  b.n++; r[6] === 'ACCEPT' ? b.acc++ : b.qua++;
  r[4] === 'ACCEPT' ? b.expAcc++ : b.expQua++;
  if (r[8] === '结果不符') b.mismatch++;
}
console.log('  族别                       期望A/Q   实测A/Q   结果不符');
for (const [k, b] of Object.entries(byFam)) {
  console.log(`  ${k.padEnd(24)} ${String(b.expAcc + '/' + b.expQua).padEnd(9)} ${String(b.acc + '/' + b.qua).padEnd(9)} ${b.mismatch}`);
}
console.log(`  合计：期望 ACCEPT ${sum((r) => r[4] === 'ACCEPT')} / QUARANTINE ${sum((r) => r[4] === 'QUARANTINE')}`
  + ` ；实测 ACCEPT ${sum((r) => r[6] === 'ACCEPT')} / QUARANTINE ${sum((r) => r[6] === 'QUARANTINE')}`
  + ` ；结果不符 ${mism} ，结果相符 ${agree}`);
console.log('  实测隔离理由（逐行判定，非仅总数）：');
const rc = {};
for (const r of rows) if (r[6] === 'QUARANTINE') rc[`${r[7]}  ⇐  ${r[8]}`] = (rc[`${r[7]}  ⇐  ${r[8]}`] ?? 0) + 1;
for (const [k, v] of Object.entries(rc).sort((a, b) => b[1] - a[1])) console.log(`   ${String(v).padStart(3)}  ${k}`);

