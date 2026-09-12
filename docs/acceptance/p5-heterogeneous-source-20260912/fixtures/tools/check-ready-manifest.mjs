// 复刻 PipelineService.findReadyManifest 的判据（只读，不改任何文件）：
//   扫描 landing/manifests/*.json（非递归） → status=="READY" → accepted+quarantined>0 → 取最大 batchId
// 用法: node check-ready-manifest.mjs
import fs from 'node:fs';
import path from 'node:path';

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const ROOT = path.resolve(HERE, '../../../../..');
const dir = path.join(ROOT, 'landing/manifests');

const longOf = (v) => (typeof v === 'number' ? v : Number(String(v ?? '0').trim() || 0));
const eligible = [];
const files = fs.readdirSync(dir).filter((f) => f.endsWith('.json'));
for (const f of files) {
  let m;
  try { m = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8')); } catch { console.log(`  [解析失败] ${f}`); continue; }
  const accepted = longOf(m.acceptedRecords), quarantined = longOf(m.quarantinedRecords);
  const ok = m.status === 'READY' && accepted + quarantined > 0;
  if (ok) eligible.push({ f, batchId: longOf(m.batchId), accepted, quarantined, status: m.status });
}
eligible.sort((a, b) => a.batchId - b.batchId);
console.log(`landing/manifests 下 .json 文件 ${files.length} 个；满足判据(READY 且 accepted+quarantined>0)的批次：`);
for (const e of eligible) console.log(`  batchId=${e.batchId}  ${e.f}  accepted=${e.accepted} quarantined=${e.quarantined} status=${e.status}`);
const best = eligible.at(-1) ?? null;
console.log(`=> findReadyManifest 结果: ${best ? `batchId=${best.batchId} (${best.f})` : 'null（无可用清单）'}`);
