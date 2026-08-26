# Methodology

How performance is measured, with what, and what each run records. `TESTING.md` covers correctness;
the two share a flow generator and nothing else.

## Questions

The study asks four things, in increasing order of how hard the answer is to guess.

What does supporting a feature cost when nobody uses it. Published matching engine figures are taken
on books holding limit and market orders. Every venue also has icebergs, stops, self match
prevention, minimum quantity and banding, and each imposes work on the hot path: a trigger check
after every execution, a replenishment that changes what a price level is, a comparison per candidate.

What does using a feature cost. Measured by composition of the input, holding the engine constant.

Where does the cost of abstraction live. Each implementation removes one class of managed runtime
cost, so the difference between two of them attributes the penalty to a mechanism instead of
producing a ratio. With a C++ implementation at a matched layout, language separates from layout: the
step between implementations isolates layout, and the step between languages at a fixed layout
isolates the runtime.

Does a structural advantage survive a venue's whole feature set. This is the primary question and
its answer is not predictable. A flat price ladder's advantage may shrink once a trigger book must be
consulted after every execution and iceberg replenishment forces indirection the layout cannot
remove.

A fifth result comes from the same harness and is the one the operator of a real venue would read
first. An
engine warmed on one market regime, tight spread and shallow book, and then given another, wide and
deep and bursty, may deoptimise or continue on a badly specialised profile. A native implementation
has no equivalent failure, so it serves as a control. A single ratio conceals this entirely.

## Design

Factors that vary: implementation, language at matched layouts, feature composition of the input,
resting book size, flow regime, and buffer bounds checking.

Factors held constant within a comparison: the machine, the input log, the instrument definition, the
allocation algorithm, and every environment setting recorded in the manifest.

The primary question is a cross term, so implementation and feature composition are crossed rather
than varied one at a time. A result that holds only at one feature composition is reported as such.

## What is measured

The unit is the service time of one command, from the engine receiving a framed message to the last
event it produces for that command. Decode and encode are inside it, because both belong to an
implementation.

Measurement is open loop at a fixed offered rate. A closed loop harness that sends the next command
when the last one returns cannot observe a stall: the harness waits, the missing samples are never
taken, and the tail disappears. That is coordinated omission, and it is the most common way a latency
benchmark flatters its subject.

Throughput is derived, not headline. The reported ceiling is the offered rate at which the tail stops
being bounded, which is the number an operator plans against.

Decode is measured on its own as well as inside the engine. Without that separation the difference
between two books can be swamped by the difference between two decoders.

The input log is generated, encoded and resident in memory before measurement begins. No file
reading, no allocation and no generation happens inside the measured path.

Three threads, each on its own core, in the shape a venue deploys. A driver publishes commands into a
ring buffer at a fixed rate. The engine reads them from that ring and publishes its events into
another. The verification consumer reads those and never touches the engine's core, which is the
point: counting events by type and checksumming the output stream would otherwise sit inside the
number they exist to protect.

What is left on the measured core is what a venue pays. A read from the input ring, a publish per
event, and the two clock reads that bracket the command. Ring publication is engine work in any real
deployment, and venues timestamp at their boundaries too. Each of the three is measured on its own and
reported with the run, so a reader can see the floor under every number.

A full ring is back pressure rather than an outlier to explain away. The driver counts the times it
could not publish at its intended moment and the engine counts the times it waited for space. A run
reporting either is flagged, because those numbers describe a harness that could not keep up rather
than an engine.

Every command carries four timestamps, so a wait is attributable rather than lumped together. Intended
to offered is the driver being late, which is harness cost. Offered to started is time on the ring,
which is queueing. Started to finished is the engine, which is service time. Intended to finished is
what a client would have seen. A number nobody can decompose is a number somebody will argue about.

## Steady state and book state

Two kinds of warm-up matter and they are separate.

The runtime reaches steady state when compilation has settled. Measurement begins after that, and the
compilation log is retained so the claim can be checked.

The book reaches its intended size before the measured batch, and the batch is sized so the book
does not drift materially during it. A batch comparable to the book size measures an average over a
moving structure. Where drift is unavoidable it is reported with the number.

## Environment

Runs are taken on a bare metal instance. That is what gives a real performance monitoring unit, and it
removes hypervisor scheduling from the measurement. A shared instance provides neither.

Some settings are boot parameters and need a reboot after the instance comes up: `isolcpus`,
`nohz_full` and `rcu_nocbs` for the measured core, and `processor.max_cstate=1` with
`intel_idle.max_cstate=0`. Deep C-state exit latency lands directly in the tail, so a benchmark that
leaves idle states alone is measuring the power manager.

