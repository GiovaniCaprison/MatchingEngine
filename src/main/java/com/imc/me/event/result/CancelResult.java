package com.imc.me.event.result;

/** The edge's answer to a cancel. Not-found is a value here rather than an exception (API-2.1). */
public sealed interface CancelResult permits Cancelled, NotFound {}
