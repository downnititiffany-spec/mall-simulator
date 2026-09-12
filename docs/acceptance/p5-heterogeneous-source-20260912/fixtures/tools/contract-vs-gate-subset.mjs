// 契约「真子集」量化：canonical-event.v1.schema.json 的 payload.required  vs
// EventContractValidator.missingPayloadField 实际校验的字段集（逐事件类型）
// 两侧都从源文件解析（可复现），不手抄。
import fs from 'node:fs';
import path from 'node:path';

const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const ROOT = path.resolve(HERE, '../../../../..');
const schemaPath = path.join(ROOT, 'contract-specs/schemas/canonical-event.v1.schema.json');
const javaPath = path.join(ROOT, 'analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/ingestion/EventContractValidator.java');

const schema = JSON.parse(fs.readFileSync(schemaPath, 'utf8'));
const javaLines = fs.readFileSync(javaPath, 'utf8').split(/\r?\n/);
const javaText = javaLines.join('\n');
// 定位 switch 起点行号（用于 file:line 引用）
const switchLine = javaLines.findIndex((l) => l.includes('String[] required = switch (eventType)')) + 1;
const findBadLine = javaLines.findIndex((l) => l.includes('private String findBadAmount')) + 1;

// 契约侧：$defs.<事件类型>.required
const contract = {};
for (const [name, def] of Object.entries(schema.$defs ?? {})) {
  if (Array.isArray(def.required)) contract[name] = def.required;
}

// 闸门侧：case EventContract.X[, EventContract.Y] -> new String[]{...}
const gate = {};
const re = /case ((?:EventContract\.[A-Z_]+\s*,?\s*)+)->\s*new String\[\]\{([^}]*)\}/g;
for (const m of javaText.matchAll(re)) {
  const names = [...m[1].matchAll(/EventContract\.([A-Z_]+)/g)].map((x) => x[1].toLowerCase());
  const fields = [...m[2].matchAll(/"([^"]+)"/g)].map((x) => x[1]);
  for (const n of names) gate[n] = fields;
}

const canonByLower = Object.fromEntries(Object.keys(contract).map((k) => [k.toLowerCase(), k]));
const types = [...new Set([...Object.keys(contract), ...Object.keys(gate)])].sort();
let cTotal = 0, gTotal = 0;
const lines = [];
lines.push(`# 契约必需字段  vs  闸门实际校验字段（逐事件类型）`);
lines.push(`# 契约: ${path.relative(ROOT, schemaPath).replace(/\\/g, '/')}  $defs.<type>.required`);
lines.push(`# 闸门: ${path.relative(ROOT, javaPath).replace(/\\/g, '/')}:${switchLine} missingPayloadField 的 switch`);
lines.push(`# 金额类型检查: 同文件:${findBadLine} findBadAmount（只拒"既非文本又非数字"，JSON 数字一律放过）`);
lines.push('');
lines.push(['事件类型', '契约必需', '闸门校验', '契约有而闸门不查（静默放过）'].join('\t'));
for (const t of types) {
  const c = contract[t] ?? contract[canonByLower[t]] ?? [];
  const g = gate[t] ?? (gate[t.replace(/_created$/, '_created')] ?? []);
  const unchecked = c.filter((f) => !g.includes(f));
  cTotal += c.length; gTotal += g.length;
  lines.push([t, String(c.length), String(g.length), unchecked.join(',') || '（无）'].join('\t'));
}
lines.push('');
lines.push(`合计：契约必需 payload 字段 ${cTotal} 个，闸门实际校验 ${gTotal} 个（前者为后者的真超集）`);
lines.push(`闸门另查但契约未要求的字段：${[...new Set(Object.values(gate).flat())].filter((f) => !Object.values(contract).flat().includes(f)).join(',') || '（无）'}`);
const out = path.join(ROOT, 'docs/acceptance/p5-heterogeneous-source-20260912/raw/contract-vs-gate-subset.txt');
fs.writeFileSync(out, lines.join('\n') + '\n', 'utf8');
console.log(lines.join('\n'));
