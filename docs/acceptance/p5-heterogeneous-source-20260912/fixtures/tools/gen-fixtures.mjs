// ============================================================================
// P5 异构源人工配置接入验证 —— 夹具生成器（固定种子，确定性可复现）
// ----------------------------------------------------------------------------
// 用法：
//   node docs/acceptance/p5-heterogeneous-source-20260912/fixtures/tools/gen-fixtures.mjs
// 输出（全部覆盖写入，每次运行字节级一致）：
//   fixtures/b1a-envelope-vocab.jsonl        30 行  B1 子族 A：信封+payload 全异构词汇
//   fixtures/b1b-payload-vocab.jsonl         30 行  B1 子族 B：规范信封 + 异构 payload
//   fixtures/b2-missing-optional.jsonl       60 行  B2：缺可选/契约必需但闸门未查的字段
//   fixtures/b3a-missing-required.jsonl      30 行  B3 子族 A：缺必需字段
//   fixtures/b3b-ambiguous-mapping.jsonl     30 行  B3 子族 B：歧义映射 / 未裁定取值
//   fixtures/expected/<group>.rows.tsv       逐行期望（裁决口径，跑前冻结）
//   fixtures/expected/ground-truth-metrics.json  按画像映射独立推算的对账基准
//
// 设计约束（见 ../README.md 与 ../EXPECTED.md）：
//  * 业务日固定 2026-09-20（与其它泳道的 2026-09-01 数据面隔离）；
//  * 故意复用源 A（mock-mall）的相同用户号/商品号/订单号：1..3 / 1..4 / 1001..1002 / P-1001..P-1002；
//  * 固定种子 20260912：本脚本不含任何 Date.now()/随机源，PRNG 为 mulberry32 自实现；
//  * 本文件只做"造数据 + 写期望"，不读取任何平台运行结果。
// ============================================================================
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES = path.resolve(HERE, '..');
const EXPECTED = path.join(FIXTURES, 'expected');

export const SEED = 20260912;
const BUSINESS_DATE = '2026-09-20';
const TZ = '+08:00';
const SOURCE_CODE = 'fixture-b';

