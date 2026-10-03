#!/usr/bin/env python3
"""Prints a Markdown table pairing J# and baseline benchmarks from a JMH JSON result file.

A J# benchmark is named `jsharp_X`; its baseline is `java_X` or `stream_X` in the same class.
Usage: scripts/bench_table.py bench/results/m6.json
"""
import json
import sys

def main(path):
    data = json.load(open(path))
    rows = {}
    for x in data:
        cls, name = x["benchmark"].split(".")[-2:]
        prefix, kernel = name.split("_", 1)
        alloc = x.get("secondaryMetrics", {}).get("gc.alloc.rate.norm", {}).get("score")
        rows.setdefault((cls, kernel), {})[prefix] = (
            x["primaryMetric"]["score"], x["primaryMetric"]["scoreError"], alloc)
    print("| Benchmark | Baseline (µs/op) | J# (µs/op) | Ratio | Baseline B/op | J# B/op |")
    print("|---|---:|---:|---:|---:|---:|")
    for (cls, kernel), r in sorted(rows.items()):
        base_name = "java" if "java" in r else "stream"
        if "jsharp" not in r or base_name not in r:
            continue
        b, j = r[base_name], r["jsharp"]
        fmt_alloc = lambda a: "–" if a is None else f"{a:,.0f}"
        print(f"| {cls}.{kernel} | {b[0]:.2f} ± {b[1]:.2f} | {j[0]:.2f} ± {j[1]:.2f} | "
              f"{j[0] / b[0]:.2f} | {fmt_alloc(b[2])} | {fmt_alloc(j[2])} |")

if __name__ == "__main__":
    main(sys.argv[1])
