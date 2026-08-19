package com.imc.me.sequencer;

/**
 * The single source of identity and order in the system (OOD-13). Every order id and every outbound
 * event number comes from here, from one monotonically increasing counter, and nothing else in the
 * engine may generate identity.
 *
 * <p>One counter rather than several because a single stream is a total order over everything that
 * happened. "Did A precede B?" becomes an integer comparison. Two counters would each be locally
 * monotonic while their interleaving went unrecorded, so the question you actually need answered
 * would not be answerable from the data.
 *
 * <p>A plain {@code long++} rather than an {@code AtomicLong}, deliberately (OOD-2). An atomic
 * increment is a locked instruction that serialises the pipeline, and it would be pure cost for a
 * counter only ever touched by the single writer. If two threads ever need to mint ids the answer
 * is two books with two sequencers, and an {@code AtomicLong} here would hide that decision rather
 * than force it.
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
   * The most recently issued value, without issuing one. This is the engine's logical clock: two
   * runs that agree here after the same input agree on everything, which is what makes NFR-1.1 and
   * NFR-1.2 checkable as a single comparison.
   */
  public long current() {
    return sequence;
  }
}
