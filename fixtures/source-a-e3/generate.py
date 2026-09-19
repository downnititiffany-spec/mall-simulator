# -*- coding: utf-8 -*-
"""BATCH-G3103 E3/E4 fixture generator (source A mock-mall, canonical-event.v1).

Hand-authored deterministic fixtures — the "生成器的文件夹具" path allowed by
guidance §7.1 (same construction style as the stage7q1 golden file; no live
mock-mall endpoints are claimed). Emits exactly 99 (E3) + 71 (E4) events with
all amounts as contract strings, IDs fresh (g3*/g4* prefixes) so the per-source
checkpoint has never seen them.

E3: cross-day file (2026-09-17 + 2026-09-18); one pipeline run at
    businessTime 2026-09-18 -> snapshot A. 2026-09-17 rows prove cross-day
    ODS/DWD loading while ADS stays single-dt.
E4: all events dt 2026-09-21 (future business day, pinned to the 2026-09-20
    execution window — regenerate with a later dt if the run slips past
    midnight); pipeline run at businessTime 2026-09-21 -> snapshot B.
"""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = "mock-mall"


class Ev:
    def __init__(self, seq, etype, ts, payload, trace):
        self.event_id = f"g3e{seq:04d}"
        self.event_type = etype
        self.event_time = ts
        self.ingest_time = ts[:11] + f"{int(ts[11:13]) + 0:02d}" + ts[13:]  # same clock, valid
        self.payload = payload
        self.trace_id = trace


def line(seq, etype, ts, payload):
    return {
        "event_id": f"g3e{seq:04d}", "event_type": etype, "event_time": ts,
        "ingest_time": ts, "source_system": SRC, "schema_version": "1.0",
        "trace_id": f"trace-g3103-{seq:04d}", "payload": payload,
    }


def items(one):
    """order_created items is a JSON-encoded STRING (frozen contract)."""
    pid, qty, price = one
    amount = qty * price
    return json.dumps([{"product_id": pid, "quantity": qty,
                        "unit_price": f"{price:.2f}", "discount": "0.00",
                        "amount": f"{amount:.2f}"}], ensure_ascii=False)


T17, T18, T21 = "2026-09-17", "2026-09-18", "2026-09-21"
USER_META = [
    ("g3u01", "25-34", "tier1", "gold"), ("g3u02", "25-34", "tier2", "silver"),
    ("g3u03", "18-24", "tier1", "none"), ("g3u04", "35-44", "tier3", "gold"),
    ("g3u05", "25-34", "tier2", "none"), ("g3u06", "18-24", "tier1", "silver"),
    ("g3u07", "35-44", "tier2", "none"), ("g3u08", "25-34", "tier3", "gold"),
]
PRODUCTS = [
    ("g3p01", "G3-商品一", "100", "b1", "100.00", "60.00"),
    ("g3p02", "G3-商品二", "100", "b1", "90.00", "55.00"),
    ("g3p03", "G3-商品三", "200", "b2", "80.00", "50.00"),
    ("g3p04", "G3-商品四", "200", "b2", "60.00", "35.00"),
    ("g3p05", "G3-商品五", "300", "b3", "50.00", "30.00"),
]