The rest are set at runtime: the scaling governor to performance, turbo disabled so frequency is fixed
instead of drifting, the hyperthread sibling of the measured core taken offline through `/sys`, and
huge pages reserved if an implementation uses them. The instruments need `perf_event_paranoid` at 1 or
below, and `kptr_restrict` at 0 for kernel symbols in a profile.

Every one of these is verified after boot and recorded, alongside the kernel, the processor model, the
runtime build, the collector and heap settings, the compiler and its flags, and the instance identity.
A setting that was requested and did not take is worse than one never requested, because the run looks
controlled. The harness reads each one from the kernel's own files and grades the run on what it finds:
a machine that is not set up produces an exploratory run, labelled in its manifest, which is useful
while an implementation is being written and is not a result. The processor model matters because
counter names and meanings are microarchitecture specific, so a comparison across instance families is
not a comparison.

## Statistics

Within a run, the harness reports a distribution. Nothing reports a mean as its headline.

Between runs, the larger source of variance is usually the session rather than the iteration, so a
headline number requires at least three independent sessions. Variance within a session and variance
between sessions are reported separately, because a tight interval inside one session says little
about reproducibility.

Comparisons are paired. Two implementations are measured on the same log, on the same machine, with
runs interleaved so that drift affects both. The reported quantity is the distribution of paired
differences, with a bootstrap interval on the percentile in question.

Outliers are not discarded. In a matching engine the tail is the subject, and an outlier is either a
finding or a defect in the harness. Where one is excluded, the reason and the count are reported.

Before any comparison, the smallest difference the setup can detect is established by measuring one
implementation against itself. A difference below that is not reported as a difference.

## Instruments

Profiling follows a measured difference and never substitutes for one. A finding is a difference plus a
mechanism; a difference alone is an observation. The order below runs from cheapest and broadest to
most expensive and narrowest, and every entry names the instrument for both languages.

**Latency.** Four timestamps a command, kept raw. Two stores into a preallocated array cost less on
the measured core than recording into a histogram, which computes a bucket index and then touches a
counts array large enough to miss cache, and the series answers what a histogram cannot: when a stall
happened, whether stalls cluster, and what the run looked like as it went. Histograms are produced
from it afterwards and stored encoded, in HdrHistogram's format, which exists for both languages so
one analysis pipeline reads both. The harness is ours and open loop; JMH and Google Benchmark are both
closed loop and cannot observe a stall, so they are used only for microbenchmarks of a single
operation, where they handle warm-up, forking and dead code elimination better than we would.

**Aggregate counters.** `perf stat` for both: cycles, instructions, instructions per cycle, cache
misses at each level, TLB misses, branch mispredictions, and stalled cycles front and back end. Per
process over an interval, so attributing a miss to a line of code needs a sampling profiler. `perf c2c`
for false sharing, once a publisher moves to another core.

**Bracketed counters.** Hardware counters opened on the measured thread and read at both ends of the
reported region, through `perf_event_open`. Java reaches it through the foreign function API, C++
directly, so both produce the same numbers around the same region. Two `read` calls a run rather than
the mapped counter page and `rdpmc`: a managed runtime cannot emit that instruction, and using it on
one side only would put the difference between two reading mechanisms inside a comparison of two
engines. Bracketing millions of commands makes two syscalls irrelevant, which is why the region rather
than the command is the unit.

Counting excludes the kernel and the hypervisor, so a syscall somebody else made cannot land in the
number. A processor has a handful of counter slots, so a set is chosen to fit; where the kernel has to
multiplex, every value becomes an extrapolation from the fraction of the time it was counting, and a
run that did this says so rather than presenting the numbers as counts.

This is what excludes runtime noise from a count: compiler threads, collector threads and startup are
all on other threads and are not counted. Instructions retired is far more stable run to run than
cycles, its variance coming only from recompilation and safepoints, which makes it the closest thing
available to a noise free comparison of two implementations of one algorithm.

**Attribution to code.** Java uses async-profiler, which samples on a signal so it can interrupt
anywhere, walks JIT frames as well as native and kernel ones, and can sample on perf events so a cache
miss is attributable to a call site. It wants `-XX:+DebugNonSafepoints` for frame information away from
safepoints and `-XX:+PreserveFramePointer` for reliable walking.

Flight Recorder's method profiler samples at safepoints instead. A stretch of code containing no
safepoint poll is invisible to it and its cost lands on whatever came next, so every flame graph
produced through Mission Control carries that bias. Flight Recorder is therefore the event timeline
here and async-profiler is the attribution; treating either as the other produces confident nonsense.

