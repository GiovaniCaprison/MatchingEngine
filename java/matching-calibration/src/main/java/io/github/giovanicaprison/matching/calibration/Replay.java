package io.github.giovanicaprison.matching.calibration;

import io.github.giovanicaprison.matching.flow.CommandLog;
import io.github.giovanicaprison.matching.protocol.AllocationAlgorithm;
import io.github.giovanicaprison.matching.protocol.CancelOrderEncoder;
import io.github.giovanicaprison.matching.protocol.InstrumentDefinitionEncoder;
import io.github.giovanicaprison.matching.protocol.MessageHeaderEncoder;
import io.github.giovanicaprison.matching.protocol.NewOrderEncoder;
import io.github.giovanicaprison.matching.protocol.PricingInstruction;
import io.github.giovanicaprison.matching.protocol.ReplaceOrderEncoder;
import io.github.giovanicaprison.matching.protocol.SessionState;
import io.github.giovanicaprison.matching.protocol.SessionStateChangeEncoder;
import io.github.giovanicaprison.matching.protocol.Side;
import io.github.giovanicaprison.matching.protocol.TimeInForce;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Turns one instrument's ITCH session into the command log this project's engines eat.
 *
 * <p>The generator's flow has a measured shape; this is the measured thing itself. A feed is not a
 * command stream, so the conversion is a reconstruction with its approximations stated rather than
 * glossed:
 *
 * <ul>
 *   <li>An add becomes a limit order, named by ITCH's own order reference, from participant one.
 *   <li>An execution names only the resting order, because the aggressor that caused it never
 *       appears in a feed if it never rested. One immediate-or-cancel limit order at the resting
 *       order's price is synthesized per execution, from participant two so nothing collides. Our
 *       engine's allocation decides who it hits, which is the point: this is real flow driving our
 *       book, and no attempt is made to reproduce Nasdaq's book order for order.
 *   <li>A partial cancel becomes a replace at the same price, which keeps queue position on both
 *       venues. A replace becomes a replace; ITCH re-queues one that only lowered quantity at the
 *       same price and this engine keeps its place, which is a stated difference (FR-4.4).
 *   <li>Hidden trades and crosses have no order to hit and are skipped and counted. So is every
 *       message type a flow is not made of.
 * </ul>
 *
 * <p>Everything before the measurement window converts too, and the measured-from marker lands on
 * the first command inside it: the pre-open hours are the warm-up that builds a real book, exactly
 * as the generator's warm-up builds a synthetic one.
 */
public final class Replay {

  private static final int REAL = 1;
  private static final int SYNTHESIZED = 2;
  private static final long WIDE_OPEN = 1_000_000_000_000L;

  private final MutableDirectBuffer out = new ExpandableArrayBuffer(1 << 26);
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final InstrumentDefinitionEncoder definition = new InstrumentDefinitionEncoder();
  private final SessionStateChangeEncoder session = new SessionStateChangeEncoder();
  private final NewOrderEncoder newOrder = new NewOrderEncoder();
  private final CancelOrderEncoder cancel = new CancelOrderEncoder();
  private final ReplaceOrderEncoder replace = new ReplaceOrderEncoder();

  private final List<Integer> offsets = new ArrayList<>();
  private final List<Integer> lengths = new ArrayList<>();

  /** What this converter believes each live order has left and has done, keyed by ITCH ref. */
  private final Map<Long, long[]> live = new HashMap<>();

  /** A replace chain keeps our client order id, so later references find the original name. */
  private final Map<Long, Long> names = new HashMap<>();

  private final String stock;
  private final long limit;
  private final long from;
  private final long tick;
  private final long lot;

  private int locate = -1;
  private long read;
  private int at;
  private long sequence;
  private long aggressors;
  private int measuredFrom = -1;
  private boolean defined;
  private long skipped;
  private long orphaned;

