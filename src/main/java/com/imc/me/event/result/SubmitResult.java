package com.imc.me.event.result;

/** The edge's answer to a submission: accepted, or refused with a reason (API-9.1). */
public sealed interface SubmitResult permits Accepted, Rejected {}
