package io.github.giovanicaprison.matching.api;

import org.agrona.DirectBuffer;

/**
 * A matching engine: an ordered stream of commands in, an ordered stream of events out.
 *
 * <p>This is the whole interface, and everything it leaves out is deliberate. It says nothing about
 * how a command is read or how an event is written, because that is the thing being compared. An
 * implementation may copy each command into fresh objects, or read fields in place out of the
 * buffer, and both are designs real systems ship. Making either the interface's business would
 * decide the study in advance.
 *
 * <p>Decoding a command is therefore part of an implementation and part of its cost, as is encoding
 * an event. Neither can be factored out of a measurement without measuring something nobody runs.
 *
 * <p>The engine is a function of its input (P-1). Given the same commands in the same order it
 * produces the same events, byte for byte, which is what makes two implementations comparable and a
 * replay exact.
 *
 * <p>One instrument per instance, one thread per instance (P-2). Nothing here is thread safe and
 * nothing should be: concurrency comes from partitioning instruments across engines.
 */
public interface MatchingEngine {

  /**
   * Applies one command and emits whatever it caused into the sink this engine was built with.
   *
   * <p>Called on the single writer thread, in the order the commands were sequenced upstream. The
   * engine does not impose that order and never generates an input sequence of its own.
   *
   * <p>Precondition: the slice holds exactly one framed message (P-14). A malformed message is a
   * programming error in whatever framed it rather than a rejection, since a refusal is a business
   * outcome and this is not.
   *
   * @param buffer the buffer holding the message
   * @param offset where the message starts
   * @param length how many bytes it occupies
   */
  void onCommand(DirectBuffer buffer, int offset, int length);
}
