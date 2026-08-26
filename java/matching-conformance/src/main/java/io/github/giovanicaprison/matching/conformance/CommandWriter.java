package io.github.giovanicaprison.matching.conformance;

import io.github.giovanicaprison.matching.protocol.AllocationAlgorithm;
import io.github.giovanicaprison.matching.protocol.CancelOrderEncoder;
import io.github.giovanicaprison.matching.protocol.InstrumentDefinitionEncoder;
import io.github.giovanicaprison.matching.protocol.MassCancelEncoder;
import io.github.giovanicaprison.matching.protocol.MessageHeaderEncoder;
import io.github.giovanicaprison.matching.protocol.NewOrderEncoder;
import io.github.giovanicaprison.matching.protocol.PricingInstruction;
import io.github.giovanicaprison.matching.protocol.ReplaceOrderEncoder;
import io.github.giovanicaprison.matching.protocol.SessionState;
import io.github.giovanicaprison.matching.protocol.SessionStateChangeEncoder;
import io.github.giovanicaprison.matching.protocol.Side;
import io.github.giovanicaprison.matching.protocol.TimeInForce;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Turns a fixture command into the bytes an engine receives.
 *
 * <p>An order reference is the client order id the harness gave that order, so a cancel or a
 * replace needs nothing an engine has to report first. That is what makes a fixture replayable
 * against any implementation without knowing how it numbers anything.
 *
 * <p>The instrument id and the input sequence are the harness's, not the fixture's. Nothing a
 * fixture writes decides them, which is the arrangement the engine always has: input arrives
 * already sequenced.
 *
 * <p>A refusal is rendered by the client order id, so {@code REJECTED #1 UNKNOWN_ORDER} names the
 * order the command meant rather than the position in the file it occupied.
 */
final class CommandWriter {

  private static final int INSTRUMENT_ID = 1;

  /** Fixtures write the time in force short. Four names, so they are written out here. */
  private static final Map<String, TimeInForce> TIME_IN_FORCE =
      Map.of(
          "GTC", TimeInForce.GOOD_TILL_CANCEL,
          "DAY", TimeInForce.DAY,
          "IOC", TimeInForce.IMMEDIATE_OR_CANCEL,
          "FOK", TimeInForce.FILL_OR_KILL);

  private static final String ABSENT = "-";
  private static final int DEFAULT_PARTICIPANT = 1;

  private final MutableDirectBuffer buffer = new ExpandableArrayBuffer(256);
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final InstrumentDefinitionEncoder instrument = new InstrumentDefinitionEncoder();
  private final SessionStateChangeEncoder session = new SessionStateChangeEncoder();
  private final NewOrderEncoder newOrder = new NewOrderEncoder();
  private final CancelOrderEncoder cancel = new CancelOrderEncoder();
  private final ReplaceOrderEncoder replace = new ReplaceOrderEncoder();
  private final MassCancelEncoder massCancel = new MassCancelEncoder();

  private final References references;
  private long sequence;
  private int orders;

  CommandWriter(final References references) {
    this.references = references;
  }

  MutableDirectBuffer buffer() {
    return buffer;
  }

  /** Encodes one command at offset zero and returns its length in bytes. */
  int write(final Fixture.Command command) {
    sequence++;
    return switch (command.directive()) {
      case INSTRUMENT -> instrument(command.arguments());
      case SESSION -> session(command.arguments());
      case NEW -> newOrder(command.arguments());
      case CANCEL -> cancel(command.arguments());
      case REPLACE -> replace(command.arguments());
      case MASSCANCEL -> massCancel(command.arguments());
    };
  }

  private int instrument(final List<String> arguments) {
    final Map<String, String> options = options(arguments);
    instrument.wrapAndApplyHeader(buffer, 0, header);
    instrument.frame().instrumentId(INSTRUMENT_ID).sequence(sequence);
    instrument
        .tickSize(number(options, "tick"))
        .lotSize(number(options, "lot"))
        .minPrice(number(options, "min"))
        .maxPrice(number(options, "max"))
        .priceScale((short) number(options, "scale"))
        .bandWidth(number(options, "band"))
        .openingReference(number(options, "open"))
        .allocation(AllocationAlgorithm.valueOf(word(options, "alloc")));
    return length(instrument.encodedLength());
  }

  private int session(final List<String> arguments) {
    session.wrapAndApplyHeader(buffer, 0, header);
    session.frame().instrumentId(INSTRUMENT_ID).sequence(sequence);
    session.state(SessionState.valueOf(arguments.getFirst()));
    return length(session.encodedLength());
  }

  private int newOrder(final List<String> arguments) {
    final Map<String, String> options = options(arguments.subList(5, arguments.size()));
    final int reference = ++orders;
    final int participant = (int) number(options, "p", DEFAULT_PARTICIPANT);
    references.declare(reference, participant);

    newOrder.wrapAndApplyHeader(buffer, 0, header);
    newOrder.frame().instrumentId(INSTRUMENT_ID).sequence(sequence);
    newOrder
        .clientOrderId(reference)
        .participantId(participant)
        .side(Side.valueOf(arguments.get(0)))
        .pricing(PricingInstruction.valueOf(arguments.get(1)))
        .timeInForce(timeInForce(arguments.get(2)))
        .price(price(arguments.get(3)))
        .quantity(Long.parseLong(arguments.get(4)))
        .minQuantity(number(options, "min", 0))
        .displayQuantity(number(options, "display", 0))
        .triggerPrice(number(options, "trigger", 0))
        .smpId(number(options, "smp", 0));
    newOrder.flags().clear().postOnly(arguments.contains("POST_ONLY"));
    return length(newOrder.encodedLength());
  }

  private int cancel(final List<String> arguments) {
    final int reference = reference(arguments.getFirst());
    final Map<String, String> options = options(arguments.subList(1, arguments.size()));
    cancel.wrapAndApplyHeader(buffer, 0, header);
    cancel.frame().instrumentId(INSTRUMENT_ID).sequence(sequence);
    cancel
        .clientOrderId(reference)
        .participantId(number(options, "p", references.participant(reference)));
    return length(cancel.encodedLength());
  }

  private int replace(final List<String> arguments) {
    final int reference = reference(arguments.getFirst());
    replace.wrapAndApplyHeader(buffer, 0, header);
    replace.frame().instrumentId(INSTRUMENT_ID).sequence(sequence);
    replace
        .clientOrderId(reference)
        .participantId(references.participant(reference))
        .quantity(Long.parseLong(arguments.get(1)))
        .price(price(arguments.get(2)));
    return length(replace.encodedLength());
  }

  private int massCancel(final List<String> arguments) {
    final Map<String, String> options = options(arguments);
    massCancel.wrapAndApplyHeader(buffer, 0, header);
    massCancel.frame().instrumentId(INSTRUMENT_ID).sequence(sequence);
    massCancel.clientOrderId(0).participantId(number(options, "p"));
    return length(massCancel.encodedLength());
  }

  private static TimeInForce timeInForce(final String word) {
    final TimeInForce timeInForce = TIME_IN_FORCE.get(word);
    if (timeInForce == null) {
      throw new IllegalArgumentException("unknown time in force " + word);
    }
    return timeInForce;
  }

  private static long price(final String word) {
    return ABSENT.equals(word) ? 0 : Long.parseLong(word);
  }

  private static int reference(final String word) {
    if (!word.startsWith("#")) {
      throw new IllegalArgumentException("expected an order reference, got " + word);
    }
    return Integer.parseInt(word.substring(1));
  }

  private static Map<String, String> options(final List<String> words) {
    final Map<String, String> options = new HashMap<>();
    for (final String word : words) {
      final int equals = word.indexOf('=');
      if (equals > 0) {
        options.put(word.substring(0, equals), word.substring(equals + 1));
      }
    }
    return options;
  }

  private static long number(final Map<String, String> options, final String key) {
    final String value = options.get(key);
    if (value == null) {
      throw new IllegalArgumentException("missing " + key);
    }
    return Long.parseLong(value);
  }

  private static long number(
      final Map<String, String> options, final String key, final long fallback) {
    final String value = options.get(key);
    return value == null ? fallback : Long.parseLong(value);
  }

  private static String word(final Map<String, String> options, final String key) {
    final String value = options.get(key);
    if (value == null) {
      throw new IllegalArgumentException("missing " + key);
    }
    return value;
  }

  private int length(final int encodedLength) {
    return MessageHeaderEncoder.ENCODED_LENGTH + encodedLength;
  }
}
