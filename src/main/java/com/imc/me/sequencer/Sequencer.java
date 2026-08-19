package com.imc.me.sequencer;

/**
 * The single source of identity and order in the system (OOD-13).
 *
 * <p>Every order id and every outbound event number comes from here, from one monotonically
 * increasing counter. Nothing else in the engine may generate identity.
 *
 * <p><b>Why one counter and not several.</b> A single stream is a <i>total order over everything that
 * happened</i>. "Did A precede B?" becomes an integer comparison, replay is exact, and an audit trail
 * reconstructs without a clock. Two counters would each be locally monotonic but their interleaving
 * would not be recorded anywhere, so the one question you actually need answered — what happened
 * first — would become unanswerable from the data.
 *
 * <p><b>Why not a clock, a UUID, or a per-class counter.</b> Determinism (NFR-1) means the same input
 * sequence produces the same output sequence, bit for bit. A clock is unreproducible on replay,
 * randomness obviously so, and several counters are interleaving-dependent. This is also why time
 * priority (FR-3.2) needs no timestamp at all: arrival order <i>is</i> sequence order, and FIFO within
 * a price level encodes it structurally.
 *
 * <p><b>Not thread-safe, deliberately</b> (OOD-2). A plain {@code long++} rather than an {@code
 * AtomicLong}: an atomic increment is a locked instruction that serialises the pipeline and would be
 * pure cost for a counter only ever touched by the single writer. If two threads ever need to mint
 * ids, the answer is two books with two sequencers, not one shared counter — and an {@code AtomicLong}
 * here would hide that decision instead of forcing it.
 */
public final class Sequencer {

  private long sequence;

  /** Starts at zero, so the first value handed out is 1 and 0 is available as "never assigned". */
  public Sequencer() {
    this(0L);
  }

  /**
   * Resumes from a known point, for replay: seeding with the last sequence of a previous run makes
   * ids continue rather than collide.
   */
  public Sequencer(final long startingAt) {
    this.sequence = startingAt;
  }

  /** The next value in the stream. Never returns the same value twice, never returns 0. */
  public long next() {
    return ++sequence;
  }

  /**
   * The most recently issued value, without issuing one.
   *
   * <p>This is the engine's logical clock: two runs that agree here after the same input agree on
   * everything, which is what makes NFR-1.1/1.2 checkable as a single comparison.
   */
  public long current() {
    return sequence;
  }
}