def build_e3():
    out, n = [], 0

    def add(etype, ts, payload):
        nonlocal n
        n += 1
        out.append(line(n, etype, ts, payload))

    # --- 2026-09-17: registrations, products, 2 views, 1 paid + 1 cancelled order
    for i, (uid, age, city, member) in enumerate(USER_META):
        add("user_registered", f"{T17}T09:0{i}:00+08:00",
            {"user_id": uid, "age_group": age, "city_level": city,
             "member_level": member, "register_time": f"{T17}T09:0{i}:00+08:00"})
    for pid, name, cat, brand, price, cost in PRODUCTS:
        add("product_created", f"{T17}T08:00:00+08:00",
            {"product_id": pid, "product_name": name, "category_id": cat,
             "brand_id": brand, "price": price, "cost": cost, "status": "on_sale"})
    add("behavior", f"{T17}T10:00:00+08:00",
        {"user_id": "g3u01", "product_id": "g3p01", "session_id": "s-17-1",
         "behavior_type": "view", "channel": "app"})
    add("behavior", f"{T17}T10:05:00+08:00",
        {"user_id": "g3u02", "product_id": "g3p02", "session_id": "s-17-2",
         "behavior_type": "view", "channel": "web"})
    add("order_created", f"{T17}T11:00:00+08:00",
        {"order_id": "g3o0001", "user_id": "g3u01", "total_amount": "100.00",
         "items": items(("g3p01", 1, 100.0))})
    add("order_paid", f"{T17}T11:05:00+08:00",
        {"order_id": "g3o0001", "user_id": "g3u01", "payment_id": "g3pay01",
         "amount": "100.00"})
    add("order_created", f"{T17}T11:10:00+08:00",
        {"order_id": "g3o0002", "user_id": "g3u02", "total_amount": "90.00",
         "items": items(("g3p02", 1, 90.0))})
    add("order_cancelled", f"{T17}T11:30:00+08:00",
        {"order_id": "g3o0002", "user_id": "g3u02", "reason": "user_cancel"})

    # --- 2026-09-18: dim refresh + behavior 45 + trade (12 paid / 2 cancelled / 1 full refund)
    for pid, name, cat, brand, price, cost in PRODUCTS:
        add("product_updated", f"{T18}T08:00:00+08:00",
            {"product_id": pid, "product_name": name, "category_id": cat,
             "brand_id": brand, "price": price, "cost": cost, "status": "on_sale"})

    views = []  # (user, product) x30; per-product pv 12/6/6/4/2, 10 distinct viewers
    for u in ("g3u01", "g3u02", "g3u03", "g3u04", "g3u05"):
        views += [(u, "g3p01")] * 0
    views += [("g3u01", "g3p01")] * 3 + [("g3u02", "g3p01")] * 3 + \
             [("g3u03", "g3p01")] * 2 + [("g3u04", "g3p01")] * 2 + [("g3u05", "g3p01")] * 2
    views += [("g3u06", "g3p02")] * 2 + [("g3u07", "g3p02")] * 2 + [("g3u08", "g3p02")] * 2
    views += [("g3u09", "g3p03")] * 3 + [("g3u10", "g3p03")] * 3
    views += [("g3u01", "g3p04"), ("g3u02", "g3p04"), ("g3u05", "g3p04"), ("g3u06", "g3p04")]
    views += [("g3u07", "g3p05"), ("g3u08", "g3p05")]
    assert len(views) == 30
    minute = 0
    for user, pid in views:
        add("behavior", f"{T18}T09:{minute // 60:02d}:{minute % 60:02d}+08:00",
            {"user_id": user, "product_id": pid, "session_id": f"s-18-{user}",
             "behavior_type": "view", "channel": "app"})
        minute += 2
    for i, (user, pid) in enumerate([("g3u01", "g3p01"), ("g3u02", "g3p01"),
                                     ("g3u03", "g3p02"), ("g3u04", "g3p02"),
                                     ("g3u05", "g3p03"), ("g3u06", "g3p03")]):
        add("behavior", f"{T18}T10:3{i}:00+08:00",
            {"user_id": user, "product_id": pid, "session_id": f"s-18-{user}",
             "behavior_type": "favorite", "channel": "app"})
    for i, (user, pid) in enumerate([("g3u01", "g3p01"), ("g3u02", "g3p01"),
                                     ("g3u03", "g3p01"), ("g3u04", "g3p02"),
                                     ("g3u05", "g3p02"), ("g3u01", "g3p03"),
                                     ("g3u06", "g3p03"), ("g3u02", "g3p04")]):
        add("behavior", f"{T18}T11:{i:02d}:00+08:00",
            {"user_id": user, "product_id": pid, "session_id": f"s-18-{user}",
             "behavior_type": "cart_add", "channel": "app"})
    add("behavior", f"{T18}T11:20:00+08:00",
        {"user_id": "g3u05", "product_id": "g3p02", "session_id": "s-18-g3u05",
         "behavior_type": "cart_remove", "channel": "app"})

    paid = [  # (order, user, product, price); gmv 1010
        ("g3o0101", "g3u01", "g3p01", 100.0), ("g3o0102", "g3u01", "g3p01", 100.0),
        ("g3o0103", "g3u01", "g3p01", 100.0), ("g3o0104", "g3u01", "g3p01", 100.0),
        ("g3o0105", "g3u02", "g3p02", 90.0), ("g3o0106", "g3u02", "g3p02", 90.0),
        ("g3o0107", "g3u02", "g3p02", 90.0), ("g3o0108", "g3u03", "g3p03", 80.0),
        ("g3o0109", "g3u03", "g3p03", 80.0), ("g3o0110", "g3u04", "g3p03", 70.0),
        ("g3o0111", "g3u05", "g3p04", 60.0), ("g3o0112", "g3u06", "g3p05", 50.0),
    ]
    for i, (oid, uid, pid, price) in enumerate(paid):
        add("order_created", f"{T18}T12:{i:02d}:00+08:00",
            {"order_id": oid, "user_id": uid, "total_amount": f"{price:.2f}",
             "items": items((pid, 1, price))})
        add("order_paid", f"{T18}T12:{i:02d}:30+08:00",
            {"order_id": oid, "user_id": uid, "payment_id": f"pay-{oid}",
             "amount": f"{price:.2f}"})
    for i, (oid, uid, pid, price, cancel_ts) in enumerate([
            ("g3o0113", "g3u07", "g3p02", 90.0, "12:30"),
            ("g3o0114", "g3u08", "g3p04", 60.0, "12:32")]):
        add("order_created", f"{T18}T12:1{i}:00+08:00",
            {"order_id": oid, "user_id": uid, "total_amount": f"{price:.2f}",
             "items": items((pid, 1, price))})
        add("order_cancelled", f"{T18}T{cancel_ts}:00+08:00",
            {"order_id": oid, "user_id": uid, "reason": "user_cancel"})
    # same-day full refund on g3o0109 (F-35 boundary: refund attributed to order business day)
    add("refund_created", f"{T18}T14:00:00+08:00",
        {"refund_id": "g3r0001", "order_id": "g3o0109", "user_id": "g3u03",
         "amount": "80.00", "reason": "quality_issue"})
    add("refund_completed", f"{T18}T14:05:00+08:00",
        {"refund_id": "g3r0001", "order_id": "g3o0109", "user_id": "g3u03",
         "amount": "80.00", "reason": "quality_issue"})
    return out


