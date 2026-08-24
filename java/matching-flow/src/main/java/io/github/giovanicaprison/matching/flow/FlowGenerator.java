package io.github.giovanicaprison.matching.flow;

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
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Builds a command log from a seed and a set of parameters.
 *
 * <p>The generator does not react to the book. It knows which orders it has placed and which it has
 * asked to remove, and nothing about what executed, so some of its cancels arrive after the order
 * they name has traded. That is a command a real venue receives, and the rejections it causes are
 * counted in a run's verification record.
 *
 * <p>Prices stay inside the instrument's band, so a flow does not spend itself on refusals that
 * measure the validation path and nothing else. Parameters that would reach outside it are refused
 * here rather than producing a log that measures the wrong thing.
 *
 * <p>Two qualifiers are placed only where a venue places them. Minimum quantity goes on an order
 * that crosses, since on one that rests it would execute nothing on entry and every such order
 * would be refused. Immediate-or-cancel and fill-or-kill likewise: a passive order carrying either
 * is flow nobody sends.
 */
public final class FlowGenerator {

  private static final int INSTRUMENT_ID = 1;

  /** What an order is for, which decides its price and which qualifiers it can carry. */
  private enum Intent {
    /** Brings the book to size: passive, and nothing that would stop it resting. */
    WARM_UP,
    PASSIVE,
    CROSSING,
    MARKET
  }

  private final FlowParameters parameters;
  private final FlowParameters.Instrument instrument;
  private final Sequence sequence;
  private final Thresholds thresholds;

  private final MutableDirectBuffer out = new ExpandableArrayBuffer(1 << 20);
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final InstrumentDefinitionEncoder definition = new InstrumentDefinitionEncoder();
  private final SessionStateChangeEncoder session = new SessionStateChangeEncoder();
  private final NewOrderEncoder order = new NewOrderEncoder();
  private final CancelOrderEncoder cancel = new CancelOrderEncoder();
  private final ReplaceOrderEncoder replace = new ReplaceOrderEncoder();
  private final MassCancelEncoder massCancel = new MassCancelEncoder();

  private final int[] offsets;
  private final int[] lengths;
  private final Resting resting;

  private int written;
  private int at;
  private long inputSequence;
  private int orders;

  private FlowGenerator(final FlowParameters parameters) {
    this.parameters = parameters;
    this.instrument = parameters.instrument();
    this.sequence = new Sequence(parameters.seed());
    this.thresholds = Thresholds.of(parameters.composition());
    final int total = 2 + parameters.restingOrders() + parameters.commands();
    this.offsets = new int[total];
    this.lengths = new int[total];
    this.resting = new Resting(total);
  }

  public static CommandLog generate(final FlowParameters parameters) {
    requireInsideTheBand(parameters);
    final FlowGenerator generator = new FlowGenerator(parameters);
    generator.define();
    generator.open();
    for (int order = 0; order < parameters.restingOrders(); order++) {
      generator.enter(Intent.WARM_UP);
    }
    final int measuredFrom = generator.written;
    for (int command = 0; command < parameters.commands(); command++) {
      generator.command();
    }
    return new CommandLog(
        new UnsafeBuffer(generator.out, 0, generator.at),
        generator.offsets,
        generator.lengths,
        generator.written,
        measuredFrom);
  }

  /**
   * One command, chosen by a single draw against the cumulative composition.
   *
   * <p>One draw rather than a ladder of independent ones, so that each fraction is the fraction of
   * commands it says it is. A ladder makes every rate after the first conditional on the ones
   * before it, and a composition that has to be worked out from the code is a composition nobody
   * can set.
   */
  private void command() {
    final int draw = sequence.nextInt(Sequence.SCALE);
    int upTo = thresholds.massCancel();
    if (resting.any() && draw < upTo) {
      massCancel();
      return;
    }
    upTo += thresholds.cancel();
    if (resting.any() && draw < upTo) {
      cancel();
      return;
    }
    upTo += thresholds.replace();
    if (resting.any() && draw < upTo) {
      replace();
      return;
    }
    upTo += thresholds.market();
    if (draw < upTo) {
      enter(Intent.MARKET);
      return;
    }
    upTo += thresholds.aggressive();
    enter(draw < upTo ? Intent.CROSSING : Intent.PASSIVE);
  }

