package io.github.giovanicaprison.matching.flow;

/**
 * The pseudorandom sequence a flow is drawn from, written out here rather than taken from a
 * library.
 *
 * <p>Every result carries the seed that produced its input, and a run from today has to be
 * reproducible in a year and in another language. That means the sequence has to be defined by this
 * project rather than by a library's stability promise, which is why it is nine lines of xorshift
 * and not {@code java.util.Random}.
 *
 * <p>The derived draws below are part of that definition. They avoid floating point so that a
 * second implementation cannot disagree by a rounding mode: a probability arrives as a fraction of
 * a million and stays an integer from there.
 */
final class Sequence {

  static final int SCALE = 1_000_000;

  private long state;

  Sequence(final long seed) {
    // Zero is the one state xorshift cannot leave, so a seed of zero is mapped rather than refused.
    state = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
  }

  /** xorshift64*, from Marsaglia by way of Vigna. */
  long next() {
    state ^= state >>> 12;
    state ^= state << 25;
    state ^= state >>> 27;
    return state * 0x2545F4914F6CDD1DL;
  }

  /**
   * Uniform in {@code [0, bound)}. The modulo bias is a part in ten million and does not matter.
   */
  int nextInt(final int bound) {
    return (int) Long.remainderUnsigned(next(), bound);
  }

  /** True with the given probability, expressed in parts per million. */
  boolean chance(final int perMillion) {
    return Long.remainderUnsigned(next(), SCALE) < perMillion;
  }

  /**
   * Uniform in {@code [0, bound)} twice over, keeping the smaller.
   *
   * <p>A book is denser near the touch than away from it, and this is the cheapest shape that is
   * denser near zero. It is an approximation and the flow parameters are reported with every
   * result, so a reader can see what was assumed.
   */
  int nearer(final int bound) {
    return Math.min(nextInt(bound), nextInt(bound));
  }

  static int perMillion(final double fraction) {
    if (fraction < 0 || fraction > 1) {
      throw new IllegalArgumentException("a fraction belongs in [0, 1]: " + fraction);
    }
    return (int) Math.round(fraction * SCALE);
  }
}