def build_e4():
    out, n = [], 0

    def add(etype, ts, payload):
        nonlocal n
        n += 1
        e = line(n, etype, ts, payload)
        e["event_id"] = f"g4e{n:04d}"
        e["trace_id"] = f"trace-g3103-e4-{n:04d}"
        out.append(e)

    for i in range(7):
        uid = f"g4u{i + 1:02d}"
        add("user_registered", f"{T21}T09:0{i}:00+08:00",
            {"user_id": uid, "age_group": "25-34", "city_level": "tier1",
             "member_level": "gold", "register_time": f"{T21}T09:0{i}:00+08:00"})
    views = []  # x31: pv 12/10/9, 8 distinct viewers
    views += [("g4u01", "g3p01")] * 3 + [("g4u02", "g3p01")] * 3 + \
             [("g4u03", "g3p01")] * 3 + [("g4u04", "g3p01")] * 3
    views += [("g4u05", "g3p02")] * 3 + [("g4u06", "g3p02")] * 3 + \
             [("g4u07", "g3p02")] * 2 + [("g4u08", "g3p02")] * 2
    views += [("g4u01", "g3p03")] * 2 + [("g4u02", "g3p03")] * 2 + \
             [("g4u03", "g3p03")] * 2 + [("g4u04", "g3p03"), ("g4u05", "g3p03"),
                                          ("g4u06", "g3p03")]
    assert len(views) == 31
    minute = 0
    for user, pid in views:
        add("behavior", f"{T21}T10:{minute // 60:02d}:{minute % 60:02d}+08:00",
            {"user_id": user, "product_id": pid, "session_id": f"s-21-{user}",
             "behavior_type": "view", "channel": "app"})
        minute += 2
    add("behavior", f"{T21}T10:30:00+08:00",
        {"user_id": "g4u01", "product_id": "g3p01", "session_id": "s-21-g4u01",
         "behavior_type": "favorite", "channel": "app"})
    add("behavior", f"{T21}T10:31:00+08:00",
        {"user_id": "g4u05", "product_id": "g3p02", "session_id": "s-21-g4u05",
         "behavior_type": "favorite", "channel": "app"})
    add("behavior", f"{T21}T11:00:00+08:00",
        {"user_id": "g4u02", "product_id": "g3p01", "session_id": "s-21-g4u02",
         "behavior_type": "cart_add", "channel": "app"})

    paid = [  # (order, user, product, price); gmv 1200, buyers 7
        ("g4o0101", "g4u01", "g3p01", 100.0), ("g4o0102", "g4u01", "g3p01", 100.0),
        ("g4o0103", "g4u02", "g3p01", 100.0), ("g4o0104", "g4u03", "g3p01", 100.0),
        ("g4o0105", "g4u04", "g3p01", 100.0), ("g4o0106", "g4u02", "g3p02", 88.0),
        ("g4o0107", "g4u02", "g3p02", 88.0), ("g4o0108", "g4u05", "g3p02", 88.0),
        ("g4o0109", "g4u06", "g3p02", 88.0), ("g4o0110", "g4u01", "g3p03", 87.0),
        ("g4o0111", "g4u03", "g3p03", 87.0), ("g4o0112", "g4u04", "g3p03", 87.0),
        ("g4o0113", "g4u07", "g3p03", 87.0),
    ]
    for i, (oid, uid, pid, price) in enumerate(paid):
        add("order_created", f"{T21}T12:{i:02d}:00+08:00",
            {"order_id": oid, "user_id": uid, "total_amount": f"{price:.2f}",
             "items": items((pid, 1, price))})
        add("order_paid", f"{T21}T12:{i:02d}:30+08:00",
            {"order_id": oid, "user_id": uid, "payment_id": f"pay-{oid}",
             "amount": f"{price:.2f}"})
    for i, (rid, oid, uid, ts_c, ts_d) in enumerate([
            ("g4r0001", "g4o0101", "g4u01", "14:00", "14:05"),
            ("g4r0002", "g4o0103", "g4u02", "14:10", "14:15")]):
        add("refund_created", f"{T21}T{ts_c}:00+08:00",
            {"refund_id": rid, "order_id": oid, "user_id": uid,
             "amount": "100.00", "reason": "quality_issue"})
        add("refund_completed", f"{T21}T{ts_d}:00+08:00",
            {"refund_id": rid, "order_id": oid, "user_id": uid,
             "amount": "100.00", "reason": "quality_issue"})
    return out


def write(name, events):
    path = HERE / name
    with path.open("w", encoding="utf-8", newline="\n") as f:
        for e in events:
            f.write(json.dumps(e, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"{name}: {len(events)} events -> {path}")


if __name__ == "__main__":
    e3, e4 = build_e3(), build_e4()
    assert len(e3) == 99 and len(e4) == 71, (len(e3), len(e4))
    ids = [e["event_id"] for e in e3 + e4]
    assert len(ids) == len(set(ids)), "event_id collision"
    write("e3-events.jsonl", e3)
    write("e4-events.jsonl", e4)
