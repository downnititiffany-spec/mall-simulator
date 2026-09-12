// 只读侦察工具：从冻结的 canonical-event.v1.schema.json 中抽出各事件类型的 required 集合，
// 给出「契约口径的必需/可选字段」，作为 B2（缺可选字段）夹具设计的依据。
// 用法：node docs/acceptance/p5-heterogeneous-source-20260912/fixtures/tools/read-canonical-required.mjs
import fs from 'node:fs';

const path = 'contract-specs/schemas/canonical-event.v1.schema.json';
const raw = fs.readFileSync(path, 'utf8');
const s = JSON.parse(raw);

const crypto = await import('node:crypto');
console.log('file=' + path);
console.log('bytes=' + Buffer.byteLength(raw));
console.log('sha256=' + crypto.createHash('sha256').update(raw, 'utf8').digest('hex').toUpperCase());
console.log('top.required=' + JSON.stringify(s.required ?? null));
console.log('top.properties=' + Object.keys(s.properties ?? {}).join(','));
console.log('top.keys=' + Object.keys(s).join(','));

const defs = s.$defs ?? s.definitions ?? {};
console.log('defs=' + Object.keys(defs).join(','));

function walk(name, node, depth) {
  const pad = '  '.repeat(depth);
  if (!node || typeof node !== 'object') return;
  const req = node.required ?? null;
  const props = node.properties ? Object.keys(node.properties) : null;
  if (req || props) {
    console.log(`${pad}[${name}] required=${JSON.stringify(req)}`);
    if (props) console.log(`${pad}      props=${props.join(',')}`);
  }
  // 递归 oneOf/anyOf/allOf/items
  for (const branch of ['oneOf', 'anyOf', 'allOf']) {
    (node[branch] ?? []).forEach((b, i) => walk(`${name}.${branch}[${i}]`, b, depth + 1));
  }
  if (node.items && typeof node.items === 'object') walk(`${name}.items`, node.items, depth + 1);
  if (node.properties) {
    for (const [k, v] of Object.entries(node.properties)) walk(`${name}.${k}`, v, depth + 1);
  }
}

for (const [k, v] of Object.entries(defs)) walk(`$defs.${k}`, v, 0);

walk('root', s, 0);
