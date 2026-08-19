package com.imc.me.domain;

/**
 * Reference data for one tradable symbol.
 *
 * <p>{@code tickSize} and {@code lotSize} are the price and quantity granularities enforced at the
 * validation boundary (VR-2.2, VR-1.1). {@code priceScale} is the number of implied decimal places
 * in every price the engine handles: at scale 4, {@code 100.25} arrives as {@code 1002500}
 * (OOD-12). Converting to and from that representation is the caller's job.
 */
public record Instrument(
    int tickerId, String ticker, long tickSize, long lotSize, int priceScale) {}
