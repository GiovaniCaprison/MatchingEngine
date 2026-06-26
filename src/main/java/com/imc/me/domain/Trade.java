package com.imc.me.domain;

public record Trade(int symbolId, long aggressorId, long restingId, long price, long qty) {}
