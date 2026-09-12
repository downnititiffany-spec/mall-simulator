#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""fix-readme-review5.py —— 处置 guard 新报的 1 处新裸锚点（缺陷 5）。

现场（guard 实测，见 anchor-guard.txt）：
  [门禁失败] 台账内锚点出现次数升高 1 处—— contract-specs\\README.md :: §1 L16 台账=2 实测=3
根因：fix-readme-review3.py 在 §14.3 加的**逐字原句引文**里含无前缀的 `§1 L16「固定值：mock-mall」`
      （guard 的窗口 = 锚点前 100 字符内需出现文件名或版本号，引文里两者都没有 ⇒ 判为裸锚点）。
处置：**只给本轮新增的那处**（§14.3 引文行）补上文件名前缀；§8 第 3 条 Q3 那处（L101）是**历史基线**，
      台账已登记 2 处（L82 的 §5 历史引述 ＋ L101 的 Q3），刻意不动、不改写历史行。
幂等：已修过则报「已存在」退出 0。
"""
import hashlib
import sys

RM = r"D:\Develop_code\GraduationProject-wt\ct-batch\contract-specs\README.md"
EOL = "\r\n"

D5_OLD = ('  > 原句（`2.1.0` 期，2026-09-12 前）：「- **`source_system` 取 `const: "mock-mall"`**：'
          '§1 L16「固定值：mock-mall」，且')
D5_NEW = ('  > 原句（源文件 `docs/contracts/event-contract.md`，`2.1.0` 期，2026-09-12 前）：'
          '「- **`source_system` 取 `const: "mock-mall"`**：§1 L16「固定值：mock-mall」，且')


def main():
    raw = open(RM, "rb").read()
    t = raw.decode("utf-8")
    n_line = len(t.splitlines())
    print(f"README raw sha256 = {hashlib.sha256(raw).hexdigest().upper()}  {len(raw)} B  CR={raw.count(13)} LF={raw.count(10)}  行数={n_line}")
    if raw.count(13) != raw.count(10) or n_line < 300:
        print("[FAIL] 哨兵：行尾/行数异常 ⇒ 不写盘")
        return 2
    print(f"[OK ] 哨兵：CR == LF == {raw.count(10)}，行数 {n_line} ≥ 300")

    if D5_NEW.replace("\n", EOL) in t:
        print("[同 ] 缺陷 5 修正**已存在**（幂等）")
        return 0
    o, n = D5_OLD.replace("\n", EOL), D5_NEW.replace("\n", EOL)
    c1, c2 = t.count(o), t.count(n)
    print(f"[{'OK ' if c1 == 1 and c2 == 0 else 'FAIL'}] 旧串命中={c1}（应然 1） 新串命中={c2}（应然 0）")
    if c1 != 1 or c2 != 0:
        return 2
    t = t.replace(o, n, 1)

    # 判据：修正后 README 内 `§1 L16` 的**裸**形式应为 2 处（L82 历史引述 ＋ L101 Q3），
    #       且 §14.3 引文行不再含裸形式。
    import re
    RXA = re.compile(r"§1\s*L16")
    RXF = re.compile(r"[A-Za-z0-9_\-\./\\]+\.(?:md|json|yaml|yml|java|scala|sql|txt|csv)\b")
    RXV = re.compile(r"[Vv]\d+\.\d+")
    bare_lines = []
    for i, ln in enumerate(t.splitlines(), 1):
        for m in RXA.finditer(ln):
            w = ln[max(0, m.start() - 100):m.start()]
            if not (RXF.search(w) or RXV.search(w)):
                bare_lines.append((i, "".join(w[-40:])))
    print(f"判据：README 内 `§1 L16` 裸形式 = {len(bare_lines)} 处（应然 2）")
    for i, w in bare_lines:
        print(f"    L{i}  窗口尾 = {w!r}")
    if len(bare_lines) != 2:
        print("SELFCHECK-FAIL ⇒ 不写盘")
        return 2

    nb = t.encode("utf-8")
    ok = nb.count(13) == nb.count(10)
    print(f"[{'OK ' if ok else 'FAIL'}] 写后 CR == LF：{nb.count(13)} == {nb.count(10)}")
    if not ok:
        return 2
    open(RM, "wb").write(nb)
    print(f">>> 已写盘  raw sha256 = {hashlib.sha256(nb).hexdigest().upper()}  {len(nb)} B  CR={nb.count(13)} LF={nb.count(10)}  行数={len(t.splitlines())}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
