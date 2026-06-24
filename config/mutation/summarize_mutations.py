#!/usr/bin/env python3
# Copyright (c) 2024, PostgreSQL Global Development Group
# See the LICENSE file in the project root for more information.
#
# Summarize a PIT mutations.xml into an actionable "which lines need more tests"
# report. SURVIVED and NO_COVERAGE mutations are the gaps:
#   NO_COVERAGE -> the line is never executed by any test (biggest gap)
#   SURVIVED    -> the line runs, but no assertion notices when it changes
#
# Usage:
#   python3 config/mutation/summarize_mutations.py pgjdbc/build/reports/pitest-codec/mutations.xml
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

GAP = {"SURVIVED", "NO_COVERAGE"}


def short_mutator(name):
    return name.rsplit(".", 1)[-1].replace("Mutator", "")


def main(path):
    root = ET.parse(path).getroot()
    by_class = defaultdict(lambda: defaultdict(int))   # class -> status -> count
    gaps = defaultdict(list)                             # class -> [(line, status, mutator, desc, method)]
    total = defaultdict(int)

    for m in root.findall("mutation"):
        status = m.get("status", "?")
        cls = m.findtext("mutatedClass", "?")
        line = int(m.findtext("lineNumber", "0"))
        mutator = short_mutator(m.findtext("mutator", "?"))
        desc = m.findtext("description", "")
        method = m.findtext("mutatedMethod", "")
        by_class[cls][status] += 1
        total[status] += 1
        if status in GAP:
            gaps[cls].append((line, status, mutator, desc, method))

    grand = sum(total.values())
    killed = total.get("KILLED", 0) + total.get("TIMED_OUT", 0)
    gap_count = total.get("SURVIVED", 0) + total.get("NO_COVERAGE", 0)
    # Mutation score = killed / (mutations that could be killed)
    killable = grand - total.get("NON_VIABLE", 0) - total.get("RUN_ERROR", 0) \
        - total.get("MEMORY_ERROR", 0)
    score = (killed / killable * 100) if killable else 0.0

    print("=" * 78)
    print("PIT MUTATION SUMMARY")
    print("=" * 78)
    print(f"total mutations : {grand}")
    for st in sorted(total):
        print(f"  {st:<12}: {total[st]}")
    print(f"mutation score  : {score:.1f}%  ({killed}/{killable} killed)")
    print(f"GAPS to address : {gap_count}  (SURVIVED + NO_COVERAGE)")
    print()

    # Per-class ranking, worst (most gaps) first
    print("-" * 78)
    print(f"{'CLASS':<40}{'mut':>6}{'kill':>6}{'surv':>6}{'noCov':>6}{'score':>8}")
    print("-" * 78)
    rows = []
    for cls, st in by_class.items():
        mut = sum(st.values())
        k = st.get("KILLED", 0) + st.get("TIMED_OUT", 0)
        surv = st.get("SURVIVED", 0)
        nocov = st.get("NO_COVERAGE", 0)
        sc = (k / mut * 100) if mut else 0.0
        rows.append((surv + nocov, cls, mut, k, surv, nocov, sc))
    for gaps_n, cls, mut, k, surv, nocov, sc in sorted(rows, key=lambda r: (-r[0], r[1])):
        short = cls.rsplit(".", 1)[-1]
        print(f"{short:<40}{mut:>6}{k:>6}{surv:>6}{nocov:>6}{sc:>7.0f}%")
    print()

    # Detailed gap list, grouped by class then line
    print("=" * 78)
    print("LINES TO TEST MORE (surviving / uncovered mutations)")
    print("=" * 78)
    for gaps_n, cls, *_ in sorted(rows, key=lambda r: (-r[0], r[1])):
        if gaps_n == 0:
            continue
        short = cls.rsplit(".", 1)[-1]
        print(f"\n### {short}  ({gaps_n} gaps)")
        for line, status, mutator, desc, method in sorted(gaps[cls]):
            tag = "NO-COV " if status == "NO_COVERAGE" else "SURVIVE"
            print(f"  L{line:<5} [{tag}] {method}(): {desc} ({mutator})")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else
         "pgjdbc/build/reports/pitest-codec/mutations.xml")
