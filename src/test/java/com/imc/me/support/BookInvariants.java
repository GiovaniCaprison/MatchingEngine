package com.imc.me.support;

/**
 * Property/stress tests call this after every operation; satisfying NFR-6.2 ("internal invariants
 * are assertable") means the engine must expose enough read-only state for these checks.
 */
public final class BookInvariants {
  private BookInvariants() {}
  // TODO (Step 1): implement assertAll(engine).
}
