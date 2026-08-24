package io.github.giovanicaprison.matching.flow;

import io.github.giovanicaprison.matching.flow.FlowParameters.Composition;

/**
 * A composition as integer thresholds, converted once.
 *
 * <p>Fractions are readable in the parameters and floating point is a way for two implementations
 * to disagree, so the conversion happens here and the draw is integer arithmetic from then on.
 */
record Thresholds(
    int aggressive,
    int market,
    int cancel,
    int replace,
    int massCancel,
    int iceberg,
    int stop,
    int immediateOrCancel,
    int fillOrKill,
    int postOnly,
    int minimumQuantity,
    int selfMatch) {

  static Thresholds of(final Composition composition) {
    final double claimed =
        composition.cancel()
            + composition.replace()
            + composition.massCancel()
            + composition.market()
            + composition.aggressive();
    if (claimed >= 1) {
      throw new IllegalArgumentException(
          "the composition leaves no room for a passive order: " + claimed);
    }
    return new Thresholds(
        Sequence.perMillion(composition.aggressive()),
        Sequence.perMillion(composition.market()),
        Sequence.perMillion(composition.cancel()),
        Sequence.perMillion(composition.replace()),
        Sequence.perMillion(composition.massCancel()),
        Sequence.perMillion(composition.iceberg()),
        Sequence.perMillion(composition.stop()),
        Sequence.perMillion(composition.immediateOrCancel()),
        Sequence.perMillion(composition.fillOrKill()),
        Sequence.perMillion(composition.postOnly()),
        Sequence.perMillion(composition.minimumQuantity()),
        Sequence.perMillion(composition.selfMatch()));
  }
}
