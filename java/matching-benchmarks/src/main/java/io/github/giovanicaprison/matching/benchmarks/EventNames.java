package io.github.giovanicaprison.matching.benchmarks;

import io.github.giovanicaprison.matching.protocol.AuctionIndicativeDecoder;
import io.github.giovanicaprison.matching.protocol.OrderAcceptedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderExecutedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderReducedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderRejectedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderRemovedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderRestedDecoder;
import io.github.giovanicaprison.matching.protocol.OrderTriggeredDecoder;
import io.github.giovanicaprison.matching.protocol.SessionStateChangedDecoder;
import java.util.Map;

/**
 * Template ids as the names a verification record reports.
 *
 * <p>The names come from the generated classes rather than from string literals, so renaming a
 * message in the schema renames it here too. What the schema cannot do for us is notice a new event
 * nobody added, and the document consistency gate reads this file for exactly that.
 */
final class EventNames {

  private static final Map<Integer, String> BY_TEMPLATE =
      Map.of(
          OrderAcceptedDecoder.TEMPLATE_ID, nameOf(OrderAcceptedDecoder.class),
          OrderRejectedDecoder.TEMPLATE_ID, nameOf(OrderRejectedDecoder.class),
          OrderRestedDecoder.TEMPLATE_ID, nameOf(OrderRestedDecoder.class),
          OrderExecutedDecoder.TEMPLATE_ID, nameOf(OrderExecutedDecoder.class),
          OrderReducedDecoder.TEMPLATE_ID, nameOf(OrderReducedDecoder.class),
          OrderRemovedDecoder.TEMPLATE_ID, nameOf(OrderRemovedDecoder.class),
          OrderTriggeredDecoder.TEMPLATE_ID, nameOf(OrderTriggeredDecoder.class),
          SessionStateChangedDecoder.TEMPLATE_ID, nameOf(SessionStateChangedDecoder.class),
          AuctionIndicativeDecoder.TEMPLATE_ID, nameOf(AuctionIndicativeDecoder.class));

  private EventNames() {}

  static Map<Integer, String> byTemplate() {
    return BY_TEMPLATE;
  }

  static String of(final int templateId) {
    final String name = BY_TEMPLATE.get(templateId);
    if (name == null) {
      throw new IllegalArgumentException("template " + templateId + " is not an event");
    }
    return name;
  }

  private static String nameOf(final Class<?> decoder) {
    return decoder.getSimpleName().replace("Decoder", "");
  }
}
