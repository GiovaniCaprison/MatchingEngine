package com.imc.me.event.sink;

import com.imc.me.book.DepthSink;
import com.imc.me.event.dto.Depth;
import com.imc.me.util.Seq;

/**
 * The edge adapter for depth: turns the book's primitive level callbacks into immutable {@link
 * Depth.Level} values for a query response.
 *
 * <p>Same rationale as {@link CollectingTradeSink}. The core emits primitives and the edge
 * materialises objects only when something is about to serialise or assert on them (OOD-3, OOD-9).
 */
public final class CollectingDepthSink implements DepthSink {

  private final Seq.Builder<Depth.Level> levels;
  private Seq<Depth.Level> built;

  public CollectingDepthSink() {
    this.levels = Seq.builder();
  }

  public CollectingDepthSink(final int expectedLevels) {
    this.levels = Seq.builder(expectedLevels);
  }

  @Override
  public boolean onLevel(final long price, final long qty) {
    levels.add(new Depth.Level(price, qty));
    return true;
  }

  /** The collected levels, best price first. Memoised, so it is safe to read more than once. */
  public Seq<Depth.Level> levels() {
    if (built == null) built = levels.build();
    return built;
  }
}
