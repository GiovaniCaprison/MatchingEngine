package io.github.giovanicaprison.matching.conformance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The mapping between what a fixture writes and what an engine chose.
 *
 * <p>A fixture refers to an order as {@code #n}, counting {@code NEW} directives from one, and that
 * number is the client order id the harness gives it. A command needs nothing else, because a
 * command names an order the way its sender does.
 *
 * <p>An event names it the way the engine does, and that id is never written down: asserting it
 * would test id allocation and would fail an implementation that numbers differently for a good
 * reason. So an event is rendered back through the id reported on acceptance. Execution ids work
 * the same way, as {@code @n} for the nth distinct one in the stream.
 */
final class References {

  private final Map<Long, Integer> referenceByOrderId = new LinkedHashMap<>();
  private final Map<Integer, Integer> participantByReference = new LinkedHashMap<>();
  private final Map<Long, Integer> executionOrdinals = new LinkedHashMap<>();

  void declare(final int reference, final int participant) {
    participantByReference.put(reference, participant);
  }

  void bind(final int reference, final long orderId) {
    referenceByOrderId.put(orderId, reference);
  }

  int participant(final int reference) {
    return participantByReference.getOrDefault(reference, 0);
  }

  /** How an order id is written in output, falling back to the raw id so a diff stays readable. */
  String render(final long orderId) {
    final Integer reference = referenceByOrderId.get(orderId);
    return reference == null ? "id=" + orderId : "#" + reference;
  }

  String renderExecution(final long executionId) {
    return "@" + executionOrdinals.computeIfAbsent(executionId, id -> executionOrdinals.size() + 1);
  }
}