C++ uses `perf record`, with `perf report` for a call graph and `perf annotate` for per instruction
attribution inside a function, and flame graphs from `perf script` through the stack collapse scripts.
The C++ side sees more here, since there is no runtime compilation to resolve first.

**Events on the timeline.** Java uses Flight Recorder, read with `jfr print` for scripted extraction
rather than through a viewer, and configured with a custom settings file. Neither shipped profile is
right: the default omits events we want, and the profiling one enables two we cannot afford. It raises
method sampling, which is the safepoint biased instrument we replaced, so the extra samples are more of
the data we do not trust. And it enables per-allocation events carrying stack traces, which puts a
stack walk on the allocation path inside the operation whose cost we are establishing.

That second objection is not about overhead. A uniform slowdown shifts the whole distribution and a
comparison survives it. A stack walk on thread local buffer refill fires on some commands and not
others, so it inflates particular percentiles and changes the shape, and the shape is the subject. The
same reasoning excludes stack traces on every exception, which should be empty here since failure is a
value (P-6); the count is kept and the traces are not.

The custom file enables the timeline and nothing else: collection with causes and phases, safepoint
operations including the time taken to reach one, compilation, and deoptimisation. Time to safepoint
needs naming on its own, since it produces latency outliers unrelated to collection and is routinely
missed by people who read the collection log and stop. `-Xlog:safepoint*` gives the same in text.

C++ has none of these events because it has none of these mechanisms. Its comparable record is
allocator behaviour, and an implementation that preallocates and never allocates in steady state has
nothing to report, which is what the comparison is about.

**Allocation and collection.** Separate phenomena, recorded separately. Java measures allocation as
bytes per command from the runtime's own accounting, with a control benchmark so fixture allocation is
not attributed to the measured path. An earlier version of this project reported 284 bytes per command
for an operation that allocates nothing, because the profiler counted the book warm-up against the
measured commands. Off-heap footprint comes from `-XX:NativeMemoryTracking`. Collection is a pause
distribution with causes, from `-Xlog:gc*` and the flight recording.

A zero allocation claim is proved rather than measured. Epsilon never collects, so an implementation
that allocates nothing in steady state runs to completion under it, and one that allocates dies.
Stronger than any counter, and it costs one flag. C++ uses heaptrack or massif, and the equivalent
proof is an allocator configured to fail after initialisation.

**Compiler behaviour.** Java uses `-XX:+PrintCompilation` for a timeline, `-XX:+PrintInlining` for
inlining decisions including the reason for each refusal, and `-XX:+LogCompilation` for a machine
readable record that JITWatch reads and maps to emitted assembly. A deoptimisation appears as a method
made not entrant with a reason, and those reasons are the regime change result. C++ uses the
optimisation report: `-fopt-info-vec-missed` and `-fopt-info-inline` on GCC, `-Rpass` and
`-Rpass-missed` on Clang. Both compilers are built and compared, since choosing one silently is a
choice nobody can check.

**Emitted code.** For headline claims only. Java needs hsdis for `-XX:+PrintAssembly`, scoped to one
method with a compile command, to answer whether a bounds check was hoisted, a loop unrolled, or
anything vectorised. C++ uses `objdump -d`, or `perf annotate` to see the same instructions with
samples attached. Code size from `size` and `nm --size-sort`, since instruction cache pressure is real
in a hot loop.

**Layout and footprint.** JOL for Java, reporting object layout, header overhead, field padding and
the footprint of a graph. `pahole` for C++, reporting struct layout, padding and cache line straddling
from the debug information. Bytes per resting order is a headline comparative figure in both and nearly
free to collect.

**Deterministic simulation.** Cachegrind simulates a cache hierarchy, so it answers which layout
touches more lines with no hardware noise, and callgrind counts instructions the same way. Both are
C++ only and slow enough to need reduced inputs, which determinism makes valid. They are a cross check
on the bracketed counters rather than the primary instrument, now that both languages can count their
own.

## Fairness between the languages

A flow that spends its time being refused measures the validation path, so the rejection rate is held
under a stated budget by a test rather than by attention (NFR-4.7).

One generator produces the input, and both languages replay the same encoded bytes from a file. A
generator written twice would put a difference between two flows inside a comparison of two engines,
and the difference would look like a finding.

