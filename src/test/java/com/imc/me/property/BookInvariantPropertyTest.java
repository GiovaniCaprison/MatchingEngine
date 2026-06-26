package com.imc.me.property;

import com.imc.me.support.Requirement;
import java.util.List;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

/**
 * For invariants with no literal oracle. jqwik generates thousands of random valid command streams
 * and, on failure, shrinks to the minimal reproducing sequence:
 *
 * <p>[BUY 100x5, SELL 100x5, CANCEL 1]
 */
@Tag("stress")
@Requirement({"NFR-3.1", "NFR-3.2", "VR-6.1", "NFR-6.1", "FR-5.3"})
class BookInvariantPropertyTest {

  @Property
  @Label("NFR-3.1 / VR-6.1 / NFR-6.1: depth always equals sum of resting qty")
  void depth_equals_sum_of_resting(
      @ForAll @Size(max = 200) List<@IntRange(min = 1, max = 50) Integer> qtys) {
    // TODO: replay a generated stream, then BookInvariants.assertAll(engine)
  }

  @Property
  @Label("NFR-3.2: no empty price levels and no orphaned orders remain")
  void no_leaked_levels(@ForAll @Size(max = 200) List<@IntRange(min = 1, max = 50) Integer> qtys) {
    // TODO: after a full random run, every level is non-empty and uid-map is consistent
  }

  @Property
  @Label("FR-5.3: depth aggregation matches per-level resting totals under load")
  void depth_aggregation_consistent(
      @ForAll @Size(max = 200) List<@IntRange(min = 1, max = 50) Integer> qtys) {
    // TODO: depth(side) levels reconcile with the raw resting orders
  }
}