// ------------------------------------------------------------------ 确定性 PRNG
function mulberry32(a) {
  return function () {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
let rnd = mulberry32(SEED);
const pick = (arr) => arr[Math.floor(rnd() * arr.length) % arr.length];
const int = (lo, hi) => lo + Math.floor(rnd() * (hi - lo + 1));

// ------------------------------------------------------------------ 时间工具
const pad = (n, w = 2) => String(n).padStart(w, '0');
/** 业务日内第 secOfDay 秒的 ISO_OFFSET_DATE_TIME（+08:00），确定性 */
function isoAt(secOfDay) {
  const h = Math.floor(secOfDay / 3600), m = Math.floor((secOfDay % 3600) / 60), s = secOfDay % 60;
  return `${BUSINESS_DATE}T${pad(h)}:${pad(m)}:${pad(s)}${TZ}`;
}
/** 同上的 epoch 毫秒（= UTC 时刻），确定性，不依赖本机时区 */
function epochMsAt(secOfDay) {
  return Date.UTC(2026, 8, 20, 0, 0, 0) + (secOfDay - 8 * 3600) * 1000;
}

// ------------------------------------------------- 源 A（mock-mall）既有原始 id 复用
// 取自 landing/events/golden-r619-clean-20260910.jsonl（源 A 的真实落地样本），
// 目的：两源使用**相同**的用户号/商品号/订单号，用于验证"不串源"。
const A_USER_IDS = ['1', '2', '3'];
const A_PRODUCT_IDS = ['1', '2', '3', '4'];
const A_ORDER_IDS = ['1001', '1002', '1003', '1004', '1005', '1006', '1007', '1008', '1009', '1010'];
const A_PAYMENT_IDS = A_ORDER_IDS.map((o) => `P-${o}`);
const A_SESSION_IDS = ['s-1', 's-2', 's-3'];

// ------------------------------------------------------------------ 行构造
const rows = { b1a: [], b1b: [], b2: [], b3a: [], b3b: [] };
const expectations = { b1a: [], b1b: [], b2: [], b3a: [], b3b: [] };

function exp(store, rowId, family, outcome, reasonClass, note) {
  store.push({ rowId, family, outcome, reasonClass, note });
}

// ============================== B1 子族 A：信封 + payload 全异构 ==============
// 源 fixture-b 的原生形态：信封键名/时间形态/版本号、payload 字段名/类型/枚举
// 全部与 mock-mall 不同；但七个信封事实与各事件必需的**信息**一个不缺。
const B1A_KINDS = [
  'user_signup', 'item_new', 'item_view', 'order_new', 'order_pay', 'stock_hold',
];
/** payload 构造：返回 { body, canonicalType, canonicalPayload }（后者仅用于独立对账，不写入夹具） */
function b1aBody(kind, seq, secOfDay) {
  const uid = Number(A_USER_IDS[seq % A_USER_IDS.length]);
  const pid = Number(A_PRODUCT_IDS[seq % A_PRODUCT_IDS.length]);
  const oid = Number(A_ORDER_IDS[seq % A_ORDER_IDS.length]);
  const qty = int(1, 3);
  const unitFen = pick([9900, 12900, 19900, 25900, 39900]);
  const grossFen = unitFen * qty;
  switch (kind) {
    case 'user_signup':
      return {
        body: {
          buyer_uid: uid,
          age_bucket: pick(['UNDER_18', 'A18_24', 'A25_34', 'A35_44', 'A45_PLUS']),
          city_tier: pick(['T1', 'T2', 'T3', 'TX']),
          member_tier: pick(['TIER_NORMAL', 'TIER_SILVER', 'TIER_GOLD', 'TIER_PLATINUM']),
          signed_ms: epochMsAt(secOfDay),
        },
        canonicalType: 'user_registered',
        canonicalPayload: { user_id: String(uid), amountFen: 0, kind: 'signup' },
      };
    case 'item_new':
      return {
        body: {
          item_sku: pid,
          item_title: `异构商品-${pid}`,
          category_ref: int(1, 5),
          brand_ref: int(1, 4),
          list_price_fen: unitFen,
          cost_fen: Math.floor(unitFen * 0.6),
          sale_state: 'ON_SHELF',
        },
        canonicalType: 'product_created',
        canonicalPayload: { product_id: String(pid), amountFen: 0, kind: 'product' },
      };
    case 'item_view':
      return {
        body: {
          buyer_uid: uid,
          item_sku: pid,
          visit_id: A_SESSION_IDS[seq % A_SESSION_IDS.length],
          view_action: pick(['BROWSE', 'WISH', 'BASKET_ADD', 'BASKET_DROP', 'QUERY']),
          terminal: pick(['APP', 'WEB_PC', 'MOBILE_WEB']),
        },
        canonicalType: 'behavior',
        // 注意：view_action 的规范语义由 enumSemantics 裁定；此处对账只取 BROWSE→view
        canonicalPayload: { user_id: String(uid), product_id: String(pid), amountFen: 0, kind: 'behavior' },
      };
    case 'order_new':
      return {
        body: {
          order_ref: oid,
          buyer_uid: uid,
          order_lines: [
            { item_sku: pid, qty, unit_price_fen: unitFen, discount_fen: 0, line_amount_fen: grossFen },
          ],
          order_total_fen: grossFen,
          order_state: 'CREATED',
          placed_ms: epochMsAt(secOfDay),
        },
        canonicalType: 'order_created',
        canonicalPayload: { order_id: String(oid), amountFen: 0, kind: 'order' },
      };
    case 'order_pay':
      return {
        body: {
          order_ref: oid,
          buyer_uid: uid,
          pay_ref: `P-${oid}`,
          paid_fen: grossFen,
          settled_ms: epochMsAt(secOfDay),
        },
        canonicalType: 'order_paid',
        canonicalPayload: { order_id: String(oid), amountFen: grossFen, kind: 'paid' },
      };
    case 'stock_hold':
      return {
        body: {
          item_sku: pid,
          hold_qty: qty,
          order_ref: oid,
          held_qty: qty,
          free_qty: 10 - qty,
        },
        canonicalType: 'stock_reserved',
        canonicalPayload: { product_id: String(pid), amountFen: 0, kind: 'stock' },
      };
    default:
      throw new Error('unknown kind ' + kind);
  }
}

for (let i = 0; i < 30; i++) {
  const kind = B1A_KINDS[i % B1A_KINDS.length];
  const sec = 10 * 3600 + i * 137; // 业务日内确定性时刻
  const { body, canonicalType, canonicalPayload } = b1aBody(kind, i, sec);
  const msgId = `b1a-msg-${pad(i + 1, 4)}`;
  rows.b1a.push({
    _rowId: msgId,
    _canonicalType: canonicalType,
    _amountFen: canonicalPayload.amountFen,
    _orderId: canonicalPayload.order_id ?? null,
    _userId: canonicalPayload.user_id ?? null,
    msg_id: msgId,
    msg_kind: kind,
    occurred_ms: epochMsAt(sec),
    received_ms: epochMsAt(sec) + 800,
    origin: SOURCE_CODE,
    contract_rev: '2.0', // 源自己的契约修订号；须由画像 canonical.schemaVersion 归一到 1.0
    corr_id: `b1a-trc-${pad(i + 1, 4)}`,
    body,
  });
  exp(expectations.b1a, msgId, 'B1A-信封异构', 'ACCEPT',
    '经 fixture-b 画像 eventTypeMapping/fieldMapping 归一后入库，产出可对账指标',
    `合同必需信息齐全：id/类型/时间/来源/版本/追踪齐全，事件语义=${canonicalType}`);
}

// ============================== B1 子族 B：规范信封 + 异构 payload ============
// 隔离变量：只让 payload 的字段名/类型/枚举异构，信封保持规范。
const B1B_PLAN = [
  ['behavior', 12], ['order_created', 6], ['order_paid', 6], ['user_registered', 6],
];
let b1bSeq = 0;
for (const [canonicalType, count] of B1B_PLAN) {
  for (let k = 0; k < count; k++) {
    b1bSeq++;
    const i = b1bSeq - 1;
    const sec = 11 * 3600 + i * 173;
    const uid = A_USER_IDS[i % A_USER_IDS.length];
    const pid = A_PRODUCT_IDS[i % A_PRODUCT_IDS.length];
    const oid = A_ORDER_IDS[i % A_ORDER_IDS.length];
    const qty = int(1, 3);
    const unitFen = pick([9900, 12900, 19900, 25900, 39900]);
    const grossFen = unitFen * qty;
    const rowId = `b1b-evt-${pad(b1bSeq, 4)}`;
    const base = {
      _rowId: rowId,
      _canonicalType: canonicalType,
      event_id: rowId,
      event_type: canonicalType,
      event_time: isoAt(sec),
      ingest_time: isoAt(sec + 1),
      source_system: SOURCE_CODE,
      schema_version: '1.0',
      trace_id: `b1b-trc-${pad(b1bSeq, 4)}`,
    };
    let payload, amountFen = 0, orderId = null, userId = null;
    if (canonicalType === 'behavior') {
      payload = {
        buyer_uid: uid,               // ← user_id 的异构名
        item_sku: pid,                // ← product_id
        visit_no: A_SESSION_IDS[i % A_SESSION_IDS.length], // ← session_id
        act_kind: pick(['BROWSE', 'WISH', 'BASKET_ADD', 'BASKET_DROP', 'QUERY']), // ← behavior_type
        terminal: pick(['APP', 'WEB_PC', 'MOBILE_WEB']),   // ← channel
      };
      userId = uid;
    } else if (canonicalType === 'order_created') {
      payload = {
        contract_no: oid,             // ← order_id
        buyer_uid: uid,
        lines: [{ item_sku: pid, qty, unit_price_fen: unitFen, discount_fen: 0, line_amount_fen: grossFen }],
        order_total_fen: grossFen,    // ← total_amount（整数分，非字符串元）
        order_state: 'CREATED',
        placed_ms: epochMsAt(sec),
      };
      orderId = oid; userId = uid;
    } else if (canonicalType === 'order_paid') {
      payload = {
        contract_no: oid, buyer_uid: uid, pay_ref: `P-${oid}`,
        paid_fen: grossFen, settled_ms: epochMsAt(sec),
      };
      orderId = oid; userId = uid; amountFen = grossFen;
    } else {
      payload = {
        buyer_uid: uid, age_bucket: 'A25_34', city_tier: 'T1',
        member_tier: 'TIER_GOLD', signed_ms: epochMsAt(sec),
      };
      userId = uid;
    }
    rows.b1b.push({ ...base, _amountFen: amountFen, _orderId: orderId, _userId: userId, payload });
    exp(expectations.b1b, rowId, 'B1B-payload异构', 'ACCEPT',
      '经画像 fieldMapping/enumSemantics 归一后入库，产出可对账指标',
      `payload 字段名/类型/枚举异构；事件语义=${canonicalType}`);
  }
}

// ============================== B2：缺可选字段 ================================
// 三族（各 20 行），全部使用**规范词汇**以隔离"缺字段"这一个变量：
//  B2-1 缺「契约必需、采集闸门未校验」的字段（schema required 有、EventContractValidator 不查）
//  B2-2 字段存在但值为 null（对照：观测到空值 vs 未观测）
//  B2-3 缺「源画像声明为附加/可选」的字段（@keep 字段与业务可选字段）
const B2_OMIT_CONTRACT_REQUIRED = {
  // 契约 required 有、EventContractValidator.missingPayloadField 不查的字段
  user_registered: ['city_level', 'register_time'],
  product_created: ['brand_id', 'cost', 'status'],
  behavior: ['channel'],
  order_created: ['status', 'created_at'],
  order_paid: ['paid_at'],
};
const B2_NULLABLE = {
  user_registered: ['city_level', 'register_time'],
  product_created: ['brand_id', 'cost', 'status'],
  behavior: ['channel'],
  order_created: ['status', 'created_at'],
  order_paid: ['paid_at'],
};
const B2_KEEP_OPTIONAL = {
  // 源自称可选（画像里 @keep 或业务可选），缺失时也不该拒收，但必须有显式规则与展示
  user_registered: ['device_id', 'coupon_code'],
  product_created: ['device_id', 'coupon_code'],
  behavior: ['device_id', 'coupon_code'],
  order_created: ['device_id', 'coupon_code'],
  order_paid: ['device_id', 'coupon_code'],
};

function canonicalPayloadOf(type, i, sec) {
  const uid = A_USER_IDS[i % A_USER_IDS.length];
  const pid = A_PRODUCT_IDS[i % A_PRODUCT_IDS.length];
  const oid = A_ORDER_IDS[i % A_ORDER_IDS.length];
  const qty = int(1, 3);
  const unitFen = pick([9900, 12900, 19900, 25900, 39900]);
  const grossFen = unitFen * qty;
  const yuan = (f) => (f / 100).toFixed(2);
  switch (type) {
    case 'user_registered':
      return {
        payload: {
          user_id: uid, age_group: '25-34', city_level: 'tier1',
          member_level: 'gold', register_time: isoAt(sec),
        },
        amountFen: 0, orderId: null, userId: uid,
      };
    case 'product_created':
      return {
        payload: {
          product_id: pid, product_name: `标准商品-${pid}`, category_id: '3', brand_id: '2',
          price: yuan(unitFen), cost: yuan(Math.floor(unitFen * 0.6)), status: 'on_sale',
        },
        amountFen: 0, orderId: null, userId: null,
      };
    case 'behavior':
      return {
        payload: {
          user_id: uid, product_id: pid, session_id: A_SESSION_IDS[i % A_SESSION_IDS.length],
          behavior_type: pick(['view', 'view', 'view', 'favorite', 'cart_add', 'search']),
          channel: pick(['app', 'pc', 'h5']),
        },
        amountFen: 0, orderId: null, userId: uid,
      };
    case 'order_created':
      return {
        payload: {
          order_id: oid, user_id: uid,
          items: [{ product_id: pid, quantity: String(qty), unit_price: yuan(unitFen), discount: '0.00', amount: yuan(grossFen) }],
          total_amount: yuan(grossFen), status: 'CREATED', created_at: isoAt(sec),
        },
        amountFen: 0, orderId: oid, userId: uid,
      };
    case 'order_paid':
      return {
        payload: {
          order_id: oid, user_id: uid, payment_id: `P-${oid}`,
          amount: yuan(grossFen), paid_at: isoAt(sec),
        },
        amountFen: grossFen, orderId: oid, userId: uid,
      };
    default:
      throw new Error(type);
  }
}

const B2_TYPES = ['behavior', 'order_created', 'order_paid', 'user_registered', 'product_created'];
let b2Seq = 0;
for (let family = 1; family <= 3; family++) {
  for (let k = 0; k < 20; k++) {
    b2Seq++;
    const i = b2Seq - 1;
    const type = B2_TYPES[i % B2_TYPES.length];
    const sec = 13 * 3600 + i * 61;
    const rowId = `b2-${family}-evt-${pad(b2Seq, 4)}`;
    const { payload, amountFen, orderId, userId } = canonicalPayloadOf(type, i, sec);
    let familyName, note;
    if (family === 1) {
      familyName = 'B2-1-缺契约必需(闸门未查)';
      for (const f of B2_OMIT_CONTRACT_REQUIRED[type]) delete payload[f];
      note = `${type} 缺 [${B2_OMIT_CONTRACT_REQUIRED[type].join(',')}]：canonical-event.v1.schema.json required 有、EventContractValidator.missingPayloadField 不查`;
    } else if (family === 2) {
      familyName = 'B2-2-字段为null';
      for (const f of B2_NULLABLE[type]) payload[f] = null;
      note = `${type} 的 [${B2_NULLABLE[type].join(',')}] 显式置 null（观测到空值，区别于"未观测"）`;
    } else {
      familyName = 'B2-3-缺源声明可选字段';
      for (const f of B2_KEEP_OPTIONAL[type]) delete payload[f];
      note = `${type} 缺 [${B2_KEEP_OPTIONAL[type].join(',')}]：画像 fieldMapping 未列出（@keep/附加字段），缺失不应影响主线指标`;
    }
    rows.b2.push({
      _rowId: rowId, _canonicalType: type, _amountFen: amountFen, _orderId: orderId, _userId: userId,
      event_id: rowId, event_type: type, event_time: isoAt(sec), ingest_time: isoAt(sec + 1),
      source_system: SOURCE_CODE, schema_version: '1.0', trace_id: `b2-trc-${pad(b2Seq, 4)}`,
      payload,
    });
    exp(expectations.b2, rowId, familyName, 'ACCEPT',
      '缺可选字段必须由**显式规则**处理，并在采集/质量结果中**展示能力限制**；不得静默补造值',
      note);
  }
}

// ============================== B3 子族 A：缺必需字段 ========================
// B3A-1 缺「闸门必需」payload 字段（应被隔离，原因可机械归因）
// B3A-2 缺「契约必需但闸门未查」字段（按裁决同样应明确拒绝或隔离）
// B3A-3 缺信封必需字段（应被隔离）
const B3A1 = [
  ['order_paid', 'payment_id'], ['order_paid', 'amount'], ['behavior', 'session_id'],
  ['behavior', 'behavior_type'], ['product_created', 'price'], ['product_created', 'category_id'],
  ['order_created', 'total_amount'], ['order_created', 'items'], ['user_registered', 'member_level'],
  ['user_registered', 'age_group'], ['order_cancelled', 'reason'], ['refund_created', 'refund_id'],
];
const B3A2 = [
  ['order_paid', 'paid_at'], ['behavior', 'channel'], ['product_created', 'brand_id'],
  ['product_created', 'status'], ['user_registered', 'city_level'], ['user_registered', 'register_time'],
  ['order_created', 'status'], ['order_created', 'created_at'], ['refund_completed', 'completed_at'],
  ['stock_reserved', 'available_qty'],
];
const B3A3 = [
  ['event_id', 'event_id'], ['event_id', 'trace_id'], ['event_id', 'source_system'],
  ['event_id', 'schema_version'], ['event_id', 'event_type'], ['event_id', 'ingest_time'],
  ['event_id', 'event_time'], ['payload', 'payload'],
];

function baseEnvelope(seq, sec, type) {
  return {
    event_id: `b3a-evt-${pad(seq, 4)}`, event_type: type, event_time: isoAt(sec),
    ingest_time: isoAt(sec + 1), source_system: SOURCE_CODE, schema_version: '1.0',
    trace_id: `b3a-trc-${pad(seq, 4)}`,
  };
}
function payloadFor(type, i, sec) {
  const uid = A_USER_IDS[i % A_USER_IDS.length];
  const pid = A_PRODUCT_IDS[i % A_PRODUCT_IDS.length];
  const oid = A_ORDER_IDS[i % A_ORDER_IDS.length];
  const yuan = (f) => (f / 100).toFixed(2);
  switch (type) {
    case 'order_paid':
      return { order_id: oid, user_id: uid, payment_id: `P-${oid}`, amount: yuan(19900), paid_at: isoAt(sec) };
    case 'behavior':
      return { user_id: uid, product_id: pid, session_id: A_SESSION_IDS[i % 3], behavior_type: 'view', channel: 'app' };
    case 'product_created':
      return { product_id: pid, product_name: `标准商品-${pid}`, category_id: '3', brand_id: '2', price: yuan(12900), cost: yuan(8000), status: 'on_sale' };
    case 'order_created':
      return {
        order_id: oid, user_id: uid,
        items: [{ product_id: pid, quantity: '1', unit_price: yuan(12900), discount: '0.00', amount: yuan(12900) }],
        total_amount: yuan(12900), status: 'CREATED', created_at: isoAt(sec),
      };
    case 'user_registered':
      return { user_id: uid, age_group: '25-34', city_level: 'tier1', member_level: 'gold', register_time: isoAt(sec) };
    case 'order_cancelled':
      return { order_id: oid, user_id: uid, reason: 'user_cancel', cancelled_at: isoAt(sec) };
    case 'refund_created':
      return { refund_id: `R-${oid}`, order_id: oid, user_id: uid, amount: yuan(9900), reason: 'size', created_at: isoAt(sec) };
    case 'refund_completed':
      return { refund_id: `R-${oid}`, order_id: oid, user_id: uid, amount: yuan(9900), completed_at: isoAt(sec) };
    case 'stock_reserved':
      return { product_id: pid, quantity: '2', order_id: oid, reserved_qty: '2', available_qty: '8' };
    default:
      throw new Error(type);
  }
}

let b3aSeq = 0;
B3A1.forEach(([type, field], k) => {
  b3aSeq++;
  const sec = 15 * 3600 + b3aSeq * 47;
  const env = baseEnvelope(b3aSeq, sec, type);
  const payload = payloadFor(type, k, sec);
  delete payload[field];
  rows.b3a.push({
    _rowId: env.event_id, _canonicalType: type, _amountFen: 0, _orderId: payload.order_id ?? null,
    _userId: payload.user_id ?? null, ...env, payload,
  });
  exp(expectations.b3a, env.event_id, 'B3A-1-缺闸门必需字段', 'QUARANTINE',
    `payload 缺失必要字段: ${field}`, `${type} 缺闸门必查字段 ${field}`);
});
B3A2.forEach(([type, field], k) => {
  b3aSeq++;
  const sec = 15 * 3600 + b3aSeq * 47;
  const env = baseEnvelope(b3aSeq, sec, type);
  const payload = payloadFor(type, k, sec);
  delete payload[field];
  rows.b3a.push({
    _rowId: env.event_id, _canonicalType: type, _amountFen: 0, _orderId: payload.order_id ?? null,
    _userId: payload.user_id ?? null, ...env, payload,
  });
  exp(expectations.b3a, env.event_id, 'B3A-2-缺契约必需(闸门未查)', 'QUARANTINE',
    `拒绝或隔离并指明缺失必需字段 ${field}（不得静默按可选处理）`,
    `${type} 缺 canonical-event.v1.schema.json required 字段 ${field}`);
});
B3A3.forEach(([kind, field]) => {
  b3aSeq++;
  const sec = 15 * 3600 + b3aSeq * 47;
  const type = 'behavior';
  const env = baseEnvelope(b3aSeq, sec, type);
  const payload = payloadFor(type, 0, sec);
  let rowId = env.event_id;
  if (kind === 'event_id') {
    delete env[field];
    if (field === 'event_id') rowId = `b3a-missing-envelope-${pad(b3aSeq, 4)}`;
  } else {
    delete env.payload;
    delete env[field];
  }
  rows.b3a.push({
    _rowId: rowId, _canonicalType: type, _amountFen: 0, _orderId: null, _userId: null,
    ...env, ...(kind === 'payload' ? {} : { payload }),
  });
  exp(expectations.b3a, rowId, 'B3A-3-缺信封必需字段', 'QUARANTINE',
    kind === 'payload' ? 'payload 缺失或非对象' : `缺失必要字段: ${field}`,
    kind === 'payload' ? '信封无 payload' : `信封缺 ${field}`);
});

// ============================== B3 子族 B：歧义映射 / 未裁定取值 ==============
// B3B-A 字段映射歧义：同一规范字段可由两个源字段承接（pay_money / settle_amount），
//       扁平 fieldMapping 无法唯一确定 ⇒ 必须显式拒绝或隔离，不得任选其一。
// B3B-B 未裁定枚举：画像 enumSemantics 中为 null（观测到但未裁定）的行为取值。
// B3B-C 类型歧义：金额既可能是字符串元、也可能是数字（源内两种形态并存）。
// B3B-D 时间歧义：格式同时匹配 dd/MM/yyyy 与 MM/dd/yyyy 两种候选格式。
let b3bSeq = 0;
function b3bRow(type, sec, payload, extraMeta = {}) {
  b3bSeq++;
  const rowId = `b3b-evt-${pad(b3bSeq, 4)}`;
  rows.b3b.push({
    _rowId: rowId, _canonicalType: type, _amountFen: extraMeta.amountFen ?? 0,
    _orderId: extraMeta.orderId ?? null, _userId: extraMeta.userId ?? null,
    event_id: rowId, event_type: type, event_time: extraMeta.eventTime ?? isoAt(sec),
    ingest_time: isoAt(sec + 1), source_system: SOURCE_CODE, schema_version: '1.0',
    trace_id: `b3b-trc-${pad(b3bSeq, 4)}`, payload,
  });
  return rowId;
}
// B3B-A1（4 行）：order_paid 行同时带 pay_money 与 settle_amount，且**无** amount
for (let k = 0; k < 4; k++) {
  const sec = 17 * 3600 + k * 41;
  const oid = A_ORDER_IDS[k % A_ORDER_IDS.length];
  const uid = A_USER_IDS[k % A_USER_IDS.length];
  const fen = pick([9900, 12900, 19900, 25900]);
  const rowId = b3bRow('order_paid', sec, {
    order_id: oid, user_id: uid, payment_id: `P-${oid}`,
    pay_money: (fen / 100).toFixed(2),
    settle_amount: ((fen + 100) / 100).toFixed(2), // 与 pay_money 不等：任选其一都会算错
    paid_at: isoAt(sec),
  }, { orderId: oid, userId: uid });
  exp(expectations.b3b, rowId, 'B3B-A1-字段映射歧义(无规范字段)', 'QUARANTINE',
    '拒绝或隔离并指明"字段映射歧义"（pay_money 与 settle_amount 都可归一为 amount 且取值不等）',
    '扁平 fieldMapping 无法唯一确定 amount 来源 ⇒ 不得任选其一');
}
// B3B-A2（4 行）：规范 amount 存在，但同一行还带两个取值不等的候选源字段
for (let k = 0; k < 4; k++) {
  const sec = 17 * 3600 + 300 + k * 43;
  const oid = A_ORDER_IDS[(k + 4) % A_ORDER_IDS.length];
  const uid = A_USER_IDS[(k + 1) % A_USER_IDS.length];
  const fen = pick([9900, 12900, 19900, 25900]);
  const rowId = b3bRow('order_paid', sec, {
    order_id: oid, user_id: uid, payment_id: `P-${oid}`,
    amount: (fen / 100).toFixed(2),
    pay_money: ((fen + 200) / 100).toFixed(2),   // 与 amount 不等
    paid_at: isoAt(sec),
  }, { orderId: oid, userId: uid, amountFen: fen });
  exp(expectations.b3b, rowId, 'B3B-A2-字段映射歧义(有规范字段)', 'QUARANTINE',
    '拒绝或隔离并指明歧义（amount 与 pay_money 取值不等，谁是权威未裁定）',
    '存在未裁定权威来源 ⇒ 不得静默采信 amount 而丢弃 pay_money');
}
// B3B-B（8 行）：未裁定枚举取值
const UNADJUDICATED = ['purchase', 'add_cart', 'pay_later', 'cancel'];
for (let k = 0; k < 8; k++) {
  const sec = 17 * 3600 + 600 + k * 53;
  const bt = UNADJUDICATED[k % UNADJUDICATED.length];
  const rowId = b3bRow('behavior', sec, {
    user_id: A_USER_IDS[k % 3], product_id: A_PRODUCT_IDS[k % 4],
    session_id: A_SESSION_IDS[k % 3], behavior_type: bt, channel: 'app',
  }, { userId: A_USER_IDS[k % 3] });
  exp(expectations.b3b, rowId, 'B3B-B-未裁定枚举', 'QUARANTINE',
    `拒绝或隔离并指明未裁定取值（画像 enumSemantics.behavior["${bt}"] = null）`,
    `D-140 §3：观测到但未裁定的取值（"${bt}"）不得猜语义`);
}
// B3B-C（7 行）：金额类型歧义（字符串 vs 数字）
for (let k = 0; k < 7; k++) {
  const sec = 18 * 3600 + k * 37;
  const oid = A_ORDER_IDS[k % A_ORDER_IDS.length];
  const uid = A_USER_IDS[k % A_USER_IDS.length];
  const rowId = b3bRow('order_paid', sec, {
    order_id: oid, user_id: uid, payment_id: `P-${oid}`,
    amount: 12900 + k, // 数字（分？元？）—— 类型与单位双重歧义
    paid_at: isoAt(sec),
  }, { orderId: oid, userId: uid, amountFen: 0 });
  exp(expectations.b3b, rowId, 'B3B-C-金额类型歧义', 'QUARANTINE',
    '拒绝或隔离并指明金额类型/单位歧义（数字无法判定是"分"还是"元"）',
    '画像 timePolicy/金额形态未声明，闸门 findBadAmount 对 isNumber() 直接放行');
}
// B3B-D（7 行）：时间格式歧义（日/月都 ≤12 ⇒ 同时匹配 dd/MM/yyyy 与 MM/dd/yyyy）
const AMBIGUOUS_TIMES = [
  '05/03/2026 14:00:00', '07/04/2026 09:30:00', '11/02/2026 20:15:00',
  '03/05/2026 08:45:00', '09/06/2026 12:20:00', '02/07/2026 19:05:00',
  '10/08/2026 07:40:00',
];
for (let k = 0; k < 7; k++) {
  const sec = 18 * 3600 + 400 + k * 43;
  const rowId = b3bRow('behavior', sec, {
    user_id: A_USER_IDS[k % 3], product_id: A_PRODUCT_IDS[k % 4],
    session_id: A_SESSION_IDS[k % 3], behavior_type: 'view', channel: 'app',
  }, { userId: A_USER_IDS[k % 3], eventTime: AMBIGUOUS_TIMES[k] });
  exp(expectations.b3b, rowId, 'B3B-D-时间格式歧义', 'QUARANTINE',
    '拒绝或隔离并指明时间格式歧义（n/d/yyyy 同时匹配 dd/MM/yyyy 与 MM/dd/yyyy）',
    `event_time=${AMBIGUOUS_TIMES[k]}；画像 timePolicy.formats 同时列出两种候选格式 ⇒ 不得猜；闸门对 event_time 完全不校验`);
}

// ------------------------------------------------------------------ 写出文件
fs.mkdirSync(EXPECTED, { recursive: true });
const files = {};
function writeJsonl(name, list) {
  const body = list.map((r) => {
    const clean = Object.fromEntries(Object.entries(r).filter(([k]) => !k.startsWith('_')));
    return JSON.stringify(clean);
  }).join('\n') + '\n';
  const p = path.join(FIXTURES, name);
  fs.writeFileSync(p, body, 'utf8');
  files[name] = {
    bytes: Buffer.byteLength(body, 'utf8'), rows: list.length,
    sha256: crypto.createHash('sha256').update(body, 'utf8').digest('hex').toUpperCase(),
  };
}
writeJsonl('b1a-envelope-vocab.jsonl', rows.b1a);
writeJsonl('b1b-payload-vocab.jsonl', rows.b1b);
writeJsonl('b2-missing-optional.jsonl', rows.b2);
writeJsonl('b3a-missing-required.jsonl', rows.b3a);
writeJsonl('b3b-ambiguous-mapping.jsonl', rows.b3b);

// 逐行期望（跑前冻结；与任何运行结果无关）
function writeExpected(name, list) {
  const lines = ['row_id\tfamily\texpected_outcome\texpected_reason_class\tnote'];
  for (const e of list) {
    lines.push([e.rowId, e.family, e.outcome, e.reasonClass, (e.note ?? '').replace(/\t/g, ' ')].join('\t'));
  }
  fs.writeFileSync(path.join(EXPECTED, name), lines.join('\n') + '\n', 'utf8');
}
writeExpected('B1-expected-outcomes.tsv', [...expectations.b1a, ...expectations.b1b]);
writeExpected('B2-expected-outcomes.tsv', expectations.b2);
writeExpected('B3-expected-outcomes.tsv', [...expectations.b3a, ...expectations.b3b]);

// 独立对账基准：只依据 fixture 原始行 + 画像声明的映射推算（不读平台任何输出）
const b1BusinessDate = BUSINESS_DATE;
const allB1 = [...rows.b1a, ...rows.b1b];
const inB1Day = allB1.filter((r) => r.event_time === undefined || String(r.event_time).startsWith(b1BusinessDate));
const pv = rows.b1b.filter((r) => r._canonicalType === 'behavior').length
  + rows.b1a.filter((r) => r._canonicalType === 'behavior' && r.body.view_action === 'BROWSE').length;
const uvUsers = new Set();
for (const r of inB1Day) if (r._userId) uvUsers.add(r._userId);
const paidB1 = allB1.filter((r) => r._canonicalType === 'order_paid');
const orderCount = paidB1.length;
const saleAmountFen = paidB1.reduce((a, r) => a + r._amountFen, 0);
const deliveredA = allB1.filter((r) => r._canonicalType === 'order_created').length;
const groundTruth = {
  _note: '依据 fixtures/b1a-*.jsonl + fixtures/b1b-*.jsonl 与 mapping/fixture-b.v1.json 声明的映射独立推算；'
    + '平台侧产出必须与此一致才叫"可对账"。金额单位：分（fen）。',
  sourceCode: SOURCE_CODE,
  businessDate: BUSINESS_DATE,
  rows: { b1a: rows.b1a.length, b1b: rows.b1b.length, total: allB1.length },
  metric: {
    pv, uv: uvUsers.size, order_count: orderCount, sale_amount_fen: saleAmountFen,
    sale_amount_yuan: (saleAmountFen / 100).toFixed(2), orders_created: deliveredA,
  },
  per_user_ids_reused_from_source_a: A_USER_IDS,
  per_product_ids_reused_from_source_a: A_PRODUCT_IDS,
  per_order_ids_reused_from_source_a: A_ORDER_IDS.slice(0, 6),
  per_payment_ids_reused_from_source_a: A_PAYMENT_IDS.slice(0, 6),
};
fs.writeFileSync(path.join(EXPECTED, 'ground-truth-metrics.json'),
  JSON.stringify(groundTruth, null, 2) + '\n', 'utf8');

const manifest = {
  generator: 'fixtures/tools/gen-fixtures.mjs',
  seed: SEED,
  businessDate: BUSINESS_DATE,
  sourceCode: SOURCE_CODE,
  files, groundTruth,
};
fs.writeFileSync(path.join(EXPECTED, 'fixture-manifest.json'),
  JSON.stringify(manifest, null, 2) + '\n', 'utf8');

console.log(JSON.stringify({ files, groundTruth: groundTruth.metric }, null, 2));
