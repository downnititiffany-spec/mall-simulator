# -*- coding: utf-8 -*-
"""BATCH-G3103 independent oracle — derives expected platform values directly
from the fixture JSONL by re-stating the frozen warehouse semantics in plain
Python (DwsSql.funnelDay / productBehaviorDay / tradeDay / userTradePeriod,
AdsSql.operationOverview / funnel / hotProduct / userProfile rfm-v2, and
DecisionService.evaluate/grade). Imports NO platform code: this file and the
fixtures are the only inputs, so a platform bug cannot leak into the oracle.

Usage: python oracle.py            -> prints EXPECTED_A / EXPECTED_B dicts
       python oracle.py --machine  -> machine-readable JSON blob for verify scripts
"""
import json
import sys
from collections import Counter, defaultdict
from datetime import date
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

HERE = Path(__file__).resolve().parent

DT_A = "2026-09-18"
# ads_metric_publish metric codes -> overview column mapping is done by the
# publisher; the oracle only needs value+period per code.


def load(name):
    return [json.loads(l) for l in (HERE / name).open(encoding="utf-8")]


def day(ev):
    return ev["event_time"][:10]


def r4(x):
    return float(Decimal(str(x)).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP))


def r2(x):
    # money scale: dws_trade_day/ads_operation_overview avg_order_value is
    # DECIMAL(18,2) (LocalSchemaInitJob / db/metric/V2) — publish carries 2dp.
    return float(Decimal(str(x)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def behavior_rows(events, dt):
    return [e["payload"] for e in events if e["event_type"] == "behavior" and day(e) == dt]


def paid_orders(events, dt):
    """Rows of dwd_order_detail semantics for dt: one row per order with
    final_paid_flag/amount/refund_amount/final_refunded_flag frozen by
    OrderTradeCompiler (refund_completed only, deduped by refund_id)."""
    created = {}
    paid_at = {}
    cancelled = set()
    refunds = defaultdict(Decimal)  # order_id -> sum of refund_completed amounts
    full_refund = set()
    for e in events:
        p, t = e["payload"], e["event_type"]
        if t == "order_created":
            created[p["order_id"]] = p
        elif t == "order_paid":
            paid_at[p["order_id"]] = p["amount"]
        elif t == "order_cancelled":
            cancelled.add(p["order_id"])
        elif t == "refund_completed":
            refunds[p["order_id"]] += Decimal(p["amount"])
    seen_refund = set()
    rows = []
    for oid, p in created.items():
        if day(next(e for e in events if e["event_type"] == "order_created"
                    and e["payload"]["order_id"] == oid)) != dt or oid not in paid_at:
            continue
        amt = Decimal(paid_at[oid])
        refund = refunds.get(oid, Decimal("0"))
        if oid in seen_refund:
            continue
        full = refund >= amt
        if full:
            seen_refund.add(oid)
        rows.append({"order_id": oid, "user_id": p["user_id"], "amount": amt,
                     "items": json.loads(p["items"]), "refund": refund, "full": full})
    # cancelled orders still occupy a dwd row with final_paid_flag=0 (funnel
    # order_users counts ALL order detail rows incl. cancelled).
    cancel_rows = []
    for oid in cancelled:
        e0 = next(e for e in events if e["event_type"] == "order_created"
                  and e["payload"]["order_id"] == oid)
        if day(e0) == dt:
            p = e0["payload"]
            cancel_rows.append({"order_id": oid, "user_id": p["user_id"],
                                "amount": Decimal("0"), "items": [],
                                "refund": Decimal("0"), "full": False})
    return rows, cancel_rows


def overview(events, dt):
    b = behavior_rows(events, dt)
    pv = sum(1 for r in b if r["behavior_type"] == "view")
    uv = len({r["user_id"] for r in b if r["behavior_type"] == "view"})
    dau = len({r["user_id"] for r in b})
    fav = sum(1 for r in b if r["behavior_type"] == "favorite")
    cart = sum(1 for r in b if r["behavior_type"] == "cart_add")
    paid, cancel = paid_orders(events, dt)
    order_rows = paid + cancel
    order_cnt = len(paid)
    buyer = len({r["user_id"] for r in paid})
    sale = sum(r["amount"] for r in paid)
    refund_amt = sum(r["refund"] for r in paid)
    net = sale - refund_amt
    refunded_orders = sum(1 for r in paid if r["refund"] > 0)
    full_refunded_orders = sum(1 for r in paid if r["full"])
    # repeat: dws_user_trade_period over [dt, dt]
    per_user = defaultdict(lambda: {"cnt": 0, "valid": 0})
    for r in paid:
        per_user[r["user_id"]]["cnt"] += 1
        if not r["full"]:
            per_user[r["user_id"]]["valid"] += 1
    pay_users = len(per_user)
    repeat_users = sum(1 for v in per_user.values() if v["valid"] >= 2)
    aov = float(sale) / order_cnt if order_cnt else None
    rr = refunded_orders / order_cnt if order_cnt else None
    frr = full_refunded_orders / order_cnt if order_cnt else None
    repeat = repeat_users / pay_users if pay_users else None
    cells = {
        "pv": (f"day:{dt}", pv), "uv": (f"day:{dt}", uv), "dau": (f"day:{dt}", dau),
        "fav_cnt": (f"day:{dt}", fav), "cart_add_cnt": (f"day:{dt}", cart),
        "paid_order_cnt": (f"day:{dt}", order_cnt), "gmv": (f"day:{dt}", float(sale)),
        "net_sale": (f"day:{dt}", float(net)),
        "avg_order_value": (f"day:{dt}", r2(aov) if aov is not None else None),
        "refund_rate": (f"day:{dt}", r4(rr) if rr is not None else None),
        "full_refund_rate": (f"day:{dt}", r4(frr) if frr is not None else None),
        "repeat_rate": (f"window:{dt}..{dt}", r4(repeat) if repeat is not None else None),
        "buy_rate": (f"day:{dt}", None), "cart_rate": (f"day:{dt}", None),
    }
    extra = {"buyer_count": buyer, "pay_users": pay_users,
             "repeat_users": repeat_users, "order_rows_incl_cancel": len(order_rows),
             "aov_exact": str(sale) + "/" + str(order_cnt)}
    return cells, extra


def funnel(events, dt):
    b = behavior_rows(events, dt)
    view_u = {r["user_id"] for r in b if r["behavior_type"] == "view"}
    intent_u = {r["user_id"] for r in b if r["behavior_type"] in ("favorite", "cart_add")}
    cart_u = {r["user_id"] for r in b if r["behavior_type"] == "cart_add"}
    paid, cancel = paid_orders(events, dt)
    order_u = {r["user_id"] for r in paid + cancel}
    pay_u = {r["user_id"] for r in paid}
    # Frozen stage set is view/intent/order/pay — no cart stage row
    # (AdsSql.funnel / S3-04: 加购不加 stage 行; cart coverage is the cart_rate metric).
    stages = {"view": len(view_u), "intent": len(intent_u),
              "order": len(order_u), "pay": len(pay_u)}
    rates = {
        "intent_rate": r4(len(intent_u) / len(view_u)) if view_u else None,
        "order_rate": r4(len(order_u) / len(intent_u)) if intent_u else None,
        "pay_rate": r4(len(pay_u) / len(order_u)) if order_u else None,
        "overall_buy_rate": r4(len(pay_u) / len(view_u)) if view_u else None,
        "cart_rate": r4(len(cart_u) / len(view_u)) if view_u else None,
    }
    return stages, rates


def hot_product(events, dt):
    b = behavior_rows(events, dt)
    stat = defaultdict(lambda: {"pv": 0, "uv": set(), "fav": 0, "cart": 0, "buy": 0})
    for r in b:
        s = stat[r["product_id"]]
        if r["behavior_type"] == "view":
            s["pv"] += 1
            s["uv"].add(r["user_id"])
        elif r["behavior_type"] == "favorite":
            s["fav"] += 1
        elif r["behavior_type"] == "cart_add":
            s["cart"] += 1
    paid, _ = paid_orders(events, dt)
    for r in paid:
        for it in r["items"]:
            stat[it["product_id"]]["buy"] += it["quantity"]
    import math
    ranked = []
    for pid, s in stat.items():
        heat = (1.0 * math.log1p(s["pv"]) + 2.0 * math.log1p(s["fav"])
                + 3.0 * math.log1p(s["cart"]) + 5.0 * math.log1p(s["buy"]))
        ranked.append({"product_id": pid, "pv": s["pv"], "uv": len(s["uv"]),
                       "fav": s["fav"], "cart": s["cart"], "buy": s["buy"],
                       "heat": round(heat, 6)})
    ranked.sort(key=lambda x: (-x["heat"], -x["buy"], x["product_id"]))
    for i, row in enumerate(ranked, 1):
        row["rank_no"] = i
    return ranked


def ntile(n, k):
    """Spark NTILE(5) bucket sizes: first (n mod k) buckets get base+1."""
    base, rem = divmod(n, k)
    sizes = [base + 1] * rem + [base] * (k - rem)
    out, pos = [], 0
    for s in sizes:
        out.extend([pos + 1] * s) if False else None
        pos += s
    buckets = []
    b, pos = 1, 0
    for s in sizes:
        buckets.extend([b] * s)
        b += 1
    return buckets


def rfm(events, dt):
    paid, _ = paid_orders(events, dt)
    per = defaultdict(lambda: {"last": None, "cnt": 0, "sale": Decimal("0"), "valid": 0})
    for r in paid:
        s = per[r["user_id"]]
        s["cnt"] += 1
        s["sale"] += r["amount"]
        s["last"] = dt
        if not r["full"]:
            s["valid"] += 1
    users = sorted(per)
    rows = [{"user_id": u, "r_days": 0, "f_count": per[u]["cnt"],
             "m_amount": float(per[u]["sale"]), "valid": per[u]["valid"]}
            for u in users]
    n = len(rows)
    if n:
        for col, nt in (("r", 1), ("f", 2), ("m", 3)):
            if nt == 1:
                key = lambda x: (x["r_days"], x["user_id"])
            elif nt == 2:
                key = lambda x: (x["f_count"], x["user_id"])
            else:
                key = lambda x: (x["m_amount"], x["user_id"])
            order = sorted(rows, key=key)
            buckets = ntile(n, 5)
            bmap = {row["user_id"]: b for row, b in zip(order, buckets)}
            for row in rows:
                row[f"{col}_ntile"] = bmap[row["user_id"]]
        for row in rows:
            row["r"] = 6 - row["r_ntile"]
            row["f"] = row["f_ntile"]
            row["m"] = row["m_ntile"]
            row["value_group"] = (
                ("重要价值" if row["f"] >= 4 else "重要发展") if row["m"] >= 4 and row["r_ntile"] <= 2
                else ("重要保持" if row["f"] >= 4 else "重要挽留") if row["m"] >= 4
                else ("一般价值" if row["f"] >= 4 else "一般发展") if row["r_ntile"] <= 2
                else ("一般保持" if row["f"] >= 4 else "一般挽留"))
            row["lifecycle_state"] = (
                "新用户" if row["f_count"] == 1 else "活跃")
    return rows


def decision_rate(baseline, actual, direction):
    rate = (Decimal(str(actual)) - Decimal(str(baseline))) / abs(Decimal(str(baseline)))
    rate = rate.quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)
    if direction == "DOWN":
        rate = -rate
    return float(rate)


def grade(baseline, actual, direction, target=None):
    rate = decision_rate(baseline, actual, direction)
    reached = (actual >= target) if (target is not None and direction == "UP") else \
              (actual <= target) if (target is not None and direction == "DOWN") else False
    if reached:
        g = "EFFECTIVE"
    elif rate >= 0.05:
        g = "EFFECTIVE"
    elif rate > 0:
        g = "PARTIAL"
    else:
        g = "INEFFECTIVE"
    return g, rate


def main():
    e3, e4 = load("e3-events.jsonl"), load("e4-events.jsonl")
    e4_dates = {day(e) for e in e4}
    if len(e4_dates) != 1:
        raise ValueError(f"E4 fixture must contain exactly one business date, got: {sorted(e4_dates)}")
    dt_b = next(iter(e4_dates))
    a_cells, a_extra = overview(e3, DT_A)
    b_cells, b_extra = overview(e4, dt_b)
    # buy_rate / cart_rate come from ads_behavior_funnel (dws funnelDay):
    _, a_rates = funnel(e3, DT_A)
    _, b_rates = funnel(e4, dt_b)
    a_cells["buy_rate"] = (f"day:{DT_A}", a_rates["overall_buy_rate"])
    a_cells["cart_rate"] = (f"day:{DT_A}", a_rates["cart_rate"])
    b_cells["buy_rate"] = (f"day:{dt_b}", b_rates["overall_buy_rate"])
    b_cells["cart_rate"] = (f"day:{dt_b}", b_rates["cart_rate"])
    out = {
        "EXPECTED_A": a_cells, "A_extra": a_extra,
        "EXPECTED_B": b_cells, "B_extra": b_extra,
        "A_funnel_stages": funnel(e3, DT_A)[0], "A_funnel_rates": a_rates,
        "A_hot_product": hot_product(e3, DT_A),
        "A_rfm": rfm(e3, DT_A), "B_rfm": rfm(e4, dt_b),
        "decisions": {
            "D1_avg_order_value_UP_target90": {
                "baseline": a_cells["avg_order_value"][1], "actual": b_cells["avg_order_value"][1],
                "grade_after_B": grade(a_cells["avg_order_value"][1], b_cells["avg_order_value"][1], "UP", 90.0),
                "insufficient_before_B": "actual snapshot == baseline snapshot -> 完成后尚未发布新快照"},
            "D2_pv_UP": {"baseline": a_cells["pv"][1], "actual": b_cells["pv"][1],
                         "grade_after_B": grade(a_cells["pv"][1], b_cells["pv"][1], "UP")},
            "D3_refund_rate_DOWN": {"baseline": a_cells["refund_rate"][1],
                                    "actual": b_cells["refund_rate"][1],
                                    "grade_after_B": grade(a_cells["refund_rate"][1], b_cells["refund_rate"][1], "DOWN")},
            "D4_gmv_UP": {"baseline": a_cells["gmv"][1], "actual": b_cells["gmv"][1],
                          "grade_after_B": grade(a_cells["gmv"][1], b_cells["gmv"][1], "UP")},
        },
    }
    if "--machine" in sys.argv:
        print(json.dumps(out, ensure_ascii=False, indent=1))
    else:
        print(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
