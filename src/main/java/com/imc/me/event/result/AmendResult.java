package com.imc.me.event.result;

/** The edge's answer to an amend. See {@link AmendOutcome} for what happens to queue priority. */
public sealed interface AmendResult permits Accepted, Rejected, NotFound {}
