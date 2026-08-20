package io.github.giovanicaprison.matching.api;

/**
 * Reference data for the one instrument an engine handles.
 *
 * <p>Configuration rather than wire data, which is why it is a Java type and not a message. It
 * arrives once at startup and never changes for the life of the engine.
 *
 * <p>Prices and quantities are scaled integers throughout (P-11). {@code priceScale} is the number
 * of implied decimal places, so at scale four a price of 100.25 arrives as 1002500. Converting to
 * and from that representation happens at the outermost edge of the system, several components away
 * from here.
 *
 * @param instrumentId the id every command and event for this instrument carries
 * @param tickSize the price granularity. A price off it is refused rather than rounded (VR-2.2)
 * @param lotSize the quantity granularity. A quantity off it cannot settle (VR-1.2)
 * @param minPrice lowest acceptable price, inclusive (VR-2.3)
 * @param maxPrice highest acceptable price, inclusive (VR-2.3)
 * @param priceScale implied decimal places in every price
 */
public record Instrument(
    int instrumentId,
    long tickSize,
    long lotSize,
    long minPrice,
    long maxPrice,
    int priceScale) {

  /**
   * Checked here because this is startup configuration rather than the hot path, and a nonsensical
   * instrument should fail loudly at construction rather than produce a book nobody can explain.
   * Contrast P-14, which is about a method called millions of times a second.
   */
  public Instrument {
    if (tickSize <= 0) throw new IllegalArgumentException("tickSize must be positive: " + tickSize);
    if (lotSize <= 0) throw new IllegalArgumentException("lotSize must be positive: " + lotSize);
    if (minPrice <= 0) throw new IllegalArgumentException("minPrice must be positive: " + minPrice);
    if (maxPrice < minPrice) {
      throw new IllegalArgumentException("maxPrice " + maxPrice + " below minPrice " + minPrice);
    }
    if (priceScale < 0) throw new IllegalArgumentException("priceScale must not be negative");
  }
}
