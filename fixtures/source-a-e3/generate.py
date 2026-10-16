# -*- coding: utf-8 -*-
"""BATCH-G3103 E3/E4 fixture generator (source A mock-mall, canonical-event.v1).

Hand-authored deterministic fixtures — the "生成器的文件夹具" path allowed by
guidance §7.1 (same construction style as the stage7q1 golden file; no live
mock-mall endpoints are claimed). Emits exactly 99 (E3) + 71 (E4) events with
all amounts as contract strings. Entity ids (user/product/order) are PURE NUMERIC
and fresh (930xx/931xx/94xxxx) so the per-source checkpoint has never seen them AND
they satisfy the frozen IdCodec contract ^[A-Za-z]*([0-9]+)$ (D-035: the first draft
used g3u01-style ids with mid-string digits -> REGEXP_EXTRACT no-match -> NULL keys
-> quality gate correctly blocked; only numeric ids survive toBIGINT).

E3: cross-day file (2026-09-17 + 2026-09-18); one pipeline run at
    businessTime 2026-09-18 -> snapshot A. 2026-09-17 rows prove cross-day
    ODS/DWD loading while ADS stays single-dt.
E4: all events share an explicit future business date supplied with
    --e4-date (defaults to tomorrow). Keep it later than the decision's
    completion date; event values stay deterministic for a pinned date.
"""
import argparse
import json
from datetime import date, timedelta
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


