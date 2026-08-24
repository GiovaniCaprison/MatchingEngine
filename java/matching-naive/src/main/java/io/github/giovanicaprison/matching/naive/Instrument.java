package io.github.giovanicaprison.matching.naive;

import io.github.giovanicaprison.matching.protocol.AllocationAlgorithm;
import io.github.giovanicaprison.matching.protocol.InstrumentDefinitionDecoder;

/**
 * The instrument this engine handles, read once out of the definition command (FR-1.1).
 *
 * <p>Its fields are trusted. Reference data is validated by whatever owns it, several components
 * upstream, so a nonsensical tick size is a programming error there and not a refusal here.
 */
record Instrument(
    int id,
    long tickSize,
    long lotSize,
    long minPrice,
    long maxPrice,
    int priceScale,
    long bandWidth,
    long openingReference,
    AllocationAlgorithm allocation) {

  static Instrument of(final InstrumentDefinitionDecoder definition) {
    return new Instrument(
        (int) definition.frame().instrumentId(),
        definition.tickSize(),
        definition.lotSize(),
        definition.minPrice(),
        definition.maxPrice(),
        definition.priceScale(),
        definition.bandWidth(),
        definition.openingReference(),
        definition.allocation());
  }
}