  Replay(final String stock, final long limit, final long from, final long tick, final long lot) {
    this.stock = stock;
    this.limit = limit;
    this.from = from;
    this.tick = tick;
    this.lot = lot;
  }

  public static void main(final String[] arguments) throws IOException {
    String stock = "AAPL";
    long limit = Long.MAX_VALUE;
    long from = 9 * 3600 + 30 * 60;
    long tick = 100;
    long lot = 1;
    Path log = Path.of("session.log");
    for (int index = 0; index + 1 < arguments.length; index += 2) {
      switch (arguments[index]) {
        case "--stock" -> stock = arguments[index + 1];
        case "--messages" -> limit = Long.parseLong(arguments[index + 1]);
        case "--from" -> from = Long.parseLong(arguments[index + 1]);
        case "--tick" -> tick = Long.parseLong(arguments[index + 1]);
        case "--lot" -> lot = Long.parseLong(arguments[index + 1]);
        case "--log" -> log = Path.of(arguments[index + 1]);
        default -> throw new IllegalArgumentException(arguments[index] + " is not an argument");
      }
    }
    final Replay replay = new Replay(stock, limit, from, tick, lot);
    final CommandLog converted = replay.convert(new BufferedInputStream(System.in, 1 << 20));
    converted.writeTo(log);
    System.err.printf(
        "%,d commands (%,d synthesized aggressors), measured from command %,d%n",
        converted.count(), replay.aggressors, converted.measuredFrom());
    System.err.printf(
        "%,d feed messages skipped as unconvertible, %,d referencing orders never seen%n",
        replay.skipped, replay.orphaned);
    System.err.println("wrote " + log);
  }

  CommandLog convert(final InputStream stream) throws IOException {
    final Itch itch = new Itch(stream);
    Itch.Message message;
    while ((message = itch.next()) != null && read < limit) {
      read++;
      if (message.type == 'R') {
        if (stock.equals(message.stock)) {
          locate = message.stockLocate;
        }
        continue;
      }
      if (message.stockLocate != locate) {
        continue;
      }
      if (measuredFrom < 0 && message.timestamp / 1_000_000_000L >= from) {
        measuredFrom = offsets.size();
      }
      apply(message);
    }
    if (offsets.isEmpty()) {
      throw new IllegalStateException("nothing for " + stock + " in this feed");
    }
    final int count = offsets.size();
    final int[] offsetArray = new int[count];
    final int[] lengthArray = new int[count];
    for (int index = 0; index < count; index++) {
      offsetArray[index] = offsets.get(index);
      lengthArray[index] = lengths.get(index);
    }
    return new CommandLog(
        out, offsetArray, lengthArray, count, measuredFrom < 0 ? 0 : measuredFrom);
  }

  private void apply(final Itch.Message message) {
    switch (message.type) {
      case 'A', 'F' -> add(message);
      case 'E', 'C' -> execution(message);
      case 'X' -> partialCancel(message);
      case 'D' -> delete(message);
      case 'U' -> replace(message);
      default -> skipped++;
    }
  }

  private void add(final Itch.Message message) {
    if (!defined) {
      define(message.price);
      open();
      defined = true;
    }
    live.put(message.orderReference, new long[] {message.price, message.shares, 0, message.side});
    names.put(message.orderReference, message.orderReference);
    newOrder.wrapAndApplyHeader(out, at, header);
    newOrder.frame().instrumentId(1).sequence(++sequence);
    newOrder
        .clientOrderId(message.orderReference)
        .participantId(REAL)
        .side(message.side == 'B' ? Side.BUY : Side.SELL)
        .pricing(PricingInstruction.LIMIT)
        .timeInForce(TimeInForce.GOOD_TILL_CANCEL)
        .price(message.price)
        .quantity(message.shares)
        .minQuantity(0)
        .displayQuantity(0)
        .triggerPrice(0)
        .smpId(0);
    newOrder.flags().clear().postOnly(false);
    complete(newOrder.encodedLength());
  }