T17, T18 = "2026-09-17", "2026-09-18"
# E4 must have a business date after the decision's completion date so the
# real evaluation path can distinguish "not yet observed" from a result.
# The CLI pins that date for each reproducible fixture release.
T21 = "2026-09-21"
USER_META = [
    ("93001", "25-34", "tier1", "gold"), ("93002", "25-34", "tier2", "silver"),
    ("93003", "18-24", "tier1", "none"), ("93004", "35-44", "tier3", "gold"),
    ("93005", "25-34", "tier2", "none"), ("93006", "18-24", "tier1", "silver"),
    ("93007", "35-44", "tier2", "none"), ("93008", "25-34", "tier3", "gold"),
]
PRODUCTS = [
    ("93101", "G3-商品一", "100", "b1", "100.00", "60.00"),
    ("93102", "G3-商品二", "100", "b1", "90.00", "55.00"),
    ("93103", "G3-商品三", "200", "b2", "80.00", "50.00"),
    ("93104", "G3-商品四", "200", "b2", "60.00", "35.00"),
    ("93105", "G3-商品五", "300", "b3", "50.00", "30.00"),
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
        {"user_id": "93001", "product_id": "93101", "session_id": "s-17-1",
         "behavior_type": "view", "channel": "app"})
    add("behavior", f"{T17}T10:05:00+08:00",
        {"user_id": "93002", "product_id": "93102", "session_id": "s-17-2",
         "behavior_type": "view", "channel": "web"})
    add("order_created", f"{T17}T11:00:00+08:00",
        {"order_id": "940001", "user_id": "93001", "total_amount": "100.00",
         "items": items(("93101", 1, 100.0))})
    add("order_paid", f"{T17}T11:05:00+08:00",
        {"order_id": "940001", "user_id": "93001", "payment_id": "g3pay01",
         "amount": "100.00"})
    add("order_created", f"{T17}T11:10:00+08:00",
        {"order_id": "940002", "user_id": "93002", "total_amount": "90.00",
         "items": items(("93102", 1, 90.0))})
    add("order_cancelled", f"{T17}T11:30:00+08:00",
        {"order_id": "940002", "user_id": "93002", "reason": "user_cancel"})

    # --- 2026-09-18: dim refresh + behavior 45 + trade (12 paid / 2 cancelled / 1 full refund)
    for pid, name, cat, brand, price, cost in PRODUCTS:
        add("product_updated", f"{T18}T08:00:00+08:00",
            {"product_id": pid, "product_name": name, "category_id": cat,
             "brand_id": brand, "price": price, "cost": cost, "status": "on_sale"})

    views = []  # (user, product) x30; per-product pv 12/6/6/4/2, 10 distinct viewers
    for u in ("93001", "93002", "93003", "93004", "93005"):
        views += [(u, "93101")] * 0
    views += [("93001", "93101")] * 3 + [("93002", "93101")] * 3 + \
             [("93003", "93101")] * 2 + [("93004", "93101")] * 2 + [("93005", "93101")] * 2
    views += [("93006", "93102")] * 2 + [("93007", "93102")] * 2 + [("93008", "93102")] * 2
    views += [("93009", "93103")] * 3 + [("93010", "93103")] * 3
    views += [("93001", "93104"), ("93002", "93104"), ("93005", "93104"), ("93006", "93104")]
    views += [("93007", "93105"), ("93008", "93105")]
    assert len(views) == 30
    minute = 0
    for user, pid in views:
        add("behavior", f"{T18}T09:{minute // 60:02d}:{minute % 60:02d}+08:00",
            {"user_id": user, "product_id": pid, "session_id": f"s-18-{user}",
             "behavior_type": "view", "channel": "app"})
        minute += 2
    for i, (user, pid) in enumerate([("93001", "93101"), ("93002", "93101"),
                                     ("93003", "93102"), ("93004", "93102"),
                                     ("93005", "93103"), ("93006", "93103")]):
        add("behavior", f"{T18}T10:3{i}:00+08:00",
            {"user_id": user, "product_id": pid, "session_id": f"s-18-{user}",
             "behavior_type": "favorite", "channel": "app"})
    for i, (user, pid) in enumerate([("93001", "93101"), ("93002", "93101"),
                                     ("93003", "93101"), ("93004", "93102"),
                                     ("93005", "93102"), ("93001", "93103"),
                                     ("93006", "93103"), ("93002", "93104")]):
        add("behavior", f"{T18}T11:{i:02d}:00+08:00",
            {"user_id": user, "product_id": pid, "session_id": f"s-18-{user}",
             "behavior_type": "cart_add", "channel": "app"})
    add("behavior", f"{T18}T11:20:00+08:00",
        {"user_id": "93005", "product_id": "93102", "session_id": "s-18-93005",
         "behavior_type": "cart_remove", "channel": "app"})

    paid = [  # (order, user, product, price); gmv 1010
        ("940101", "93001", "93101", 100.0), ("940102", "93001", "93101", 100.0),
        ("940103", "93001", "93101", 100.0), ("940104", "93001", "93101", 100.0),
        ("940105", "93002", "93102", 90.0), ("940106", "93002", "93102", 90.0),
        ("940107", "93002", "93102", 90.0), ("940108", "93003", "93103", 80.0),
        ("940109", "93003", "93103", 80.0), ("940110", "93004", "93103", 70.0),
        ("940111", "93005", "93104", 60.0), ("940112", "93006", "93105", 50.0),
    ]
    for i, (oid, uid, pid, price) in enumerate(paid):
        add("order_created", f"{T18}T12:{i:02d}:00+08:00",
            {"order_id": oid, "user_id": uid, "total_amount": f"{price:.2f}",
             "items": items((pid, 1, price))})
        add("order_paid", f"{T18}T12:{i:02d}:30+08:00",
            {"order_id": oid, "user_id": uid, "payment_id": f"pay-{oid}",
             "amount": f"{price:.2f}"})
    for i, (oid, uid, pid, price, cancel_ts) in enumerate([
            ("940113", "93007", "93102", 90.0, "12:30"),
            ("940114", "93008", "93104", 60.0, "12:32")]):
        add("order_created", f"{T18}T12:1{i}:00+08:00",
            {"order_id": oid, "user_id": uid, "total_amount": f"{price:.2f}",
             "items": items((pid, 1, price))})
        add("order_cancelled", f"{T18}T{cancel_ts}:00+08:00",
            {"order_id": oid, "user_id": uid, "reason": "user_cancel"})
    # same-day full refund on 940109 (F-35 boundary: refund attributed to order business day)
    add("refund_created", f"{T18}T14:00:00+08:00",
        {"refund_id": "g3r0001", "order_id": "940109", "user_id": "93003",
         "amount": "80.00", "reason": "quality_issue"})
    add("refund_completed", f"{T18}T14:05:00+08:00",
        {"refund_id": "g3r0001", "order_id": "940109", "user_id": "93003",
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
        uid = str(93020 + i + 1)
        add("user_registered", f"{T21}T09:0{i}:00+08:00",
            {"user_id": uid, "age_group": "25-34", "city_level": "tier1",
             "member_level": "gold", "register_time": f"{T21}T09:0{i}:00+08:00"})
    views = []  # x31: pv 12/10/9, 8 distinct viewers
    views += [("93021", "93101")] * 3 + [("93022", "93101")] * 3 + \
             [("93023", "93101")] * 3 + [("93024", "93101")] * 3
    views += [("93025", "93102")] * 3 + [("93026", "93102")] * 3 + \
             [("93027", "93102")] * 2 + [("93028", "93102")] * 2
    views += [("93021", "93103")] * 2 + [("93022", "93103")] * 2 + \
             [("93023", "93103")] * 2 + [("93024", "93103"), ("93025", "93103"),
                                          ("93026", "93103")]
    assert len(views) == 31
    minute = 0
    for user, pid in views:
        add("behavior", f"{T21}T10:{minute // 60:02d}:{minute % 60:02d}+08:00",
            {"user_id": user, "product_id": pid, "session_id": f"s-21-{user}",
             "behavior_type": "view", "channel": "app"})
        minute += 2
    add("behavior", f"{T21}T10:30:00+08:00",
        {"user_id": "93021", "product_id": "93101", "session_id": "s-21-93021",
         "behavior_type": "favorite", "channel": "app"})
    add("behavior", f"{T21}T10:31:00+08:00",
        {"user_id": "93025", "product_id": "93102", "session_id": "s-21-93025",
         "behavior_type": "favorite", "channel": "app"})
    add("behavior", f"{T21}T11:00:00+08:00",
        {"user_id": "93022", "product_id": "93101", "session_id": "s-21-93022",
         "behavior_type": "cart_add", "channel": "app"})

    paid = [  # (order, user, product, price); gmv 1200, buyers 7
        ("942101", "93021", "93101", 100.0), ("942102", "93021", "93101", 100.0),
        ("942103", "93022", "93101", 100.0), ("942104", "93023", "93101", 100.0),
        ("942105", "93024", "93101", 100.0), ("942106", "93022", "93102", 88.0),
        ("942107", "93022", "93102", 88.0), ("942108", "93025", "93102", 88.0),
        ("942109", "93026", "93102", 88.0), ("942110", "93021", "93103", 87.0),
        ("942111", "93023", "93103", 87.0), ("942112", "93024", "93103", 87.0),
        ("942113", "93027", "93103", 87.0),
    ]
    for i, (oid, uid, pid, price) in enumerate(paid):
        add("order_created", f"{T21}T12:{i:02d}:00+08:00",
            {"order_id": oid, "user_id": uid, "total_amount": f"{price:.2f}",
             "items": items((pid, 1, price))})
        add("order_paid", f"{T21}T12:{i:02d}:30+08:00",
            {"order_id": oid, "user_id": uid, "payment_id": f"pay-{oid}",
             "amount": f"{price:.2f}"})
    for i, (rid, oid, uid, ts_c, ts_d) in enumerate([
            ("g4r0001", "942101", "93021", "14:00", "14:05"),
            ("g4r0002", "942103", "93022", "14:10", "14:15")]):
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
    parser = argparse.ArgumentParser(description="Generate deterministic G31-03 E3/E4 event fixtures")
    parser.add_argument(
        "--e4-date",
        default=(date.today() + timedelta(days=1)).isoformat(),
        help="E4 business date (ISO yyyy-mm-dd); defaults to tomorrow so it remains after today's completion date",
    )
    args = parser.parse_args()
    try:
        parsed_e4_date = date.fromisoformat(args.e4_date)
    except ValueError as exc:
        parser.error(f"--e4-date must be an ISO date (yyyy-mm-dd): {exc}")
    if parsed_e4_date <= date.today():
        parser.error("--e4-date must be later than today's local date so post-completion evaluation is meaningful")
    T21 = parsed_e4_date.isoformat()
    e3, e4 = build_e3(), build_e4()
    assert len(e3) == 99 and len(e4) == 71, (len(e3), len(e4))
    ids = [e["event_id"] for e in e3 + e4]
    assert len(ids) == len(set(ids)), "event_id collision"
    print(f"E4 business date: {T21}")
    write("e3-events.jsonl", e3)
    write("e4-events.jsonl", e4)