  private void enter(final Intent intent) {
    final Side side = side();
    final int participant = 1 + sequence.nextInt(parameters.placement().participants());
    final long quantity = quantity();
    final boolean crossing = intent == Intent.CROSSING || intent == Intent.MARKET;
    final long price = intent == Intent.MARKET ? 0 : price(side, crossing);
    final long trigger = intent == Intent.WARM_UP ? 0 : trigger(side);
    final int ordinal = ++orders;

    order.wrapAndApplyHeader(out, at, header);
    order.frame().instrumentId(INSTRUMENT_ID).sequence(++inputSequence);
    order
        .clientOrderId(ordinal)
        .participantId(participant)
        .side(side)
        .pricing(intent == Intent.MARKET ? PricingInstruction.MARKET : PricingInstruction.LIMIT)
        .timeInForce(timeInForce(intent))
        .price(price)
        .quantity(quantity)
        .minQuantity(minimumQuantity(crossing, quantity))
        .displayQuantity(display(quantity))
        .triggerPrice(trigger)
        .smpId(sequence.chance(thresholds.selfMatch()) ? participant : 0);
    order.flags().clear().postOnly(sequence.chance(thresholds.postOnly()));
    complete(order.encodedLength());

    if (!crossing && trigger == 0) {
      resting.add(ordinal, participant, side, price);
    }
  }

  private void cancel() {
    final int index = resting.pick(sequence);
    cancel.wrapAndApplyHeader(out, at, header);
    cancel.frame().instrumentId(INSTRUMENT_ID).sequence(++inputSequence);
    cancel
        .clientOrderId(inputSequence)
        .participantId(resting.participantAt(index))
        .orderId(resting.ordinalAt(index));
    complete(cancel.encodedLength());
    resting.removeAt(index);
  }

  /**
   * A replace at the same price, or one tick further from the reference.
   *
   * <p>Both paths matter and the difference between them is the point of the command: at the same
   * price a lower quantity keeps queue position, and anything else loses it. Moving away from the
   * reference keeps the order passive, so a replace never turns into an accidental crossing order.
   */
  private void replace() {
    final int index = resting.pick(sequence);
    final long price = movedPrice(index);
    replace.wrapAndApplyHeader(out, at, header);
    replace.frame().instrumentId(INSTRUMENT_ID).sequence(++inputSequence);
    replace
        .clientOrderId(inputSequence)
        .participantId(resting.participantAt(index))
        .orderId(resting.ordinalAt(index))
        .quantity(quantity())
        .price(price);
    complete(replace.encodedLength());
    resting.priceAt(index, price);
  }

  private void massCancel() {
    final int participant = 1 + sequence.nextInt(parameters.placement().participants());
    massCancel.wrapAndApplyHeader(out, at, header);
    massCancel.frame().instrumentId(INSTRUMENT_ID).sequence(++inputSequence);
    massCancel.clientOrderId(inputSequence).participantId(participant);
    complete(massCancel.encodedLength());
    resting.forget(participant);
  }

  private void define() {
    definition.wrapAndApplyHeader(out, at, header);
    definition.frame().instrumentId(INSTRUMENT_ID).sequence(++inputSequence);
    definition
        .tickSize(instrument.tickSize())
        .lotSize(instrument.lotSize())
        .minPrice(instrument.minPrice())
        .maxPrice(instrument.maxPrice())
        .priceScale((short) instrument.priceScale())
        .bandWidth(instrument.bandWidth())
        .openingReference(instrument.openingReference())
        .allocation(instrument.allocation());
    complete(definition.encodedLength());
  }

