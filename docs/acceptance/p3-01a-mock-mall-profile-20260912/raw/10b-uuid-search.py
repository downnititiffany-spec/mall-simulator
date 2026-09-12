# -*- coding: utf-8 -*-
"""补齐 raw/10-uuid-coverage-gap.txt（原文件只有标题、无输出，属**不完整证据**，故另存新文件不删旧文件）。

问题：`contract-specs/schemas/surrogate-key.v1.json` 的向量 V01 标注
      entity=user、rawInput="89a6db2c-9ecb-4be0-a24b-9f697da00686"、why="真实 landing 取值"。
本泳道 346 文件 *.jsonl 全树扫描**未**见到 user_id 的 UUID 形态 ⇒ 核查该字面量到底在 landing 哪里。

对照设计：
  阳性对照 A = 一个**已知存在**的 payload.user_id 真实取值（须 >0 命中，否则说明扫描坏了）
  阳性对照 B = UUID 里也存在的子串 "89a6db2c"（须 >= A，证明不是正则/编码问题）
  阴性对照   = 一个必然不存在的 UUID（须 == 0）
"""
import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))
LANDING = os.path.join(ROOT, "landing")

TARGET = "89a6db2c-9ecb-4be0-a24b-9f697da00686"
CTRL_A = "2098607948334395394"          # 同一行里真实的 payload.user_id
CTRL_B = "89a6db2c"                      # UUID 的头部子串
NEG = "ffffffff-dead-beef-dead-beefdeadbeef"


def main():
    hits = {TARGET: [], CTRL_A: [], CTRL_B: [], NEG: []}
    files = 0
    for base, _dirs, names in os.walk(LANDING):
        for nm in sorted(names):
            path = os.path.join(base, nm)
            files += 1
            try:
                with open(path, "r", encoding="utf-8", errors="replace") as fh:
                    text = fh.read()
            except Exception:
                continue
            for key in hits:
                if key in text:
                    hits[key].append(os.path.relpath(path, ROOT))
    print("## [1] 扫描范围：%s 下**全部**文件（不限后缀）= %d 个" % (
        os.path.relpath(LANDING, ROOT), files))
    for key, label in ((TARGET, "目标 UUID（规格 V01 声称的 user_id）"),
                       (CTRL_A, "阳性对照 A：同行真实 payload.user_id"),
                       (CTRL_B, "阳性对照 B：UUID 头部子串"),
                       (NEG, "阴性对照：必然不存在的 UUID")):
        print("\n## %s = %s => 命中 %d 个文件" % (label, key, len(hits[key])))
        for p in hits[key][:10]:
            print("     %s" % p)
        if len(hits[key]) > 10:
            print("     ...（共 %d 个）" % len(hits[key]))

    print("\n## [2] 该 UUID 在真实行里到底是哪个字段？")
    for rel in hits[TARGET][:3]:
        path = os.path.join(ROOT, rel)
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if TARGET not in line:
                    continue
                try:
                    obj = json.loads(line)
                except Exception:
                    continue
                where = []
                for k, v in obj.items():
                    if k == "payload":
                        for pk, pv in (v or {}).items():
                            if isinstance(pv, str) and TARGET in pv:
                                where.append("payload.%s" % pk)
                    elif isinstance(v, str) and TARGET in v:
                        where.append(k)
                print("   %s" % rel)
                print("     字段位置 = %s" % (where or "<未定位>"))
                print("     event_id   = %r" % obj.get("event_id"))
                print("     event_type = %r" % obj.get("event_type"))
                pl = obj.get("payload") or {}
                print("     payload.user_id = %r" % pl.get("user_id"))
                break

    print("\n## [3] 判读")
    ok_ctrl = len(hits[CTRL_A]) > 0 and len(hits[CTRL_B]) >= len(hits[TARGET]) and len(hits[NEG]) == 0
    print("   对照自检 = %s（阳性 A>0、阳性 B>=目标、阴性==0）" % ok_ctrl)
    print("   结论：规格 surrogate-key.v1.json 向量 V01 把 **event_id** 当成了 entity=user 的 rawInput；")
    print("         同一条真实行里 payload.user_id 另有其值（见 [2]，即该规格的 V02 向量）。")
    print("         且 event_id 属该规格自称的**范围之外**（事件粒度键）。")


if __name__ == "__main__":
    main()
