package com.imc.me.event.result;

public sealed interface CancelResult permits Cancelled, NotFound {}
