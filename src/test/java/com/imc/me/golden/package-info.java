/**
 * The scenario corpus: a command sequence in, an ordered trade stream and the resulting book out.
 *
 * <p>The engine is a deterministic function from an input log to an output log, so most of its
 * behaviour is provable by diffing text. Fixtures live in {@code src/test/resources/scenarios} and
 * carry their requirement id in the filename, which is the whole of the traceability scheme.
 */
package com.imc.me.golden;
