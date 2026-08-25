#!/usr/bin/env python3
"""Launches one process per cell of the factor matrix, which is how a campaign is taken.

One implementation per process is not a preference: two engines in one JVM make the dispatch
megamorphic and pollute every number after it, so the matrix runner is a process launcher and
nothing more. Cells run sequentially, because two cells sharing a machine would measure each other.

The factors mirror METHODOLOGY's design: implementation crossed with feature composition, then
rate and resting book size varied, with several seeds so a headline number rests on independent
sessions. Every cell's artifacts land under one campaign directory for analyze.py to read.

Usage:
  matrix.py --jar java/matching-benchmarks/target/benchmarks.jar --results results/campaign-01 \
            --cores 2,4,6 [--rates 100000,250000] [--seeds 1,2,3] [--dry-run]

On a machine nobody controlled, omit --cores and every run is exploratory, which is the honest
label for it. Standard library only.
"""

import argparse
import itertools
import subprocess
import sys
from pathlib import Path

IMPLEMENTATIONS = {
    "naive-java": "io.github.giovanicaprison.matching.naive.NaiveEngineFactory",
    "lean-java": "io.github.giovanicaprison.matching.lean.LeanEngineFactory",
}

# The lean engine exists to be compared on the flow that uses only what it has, so it runs at the
# limit-and-market composition alone. The full engine runs at both: same engine, both flows, is
# the cost of use; both engines, lean flow, is the cost of existence (P-16).
CELLS = [
    ("naive-java", "standard"),
    ("naive-java", "limit-and-market"),
    ("lean-java", "limit-and-market"),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--cores", default="", help="driver,engine,verifier; empty for unpinned")
    parser.add_argument("--rates", default="100000", help="offered rates, comma separated")
    parser.add_argument("--seeds", default="1", help="one independent session per seed")
    parser.add_argument("--commands", type=int, default=1_000_000)
    parser.add_argument("--warmup", type=int, default=200_000)
    parser.add_argument("--resting", default="5000", help="book sizes, comma separated")
    parser.add_argument("--dry-run", action="store_true")
    arguments = parser.parse_args()

    rates = [int(rate) for rate in arguments.rates.split(",")]
    seeds = [int(seed) for seed in arguments.seeds.split(",")]
    resting = [int(size) for size in arguments.resting.split(",")]

    launches = []
    for (label, composition), rate, seed, book in itertools.product(
        CELLS, rates, seeds, resting
    ):
        cell = f"{label}-{composition}-r{rate}-b{book}-s{seed}"
        launches.append(
            [
                "java",
                "-jar",
                str(arguments.jar),
                "--implementation", IMPLEMENTATIONS[label],
                "--label", cell,
                "--composition", composition,
                "--rate", str(rate),
                "--seed", str(seed),
                "--commands", str(arguments.commands),
                "--warmup", str(arguments.warmup),
                "--resting", str(book),
                "--results", str(arguments.results),
            ]
            + (["--cores", arguments.cores] if arguments.cores else [])
        )

    print(f"{len(launches)} cells -> {arguments.results}", file=sys.stderr)
    for launch in launches:
        print(" ".join(launch), file=sys.stderr)
        if arguments.dry_run:
            continue
        completed = subprocess.run(launch)
        if completed.returncode != 0:
            # A failed cell stops the campaign rather than leaving a hole a table would hide.
            raise SystemExit(f"cell failed with {completed.returncode}: {' '.join(launch)}")
    if not arguments.dry_run:
        print(f"done. read it with: scripts/analyze.py --campaign {arguments.results}")


if __name__ == "__main__":
    main()
