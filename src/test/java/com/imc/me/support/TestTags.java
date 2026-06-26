package com.imc.me.support;

/**
 * JUnit Platform tag names = the CI "lanes". A test's physical package says what kind it is; a tag
 * says how expensive it is, which is what I want CI to filter on.
 *
 * <ul>
 *   <li>FAST - millisecond example + structural tests. Run on every push.
 *   <li>GOLDEN- snapshot scenarios. Isolated so a diff is reviewed deliberately before re-blessing.
 *   <li>STRESS- randomized property tests (thousands of cases).
 * </ul>
 */
public final class TestTags {
  private TestTags() {}

  public static final String FAST = "fast";
  public static final String GOLDEN = "golden";
  public static final String STRESS = "stress";
}
