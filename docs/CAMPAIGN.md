# Campaign

METHODOLOGY says what a number must mean; this document is the runbook that produces one, start
to finish, so a campaign is repeatable by anyone holding this repository and a machine. Every
step names its reason, because the discipline is the research as much as the numbers are.

## The box

The reference machine is an m5zn.metal, two sockets of Cascade Lake at the highest all-core
clocks EC2 sells, with the full performance counter unit exposed. The research is single-engine
latency on three pinned cores per cell, so the clock matters and the core count does not, which
makes the high-frequency family the right box as well as the cheaper one. Everything below
adapts to any Intel bare metal: rehearse the whole process on another instance freely, but take
one campaign's numbers from one instance type only, because latency does not compare across
microarchitectures. Virtualised instances have no counters and a
hypervisor's jitter and are only good for correctness, which the deterministic suites pass
anywhere; ARM is a different instruction set and none of the x86 reasoning carries.

On the box, as root:

```
scripts/metal_setup.sh boot      # isolated cores, no tick, shallow C-states; then reboot
scripts/metal_setup.sh runtime   # performance governor, turbo off, after every boot
scripts/metal_setup.sh check     # prints what actually took
```

Isolation exists because the tail is the claim: one migration or one deep C-state exit lands in
a p99.9 and is indistinguishable from the engine's own cost. Turbo goes off because a thermally
variable frequency donates variance that reads as noise between runs. The check step matters
because every run's manifest records the environment it saw, and the grade falls when the
isolation asked for was refused, so a mis-tuned box produces labelled numbers rather than wrong
ones.

## The build

Java builds with the pinned Maven (`.tool-versions`): `mvn -T 1C package` from `java/`, and the
measured artifact is `java/matching-benchmarks/target/benchmarks.jar`. C++ configures and builds
with CMake from `cpp/`:

```
cmake -B cpp/build -S cpp -DCMAKE_CXX_FLAGS=-march=native
cmake --build cpp/build -j
```

The headline C++ numbers carry link time optimisation and profile guidance, so the record run
builds three times: once plain for the baseline, once with `-DMATCHING_LTO=ON`, and the PGO pair,
`-DMATCHING_PGO=generate`, one full run to write profiles, then `-DMATCHING_PGO=use` and the
measured run. All of it is recorded: the binaries compile their build type, LTO, PGO and extra
flags into every manifest they write, so a number's toolchain is in the artifact.

## The data

Two kinds of flow feed the engines, and they answer different questions. Generated flow is a
seeded, calibrated stream whose parameters were fitted to a real session; it reaches states real
data rarely does and is what the requirements gates and most cells run on. Real sessions come
from Nasdaq's public TotalView-ITCH sample days (emi.nasdaq.com, free), converted to the engine's
command log by the calibration replay:

```
zcat 01302020.NASDAQ_ITCH50.gz | java -cp \
  java/matching-calibration/target/matching-calibration-0.1.0-SNAPSHOT.jar:java/matching-benchmarks/target/benchmarks.jar \
  io.github.giovanicaprison.matching.calibration.Replay --stock AAPL --log session.log
```

The conversion is honest about what a feed cannot say: executions arrive as synthesized
aggressors (marketable orders from a reserved participant) because ITCH shows the resting side
only, replaces keep queue position the way the venue's own semantics do, and the pre-open prefix
converts as warm-up with measurement starting where the session does. The converter prints what
it skipped and why, and the counts belong in the campaign notes.

## The matrix

A campaign is the factor matrix from METHODOLOGY's design, one process per cell, cells run
sequentially: two engines in one JVM make dispatch megamorphic and pollute every number after,
and two cells sharing a machine measure each other. The Java side is launched by the runner
script; the C++ side is one binary with an implementation registry:

```
python3 scripts/matrix.py --jar java/matching-benchmarks/target/benchmarks.jar \
  --results results/campaign-01 --cores 2,4,6 --seeds 7,11,13
cpp/build/benchmarks/benchmarks --implementation flyweight --log session.log \
  --cores 2,4,6 --results results/campaign-01 [--counters NAMES]
```

Cores come as three, driver first, so the load generator, the engine and the measurement thread
each own a core inside the isolated set. Per-command-type series (`--counters`, the types
artifact) run as their own cells, because reading counters is itself an instrument with a cost
the manifest must carry.

## The analysis

`python3 scripts/analyze.py results/campaign-01/*` turns run directories into the tables
METHODOLOGY promises, both languages in one table since the artifacts are byte compatible. No
figure is written by hand: a number in a write-up traces to the run directory that produced it,
and the raw series is kept precisely so a question asked next year is answerable without the
machine.

## What each run answers

The rungs ladder (naive, indexed, pooled, flyweight) isolates what each representation buys; the
lean twins price the full remit against the rung's own book; the decode-only cell prices the
protocol before any matching; rate, resting book size and the mid-session regime shift probe
sensitivity to load and state; the LTO and PGO flavours price the toolchain; and the same log
through both languages, digest-checked for identity first, is the cross-language question, where
the medians are expected to agree and the tails to diverge, which is the finding worth the whole
campaign.
