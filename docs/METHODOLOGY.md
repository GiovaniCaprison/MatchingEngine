# Methodology

How performance is measured and what the measurements are for. `TESTING.md` covers correctness; the
two share a flow generator and nothing else.

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

Does a structural advantage survive a production feature set. This is the primary question and its
answer is not predictable. A flat price ladder's advantage may shrink once a trigger book must be
consulted after every execution and iceberg replenishment forces indirection the layout cannot
remove.

A fifth result comes from the same harness and is the most relevant to a production operator. An
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
reading, no allocation and no generation happens inside the measured path, and the event sink used
during measurement counts and discards. The harness never appears in a number.

## Steady state and book state

Two kinds of warm-up matter and they are separate.

The runtime reaches steady state when compilation has settled. Measurement begins after that, and the
compilation log is retained so the claim can be checked.

The book reaches its intended size before the measured batch, and the batch is sized so the book
does not drift materially during it. A batch comparable to the book size measures an average over a
moving structure. Where drift is unavoidable it is reported with the number.

## Environment

Runs are taken on a bare metal instance, which is what gives access to hardware performance counters
and removes hypervisor scheduling from the measurement. A shared instance provides neither.

The measured thread is pinned to an isolated core with its hyperthread sibling offline. Frequency is
fixed rather than left to scale, the governor is set for performance, huge pages are configured
deliberately, and the machine is otherwise quiet.

Every one of those is a recorded field, alongside the kernel, the processor
model, the runtime build, the collector and heap settings, the compiler and its flags, and the
instance identity. A different physical host is a variable, not an implementation detail.

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

## Profiling

Profiling follows a measured difference and never substitutes for one. A finding is a difference plus
a mechanism; a difference alone is an observation.

Counters first: cycles, instructions, cache misses at each level, branch mispredictions, and
instructions per cycle, from the hardware. Then allocation, in bytes per command, with a control
benchmark so that fixture allocation is not attributed to the measured path. Then compilation and
deoptimisation logs, which is where the regime change result comes from. Sampling profiles last, for
attribution to a call site.

## Threats to validity

Generated flow is not real flow. The generator produces plausible arrival, placement and cancellation
behaviour, and it does not react to the book. Findings are conditional on the flow parameters, which
every result carries.

One instrument, one book, one machine. Nothing here measures a multi-instrument deployment, cross
core publication, or contention, because the engine is single writer by construction.

The runtime's steady state under a benchmark may not be its steady state in production, where the
flow mix shifts through a session. The regime change result exists because of this, and it bounds
rather than removes the concern.

Both language implementations are written by the same person. That controls for algorithm and
familiarity and introduces a uniform skill ceiling. Where the two cannot be made structurally
equivalent, the difference is recorded: a virtual call in one language against a
devirtualised interface in the other is not a language finding.

Buffer bounds checking is on by default in the buffer library and off in a production deployment.
Every number names the setting it was taken under, and the difference between the two is reported as
a result.

## Reproducibility

Each run writes a directory containing its manifest, its raw histograms and its counter output.

The manifest records the run identity and time, the commit and whether the tree was modified, the
full environment, the configuration including the generator seed and flow parameters, and the command
line that produced it.

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
