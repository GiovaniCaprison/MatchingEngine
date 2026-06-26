package com.imc.me.domain;

public record Instrument(
    int tickerId, String ticker, long tickSize, long lotSize, int priceScale) {}
