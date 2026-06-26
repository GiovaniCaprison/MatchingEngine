package com.imc.me.event.result;

public sealed interface AmendResult permits Accepted, Rejected, NotFound {}