  private void open() {
    session.wrapAndApplyHeader(out, at, header);
    session.frame().instrumentId(INSTRUMENT_ID).sequence(++inputSequence);
    session.state(SessionState.CONTINUOUS);
    complete(session.encodedLength());
  }

  private Side side() {
    return sequence.chance(Sequence.SCALE / 2) ? Side.BUY : Side.SELL;
  }

  private long quantity() {
    return instrument.lotSize() * (1 + sequence.nextInt(parameters.placement().maximumLots()));
  }

  /** A price some ticks from the reference, on the side that rests or across it. */
  private long price(final Side side, final boolean crossing) {
    final long distance = ticks();
    final boolean above = crossing == (side == Side.BUY);
    return instrument.openingReference() + (above ? distance : -distance);
  }

  private long movedPrice(final int index) {
    final long price = resting.priceAt(index);
    if (!sequence.chance(Sequence.SCALE / 2)) {
      return price;
    }
    final long away =
        resting.sideAt(index) == Side.BUY ? -instrument.tickSize() : instrument.tickSize();
    final long moved = price + away;
    return withinBounds(moved) ? moved : price;
  }

  private TimeInForce timeInForce(final Intent intent) {
    if (intent == Intent.MARKET) {
      // A market order cannot rest, so it cannot be told to. Anything else contradicts itself and
      // the engine refuses it, which is a rejection this generator has no business producing.
      return TimeInForce.IMMEDIATE_OR_CANCEL;
    }
    if (intent != Intent.CROSSING) {
      return TimeInForce.GOOD_TILL_CANCEL;
    }
    if (sequence.chance(thresholds.fillOrKill())) {
      return TimeInForce.FILL_OR_KILL;
    }
    return sequence.chance(thresholds.immediateOrCancel())
        ? TimeInForce.IMMEDIATE_OR_CANCEL
        : TimeInForce.GOOD_TILL_CANCEL;
  }

  private long minimumQuantity(final boolean crossing, final long quantity) {
    return crossing && sequence.chance(thresholds.minimumQuantity()) ? half(quantity) : 0;
  }

  private long display(final long quantity) {
    if (!sequence.chance(thresholds.iceberg())) {
      return 0;
    }
    final long displayed = half(quantity);
    return displayed < quantity ? displayed : 0;
  }

  private long trigger(final Side side) {
    if (!sequence.chance(thresholds.stop())) {
      return 0;
    }
    final long distance = ticks();
    return instrument.openingReference() + (side == Side.BUY ? distance : -distance);
  }

  private long ticks() {
    return instrument.tickSize() * (1 + sequence.nearer(parameters.placement().depthTicks()));
  }

  private long half(final long quantity) {
    final long lot = instrument.lotSize();
    return Math.max(lot, quantity / 2 / lot * lot);
  }

  private boolean withinBounds(final long price) {
    final long reach = Math.abs(price - instrument.openingReference());
    return reach <= instrument.bandWidth()
        && price >= instrument.minPrice()
        && price <= instrument.maxPrice();
  }

  private void complete(final int encodedLength) {
    final int length = MessageHeaderEncoder.ENCODED_LENGTH + encodedLength;
    offsets[written] = at;
    lengths[written] = length;
    written++;
    at += length;
  }

  private static void requireInsideTheBand(final FlowParameters parameters) {
    final FlowParameters.Instrument instrument = parameters.instrument();
    final long reach = instrument.tickSize() * parameters.placement().depthTicks();
    if (reach > instrument.bandWidth()) {
      throw new IllegalArgumentException(
          "orders would reach "
              + reach
              + " from the reference, outside a band of "
              + instrument.bandWidth());
    }
    if (instrument.openingReference() - reach < instrument.minPrice()
        || instrument.openingReference() + reach > instrument.maxPrice()) {
      throw new IllegalArgumentException(
          "orders would reach outside the instrument's price bounds");
    }
  }
}
