package com.imc.me.book;

public sealed interface OrderBook extends OrderBookReader, OrderBookWriter
    permits TreeMapOrderBook {}