  /** The order the feed never shows: whoever caused this execution, as one IOC at that price. */
  private void execution(final Itch.Message message) {
    final long[] order = live.get(message.orderReference);
    if (order == null) {
      orphaned++;
      return;
    }
    newOrder.wrapAndApplyHeader(out, at, header);
    newOrder.frame().instrumentId(1).sequence(++sequence);
    newOrder
        .clientOrderId(++aggressors)
        .participantId(SYNTHESIZED)
        .side(order[3] == 'B' ? Side.SELL : Side.BUY)
        .pricing(PricingInstruction.LIMIT)
        .timeInForce(TimeInForce.IMMEDIATE_OR_CANCEL)
        .price(order[0])
        .quantity(message.shares)
        .minQuantity(0)
        .displayQuantity(0)
        .triggerPrice(0)
        .smpId(0);
    newOrder.flags().clear().postOnly(false);
    complete(newOrder.encodedLength());
    order[1] -= message.shares;
    order[2] += message.shares;
    if (order[1] <= 0) {
      live.remove(message.orderReference);
    }
  }

  /** A reduction that keeps queue position, which is a same-price replace here (FR-4.4). */
  private void partialCancel(final Itch.Message message) {
    final long[] order = live.get(message.orderReference);
    if (order == null) {
      orphaned++;
      return;
    }
    order[1] -= message.shares;
    replaceCommand(message.orderReference, order[1] + order[2], order[0]);
    if (order[1] <= 0) {
      live.remove(message.orderReference);
    }
  }

  private void delete(final Itch.Message message) {
    final Long name = names.get(message.orderReference);
    if (live.remove(message.orderReference) == null || name == null) {
      orphaned++;
      return;
    }
    cancel.wrapAndApplyHeader(out, at, header);
    cancel.frame().instrumentId(1).sequence(++sequence);
    cancel.clientOrderId(name).participantId(REAL);
    complete(cancel.encodedLength());
  }

  /** A new price or quantity under the same name, since a replace chain keeps its id (FR-4.8). */
  private void replace(final Itch.Message message) {
    final long[] order = live.remove(message.orderReference);
    final Long name = names.get(message.orderReference);
    if (order == null || name == null) {
      orphaned++;
      return;
    }
    names.put(message.newOrderReference, name);
    live.put(
        message.newOrderReference, new long[] {message.price, message.shares, order[2], order[3]});
    replaceCommand(name, message.shares + order[2], message.price);
  }

  private void replaceCommand(final long reference, final long quantity, final long price) {
    final Long name = names.get(reference);
    replace.wrapAndApplyHeader(out, at, header);
    replace.frame().instrumentId(1).sequence(++sequence);
    replace
        .clientOrderId(name == null ? reference : name)
        .participantId(REAL)
        .quantity(quantity)
        .price(price);
    complete(replace.encodedLength());
  }

  /**
   * An instrument wide open on purpose. Banding refuses what a real venue would have refused
   * already, and this feed is what a real venue accepted, so the bands are set where they cannot
   * second-guess it.
   */
  private void define(final long firstPrice) {
    definition.wrapAndApplyHeader(out, at, header);
    definition.frame().instrumentId(1).sequence(++sequence);
    definition
        .tickSize(tick)
        .lotSize(lot)
        .minPrice(tick)
        .maxPrice(WIDE_OPEN)
        .priceScale((short) 4)
        .bandWidth(WIDE_OPEN)
        .openingReference(firstPrice)
        .allocation(AllocationAlgorithm.PRICE_TIME);
    complete(definition.encodedLength());
  }

  private void open() {
    session.wrapAndApplyHeader(out, at, header);
    session.frame().instrumentId(1).sequence(++sequence);
    session.state(SessionState.CONTINUOUS);
    complete(session.encodedLength());
  }

  private void complete(final int encodedLength) {
    final int length = MessageHeaderEncoder.ENCODED_LENGTH + encodedLength;
    offsets.add(at);
    lengths.add(length);
    at += length;
  }
}
