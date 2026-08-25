#!/usr/bin/env python3
"""Turns run directories into the tables METHODOLOGY promises.

Every figure in a write up is generated from stored artifacts by this script, never written by
hand, so a number is traceable to the run that produced it and the environment that run recorded.

Reads, per run directory: manifest.json (identity, grade, environment, flow), measurement.json
(counts, counters, placement, samples, summaries), and timings.bin (METIMES1: four timestamps per
command, little endian), from which every distribution is recomputed rather than trusted.

Usage:
  analyze.py RUN_DIR [RUN_DIR ...]        one summary row per run, then detail per run
  analyze.py --campaign CAMPAIGN_DIR      every run under the directory, grouped for comparison
  analyze.py --warmup N ...               drop the first N recorded commands from distributions

Standard library only, so the analysis machine needs nothing but a Python.
"""

import argparse
import json
import struct
import sys
from pathlib import Path

MAGIC = b"METIMES1"
PERCENTILES = (50.0, 99.0, 99.9, 100.0)


def read_timings(path):
    """The four timestamps per command: intended, published, started, finished."""
    raw = path.read_bytes()
    if raw[:8] != MAGIC:
        raise SystemExit(f"{path} is not a timings file")
    (count,) = struct.unpack_from("<i", raw, 8)
    rows = struct.unpack_from(f"<{count * 4}q", raw, 12)
    return [tuple(rows[at : at + 4]) for at in range(0, count * 4, 4)]


def percentile(sorted_values, at):
    if not sorted_values:
        return 0
    index = min(len(sorted_values) - 1, int(at / 100.0 * len(sorted_values)))
    return sorted_values[index]


def distributions(timings, warmup):
    """The four durations METHODOLOGY names, recomputed from the raw timestamps."""
    kept = timings[warmup:]
    series = {
        "offered": sorted(published - intended for intended, published, _, _ in kept),
        "queued": sorted(started - published for _, published, started, _ in kept),
        "service": sorted(finished - started for _, _, started, finished in kept),
        "response": sorted(finished - intended for intended, _, _, finished in kept),
    }
    return {
        name: {f"p{at:g}": percentile(values, at) for at in PERCENTILES}
        for name, values in series.items()
    }


def load(run_dir):
    run = {"directory": run_dir}
    run["manifest"] = json.loads((run_dir / "manifest.json").read_text())
    run["measurement"] = json.loads((run_dir / "measurement.json").read_text())
    run["timings"] = read_timings(run_dir / "timings.bin")
    return run


def cell(value):
    return f"{value:,}" if isinstance(value, int) else str(value)


def table(headers, rows):
    lines = ["| " + " | ".join(headers) + " |", "|" + "|".join("---" for _ in headers) + "|"]
    for row in rows:
        lines.append("| " + " | ".join(cell(value) for value in row) + " |")
    return "\n".join(lines)


def summary_rows(runs, warmup):
    rows = []
    for run in runs:
        manifest, measurement = run["manifest"], run["measurement"]
        service = distributions(run["timings"], warmup)["service"]
        response = distributions(run["timings"], warmup)["response"]
        rows.append(
            (
                manifest["run"],
                manifest["implementation"].rsplit(".", 1)[-1],
                manifest["grade"],
                measurement["commands"],
                "yes" if measurement["harnessKeptUp"] else "NO",
                service["p50"],
                service["p99"],
                service["p99.9"],
                response["p99"],
            )
        )
    return rows


def detail(run, warmup):
    manifest, measurement = run["manifest"], run["measurement"]
    print(f"\n### {manifest['run']}  ({manifest['grade']})\n")
    print(f"implementation `{manifest['implementation']}`  commit `{manifest['commit']}`")
    flow = manifest["flow"]
    print(
        f"flow seed {flow['seed']}, {flow['commands']:,} commands,"
        f" {flow['restingOrders']:,} resting"
    )
    unmet = [s for s in manifest["environment"] if s.get("status") == "WRONG"]
    unavailable = [s for s in manifest["environment"] if s.get("status") == "UNAVAILABLE"]
    print(f"environment: {len(unmet)} wrong, {len(unavailable)} unavailable")
    for setting in unmet:
        print(f"  wrong: {setting['name']} = {setting['actual']} (wanted {setting['expected']})")
    for setting in manifest.get("isolation", []):
        print(f"  isolation: {setting['name']} = {setting.get('actual')}")
    print()
    rows = [
        (name, *(values[f"p{at:g}"] for at in PERCENTILES))
        for name, values in distributions(run["timings"], warmup).items()
    ]
    print(table(("nanoseconds", "p50", "p99", "p99.9", "max"), rows))
    counters = {k: v for k, v in measurement.get("counters", {}).items() if k != "multiplexed"}
    if counters:
        print()
        print(table(("counter", "count"), sorted(counters.items())))
        if measurement["counters"].get("multiplexed"):
            print("\ncounters were multiplexed: values are extrapolations, not counts")
    before = {s["name"]: s.get("actual") for s in measurement.get("sampledBefore", [])}
    after = {s["name"]: s.get("actual") for s in measurement.get("sampledAfter", [])}
    moved = [
        (name, before[name], after.get(name))
        for name in before
        if before[name] is not None and before[name] != after.get(name)
    ]
    if moved:
        print()
        print(table(("sample", "before", "after"), moved))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", nargs="*", type=Path)
    parser.add_argument("--campaign", type=Path, help="a directory holding many run directories")
    parser.add_argument("--warmup", type=int, default=0, help="recorded commands to drop")
    arguments = parser.parse_args()

    directories = list(arguments.runs)
    if arguments.campaign:
        directories += sorted(
            child for child in arguments.campaign.rglob("*") if (child / "manifest.json").exists()
        )
    if not directories:
        parser.error("no run directories given")

    runs = [load(directory) for directory in directories]
    print(
        table(
            ("run", "engine", "grade", "commands", "kept up", "svc p50", "svc p99", "svc p99.9", "rsp p99"),
            summary_rows(runs, arguments.warmup),
        )
    )
    for run in runs:
        detail(run, arguments.warmup)


if __name__ == "__main__":
    sys.exit(main())
