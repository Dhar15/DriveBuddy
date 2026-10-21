#!/usr/bin/env python3
"""Summarise DriveBuddy latency.jsonl pulled from the phone.

Usage: python3 tools/summarize_latency.py latency.jsonl
Go/kill lines come from section 14 of the assessment:
  end_to_ack   p90 < 1000 ms
  end_to_voice median < 2000 ms
"""
import json
import math
import sys

METRICS = ["trigger_to_listening", "end_to_ack", "end_to_final", "final_to_voice", "end_to_voice"]
LINES = {"end_to_ack": ("p90", 1000), "end_to_voice": ("p50", 2000)}


def percentile(values, p):
    """Nearest-rank percentile; values must be non-empty."""
    s = sorted(values)
    rank = max(1, math.ceil(p / 100 * len(s)))
    return s[rank - 1]


def load(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                print(f"skipping line {n}: not valid JSON", file=sys.stderr)
    return rows


def summarise(rows):
    out = {}
    for m in METRICS:
        vals = [r[m] for r in rows if r.get(m) is not None]
        if vals:
            out[m] = {"n": len(vals), "p50": percentile(vals, 50), "p90": percentile(vals, 90), "max": max(vals)}
    return out


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    rows = load(argv[1])
    errors = sum(1 for r in rows if str(r.get("command", "")).startswith("error"))
    print(f"interactions: {len(rows)}   errors: {errors}")
    stats = summarise(rows)
    for m in METRICS:
        s = stats.get(m)
        if not s:
            print(f"{m:22s} no data")
            continue
        verdict = ""
        if m in LINES:
            key, limit = LINES[m]
            verdict = "  PASS" if s[key] < limit else "  FAIL"
            verdict += f" ({key} < {limit} ms)"
        print(f"{m:22s} n={s['n']:3d}  p50={s['p50']:5d}  p90={s['p90']:5d}  max={s['max']:5d} ms{verdict}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