The runtime compiles with a profile gathered at run time, so a Java number is profile guided by
construction. Comparing it against a C++ build without profile guided optimisation understates C++,
which is one of the few places this comparison could be quietly rigged. Headline comparisons use a
profile guided build and report the difference against a plain optimised one. Optimisation level, link
time optimisation, profile guidance, target architecture and compiler identity are recorded per run.

## What every run collects

A standard run carries only counters and produces the numbers that get reported:

- the manifest: run identity, commit, environment as verified, configuration including generator seed
  and flow parameters, and the command line
- the raw timings, four to a command, and the encoded histograms produced from them
- aggregate and bracketed counter output
- a verification record: event counts by type, and a checksum of the output stream
- environment samples before and after: the measured core's frequency, context switches and steal
  time, the package temperature, and the engine thread's own voluntary and involuntary switch counts,
  which the thread reads from its own status file because they belong to a thread rather than a core
- the hardware counters over the reported region, and whether the kernel had to multiplex them
- the ring buffer record: capacity, high water mark, and the stalls at each end
- where each thread was placed, read back from the kernel rather than taken from the request

Java runs add the collection log, the safepoint log, the compilation log and a flight recording. C++
runs add the optimisation report and the build flags.

The verification record is not optional. A benchmark that does not check its output will report a fast
wrong engine.

An investigation run is taken separately, on the same input and machine, when a measured difference
needs a mechanism. It adds a sampling profile and its flame graph, a second profile sampled on cache
misses, disassembly of the methods a claim names, a layout report, the simulators on a reduced input,
and allocation profiling.

## Where analysis happens

Collection is entirely command line, so all of it runs on a headless instance. Every viewer is not:
Mission Control, JITWatch, the heaptrack and callgrind viewers, and flame graphs in a browser.

A run writes its directory on the instance, the directory is pulled down whole, and analysis happens
locally against the artifacts. Nothing is inspected interactively on the box and no figure is produced
there, which keeps the instance disposable and every figure traceable to a stored artifact.

## Instrument cost

Every instrument perturbs, and the question to ask is whether it perturbs uniformly. Counters are close
to free. Bracketed reads cost tens of cycles each. Sampling profilers cost a few percent. Flight
recording costs more. Frame pointer preservation and non-safepoint debug information cost a little
continuously. The simulators cost one to two orders of magnitude.

Headline numbers come from a standard run. Everything heavier runs separately, and the analysis names
which run each figure came from.

## Threats to validity

Generated flow is not real flow, and it does not react to the book. What it does have is a measured
shape: the command mix, order size and placement depth come from a Nasdaq TotalView-ITCH session,
AAPL on 30 January 2020 between 09:30 and 11:08, read by `matching-calibration`. Before that they were
chosen, and they were wrong in ways that mattered: orders around twenty times too large and uniformly
distributed where a real book is nine tenths single lots, and a quarter of every run spent being
refused.

What a feed cannot show is what an order was. An iceberg, a stop, a post-only and a minimum quantity
are all invisible in market data, and an aggressive order that filled completely never rested and so
never appears at all. Those rates are chosen, the crossing share is fitted so that the fraction of
posted quantity which executes lands near the 5% the session showed, and the manifest records every one
of them. Findings are conditional on all of it.

One instrument, one book, one machine. Nothing here measures a multi-instrument deployment, cross
core publication, or contention, because the engine is single writer by construction.

The runtime's steady state under a benchmark may not be its steady state under real flow, where
the mix shifts through a session. The regime change result exists because of this, and it bounds
rather than removes the concern.

Both language implementations are written by the same person. That controls for algorithm and
familiarity and introduces a uniform skill ceiling. Where the two cannot be made structurally
equivalent, the difference is recorded: a virtual call in one language against a
devirtualised interface in the other is not a language finding.

Buffer bounds checking is on by default in the buffer library and off in a real deployment.
Every number names the setting it was taken under, and the difference between the two is reported as
a result.

## Reproducibility

Histograms are stored in their encoded form, never as summary percentiles. An encoded histogram can
be merged and re-quantiled, so a question asked next year about a run from today is answerable
without the machine that produced it. A stored percentile answers one question forever.

Every table and figure is generated from those directories by a script in the repository. None is
written by hand. A number in the write up is traceable to the run that produced it and to the
environment that run recorded.

## What is not claimed

Nothing here describes any real venue's engine. The protocol and the feature set follow published
specifications; the implementations are ours.

No implementation is claimed to be the fastest possible. Each is a point in a design space, and the
subject is the distance between points.

The language comparison holds at matched layouts and does not generalise to the languages. It says
what a runtime costs for this workload at this layout, on this machine.
