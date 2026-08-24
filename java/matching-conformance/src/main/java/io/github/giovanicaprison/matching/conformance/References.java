package io.github.giovanicaprison.matching.conformance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The mapping between what a fixture writes and what an engine chose.
 *
 * <p>A fixture refers to an order as {@code #n}, counting {@code NEW} directives from one. Engine
 * order ids are never written down, because asserting them would test id allocation and would fail
 * an implementation that numbers differently for a good reason. Execution ids are handled the same
 * way, as {@code @n} for the nth distinct one in the stream.
 */
final class References {

  private final Map<Integer, Long> orderIdByReference = new LinkedHashMap<>();
  private final Map<Long, Integer> referenceByOrderId = new LinkedHashMap<>();
  private final Map<Integer, Integer> participantByReference = new LinkedHashMap<>();
  private final Map<Long, Integer> executionOrdinals = new LinkedHashMap<>();

  void declare(final int reference, final int participant) {
    participantByReference.put(reference, participant);
  }

  void bind(final int reference, final long orderId) {
    orderIdByReference.put(reference, orderId);
    referenceByOrderId.put(orderId, reference);
  }

  /**
   * The engine id behind a reference.
   *
   * <p>Unbound means the fixture is cancelling or replacing an order the engine never accepted.
   * There is no id to send, so the fixture is wrong rather than interesting: a command against an
   * unknown order is a unit test, where the id can be chosen deliberately.
   */
  long orderId(final int reference) {
    final Long orderId = orderIdByReference.get(reference);
    if (orderId == null) {
      throw new IllegalStateException("#" + reference + " was never accepted, so it has no id");
    }
    return orderId;
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
