#!/usr/bin/env python3
# Copyright (c) 2024, PostgreSQL Global Development Group
# See the LICENSE file in the project root for more information.
#
# Compare two PIT mutations.xml runs (e.g. unit-only vs. with database) and show
# per-class mutation score side by side, so it is obvious which codecs are only
# reached by the database-backed tests and which are genuinely untested.
#
#   python3 config/mutation/compare_runs.py unit.xml db.xml
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict


def load(path):
    root = ET.parse(path).getroot()
    by_class = defaultdict(lambda: defaultdict(int))
    for m in root.findall("mutation"):
        by_class[m.findtext("mutatedClass", "?")][m.get("status", "?")] += 1
    return by_class


def score(st):
    mut = sum(st.values())
    killed = st.get("KILLED", 0) + st.get("TIMED_OUT", 0)
    return mut, killed, (killed / mut * 100 if mut else 0.0)


def main(a, b):
    A, B = load(a), load(b)
    classes = sorted(set(A) | set(B))
    print(f"{'CLASS':<40}{'unit%':>8}{'+DB%':>8}{'gain':>8}{'mut':>6}")
    print("-" * 70)
    for cls in classes:
        ma, ka, sa = score(A.get(cls, {}))
        mb, kb, sb = score(B.get(cls, {}))
        mut = max(ma, mb)
        gain = sb - sa
        flag = ""
        if sa == 0 and sb > 0:
            flag = "  <- DB-only coverage"
        elif sb < 50:
            flag = "  <- still weak"
        print(f"{cls.rsplit('.',1)[-1]:<40}{sa:>7.0f}%{sb:>7.0f}%{gain:>+7.0f}{mut:>6}{flag}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
