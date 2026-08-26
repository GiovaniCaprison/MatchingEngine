package io.github.giovanicaprison.matching.lean.flyweight;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.api.MatchingEngine;
import io.github.giovanicaprison.matching.protocol.CancelOrderDecoder;
import io.github.giovanicaprison.matching.protocol.InstrumentDefinitionDecoder;
import io.github.giovanicaprison.matching.protocol.MassCancelDecoder;
import io.github.giovanicaprison.matching.protocol.MessageHeaderDecoder;
import io.github.giovanicaprison.matching.protocol.NewOrderDecoder;
import io.github.giovanicaprison.matching.protocol.PricingInstruction;
import io.github.giovanicaprison.matching.protocol.RejectReason;
import io.github.giovanicaprison.matching.protocol.RemoveReason;
import io.github.giovanicaprison.matching.protocol.ReplaceOrderDecoder;
import io.github.giovanicaprison.matching.protocol.SessionState;
import io.github.giovanicaprison.matching.protocol.SessionStateChangeDecoder;
import io.github.giovanicaprison.matching.protocol.TimeInForce;
import java.nio.ByteOrder;
import org.agrona.DirectBuffer;

/**
 * Rung three's lean twin: limit and market orders, price-time, on the flyweight layout.
 *
 * <p>Published matching engine figures are taken on books like this one, and METHODOLOGY's first
 * question is what the rest of a real venue's feature set costs when nobody uses it. A runtime flag
 * cannot answer that, because a disabled feature behind a branch still occupies the method, the
 * layout and the inlining budget (P-16). So this engine is the arm where the features do not exist:
 * no trigger book consulted after an execution, no tranche arithmetic on a take, no self match
 * comparison per candidate, no allocation choice, no auction, and an order slot that fits in one
 * cache line where the full rung's needs two. Comparing it against the full rung on flow that uses
 * only this remit measures the cost of existence itself.
 *
 * <p>On the shared remit this engine, the full flyweight rung, and every arm below them are byte
 * identical by construction, and tests hold them to it: an arm that answered differently would be
 * measuring a behaviour difference and calling it a feature cost.
 *
 * <p>Input is trusted the way every engine here trusts it (P-14). Flow at this composition never
 * carries a qualifier, so the fields this engine does not read are fields nothing sets.
 */
public final class LeanEngine implements MatchingEngine {

  private static final ByteOrder WIRE = ByteOrder.LITTLE_ENDIAN;

  private static final int HEADER_TEMPLATE = MessageHeaderDecoder.templateIdEncodingOffset();
  private static final int BODY = MessageHeaderDecoder.ENCODED_LENGTH;

  private static final int LIMIT = PricingInstruction.LIMIT.value();
  private static final int MARKET = PricingInstruction.MARKET.value();
  private static final int DAY = TimeInForce.DAY.value();

  private static final int PRE_OPEN = SessionState.PRE_OPEN.value();
  private static final int CONTINUOUS = SessionState.CONTINUOUS.value();
  private static final int CLOSED = SessionState.CLOSED.value();

  private static final int NON_POSITIVE_QUANTITY = RejectReason.NON_POSITIVE_QUANTITY.value();
  private static final int LOT_VIOLATION = RejectReason.LOT_VIOLATION.value();
  private static final int NON_POSITIVE_PRICE = RejectReason.NON_POSITIVE_PRICE.value();
  private static final int TICK_VIOLATION = RejectReason.TICK_VIOLATION.value();
  private static final int STATIC_BAND_VIOLATION = RejectReason.STATIC_BAND_VIOLATION.value();
  private static final int DYNAMIC_BAND_VIOLATION = RejectReason.DYNAMIC_BAND_VIOLATION.value();
  private static final int INVALID_FIELDS = RejectReason.INVALID_FIELDS.value();
  private static final int STATE_NOT_PERMITTED = RejectReason.STATE_NOT_PERMITTED.value();
  private static final int UNKNOWN_ORDER = RejectReason.UNKNOWN_ORDER.value();
  private static final int QUANTITY_BELOW_EXECUTED = RejectReason.QUANTITY_BELOW_EXECUTED.value();

  private static final int CANCELLED = RemoveReason.CANCELLED.value();
  private static final int REPLACED = RemoveReason.REPLACED.value();
  private static final int MASS_CANCELLED = RemoveReason.MASS_CANCELLED.value();
  private static final int IOC_REMAINDER = RemoveReason.IMMEDIATE_OR_CANCEL_REMAINDER.value();

  // Field offsets inside each command's body, each tied to its schema field by the decoder.
  private static final int DEFINITION_INSTRUMENT =
      InstrumentDefinitionDecoder.frameEncodingOffset();
  private static final int DEFINITION_TICK = InstrumentDefinitionDecoder.tickSizeEncodingOffset();
  private static final int DEFINITION_LOT = InstrumentDefinitionDecoder.lotSizeEncodingOffset();
  private static final int DEFINITION_MIN = InstrumentDefinitionDecoder.minPriceEncodingOffset();
  private static final int DEFINITION_MAX = InstrumentDefinitionDecoder.maxPriceEncodingOffset();
  private static final int DEFINITION_BAND = InstrumentDefinitionDecoder.bandWidthEncodingOffset();
  private static final int DEFINITION_OPEN =
      InstrumentDefinitionDecoder.openingReferenceEncodingOffset();

  private static final int NEW_CLIENT = NewOrderDecoder.clientOrderIdEncodingOffset();
  private static final int NEW_PARTICIPANT = NewOrderDecoder.participantIdEncodingOffset();
  private static final int NEW_SIDE = NewOrderDecoder.sideEncodingOffset();
  private static final int NEW_PRICING = NewOrderDecoder.pricingEncodingOffset();
  private static final int NEW_TIF = NewOrderDecoder.timeInForceEncodingOffset();
  private static final int NEW_PRICE = NewOrderDecoder.priceEncodingOffset();
  private static final int NEW_QUANTITY = NewOrderDecoder.quantityEncodingOffset();

  private static final int CANCEL_CLIENT = CancelOrderDecoder.clientOrderIdEncodingOffset();
  private static final int CANCEL_PARTICIPANT = CancelOrderDecoder.participantIdEncodingOffset();

  private static final int REPLACE_CLIENT = ReplaceOrderDecoder.clientOrderIdEncodingOffset();
  private static final int REPLACE_PARTICIPANT = ReplaceOrderDecoder.participantIdEncodingOffset();
  private static final int REPLACE_QUANTITY = ReplaceOrderDecoder.quantityEncodingOffset();
  private static final int REPLACE_PRICE = ReplaceOrderDecoder.priceEncodingOffset();

  private static final int MASS_CLIENT = MassCancelDecoder.clientOrderIdEncodingOffset();
  private static final int MASS_PARTICIPANT = MassCancelDecoder.participantIdEncodingOffset();

  private static final int SESSION_STATE = SessionStateChangeDecoder.stateEncodingOffset();

  private final Feed feed;
  private final Slab slab = new Slab(1 << 16);
  private final IntScratch gathered = new IntScratch();

  private Book book;

  private long tickSize = 1;
  private long lotSize = 1;
  private long minPrice;
  private long maxPrice;
  private long bandWidth;
  private long baseTick;
  private int state = PRE_OPEN;
  private long reference;
  private long nextOrderId = 1;
  private long nextExecutionId = 1;
  private long arrival;

  LeanEngine(final EventPublisher events) {
    this.feed = new Feed(events);
  }

  @Override
  public void onCommand(final DirectBuffer buffer, final int offset, final int length) {
    final int body = offset + BODY;
    switch (buffer.getShort(offset + HEADER_TEMPLATE, WIRE) & 0xFFFF) {
      case NewOrderDecoder.TEMPLATE_ID -> enter(buffer, body);
      case CancelOrderDecoder.TEMPLATE_ID -> cancel(buffer, body);
      case ReplaceOrderDecoder.TEMPLATE_ID -> replace(buffer, body);
      case MassCancelDecoder.TEMPLATE_ID -> massCancel(buffer, body);
      case SessionStateChangeDecoder.TEMPLATE_ID -> {
        state = buffer.getByte(body + SESSION_STATE);
        feed.stateChanged(state);
      }
      case InstrumentDefinitionDecoder.TEMPLATE_ID -> define(buffer, body);
      default ->
          throw new IllegalArgumentException(
              "template "
                  + (buffer.getShort(offset + HEADER_TEMPLATE, WIRE) & 0xFFFF)
                  + " is not a command (P-14)");
    }
  }

  private void define(final DirectBuffer buffer, final int body) {
    tickSize = buffer.getLong(body + DEFINITION_TICK, WIRE);
    lotSize = buffer.getLong(body + DEFINITION_LOT, WIRE);
    minPrice = buffer.getLong(body + DEFINITION_MIN, WIRE);
    maxPrice = buffer.getLong(body + DEFINITION_MAX, WIRE);
    bandWidth = buffer.getLong(body + DEFINITION_BAND, WIRE);
    reference = buffer.getLong(body + DEFINITION_OPEN, WIRE);
    baseTick = (minPrice + tickSize - 1) / tickSize;
    book = new Book(slab, tickSize, baseTick, (int) (maxPrice / tickSize - baseTick + 1));
    feed.instrument(buffer.getInt(body + DEFINITION_INSTRUMENT, WIRE));
  }

  // Order entry ---------------------------------------------------------------------------------

  private void enter(final DirectBuffer buffer, final int body) {
    final long clientOrderId = buffer.getLong(body + NEW_CLIENT, WIRE);
    final int participantId = buffer.getInt(body + NEW_PARTICIPANT, WIRE);
    final int side = buffer.getByte(body + NEW_SIDE);
    final int pricing = buffer.getByte(body + NEW_PRICING);
    final int timeInForce = buffer.getByte(body + NEW_TIF);
    final long price = buffer.getLong(body + NEW_PRICE, WIRE);
    final long quantity = buffer.getLong(body + NEW_QUANTITY, WIRE);

    final long verdict = refusalOrTick(pricing, timeInForce, price, quantity);
    if (verdict < 0) {
      feed.rejected(clientOrderId, participantId, reasonOf(verdict));
      return;
    }
    final long id = nextOrderId++;
    final int slot = slab.acquire();
    slab.init(
        slot,
        id,
        clientOrderId,
        participantId,
        side,
        pricing,
        timeInForce,
        (int) verdict,
        quantity,
        ++arrival,
        0);
    feed.accepted(id, clientOrderId, participantId);
    if (state == CONTINUOUS) {
      match(slot, side);
    }
    settle(slot, side);
  }

  private void settle(final int slot, final int side) {
    if (slab.remaining(slot) == 0) {
      slab.release(slot);
      return;
    }
    if (slab.pricing(slot) == LIMIT && slab.timeInForce(slot) <= DAY) {
      slab.rest(slot, ++arrival);
      book.add(side, slot);
      feed.rested(slab.id(slot), side, book.priceOfTick(slab.tick(slot)), slab.remaining(slot));
      return;
    }
    feed.removed(slab.id(slot), slab.remaining(slot), IOC_REMAINDER);
    slab.release(slot);
  }

  // Matching ------------------------------------------------------------------------------------

  /** (FR-3.1, FR-3.3) Best price first, then earliest arrival, until nothing crosses. */
  private void match(final int taker, final int side) {
    final int limitRank =
        slab.pricing(taker) == MARKET
            ? book.marketLimit()
            : book.rankOf(side ^ 1, slab.tick(taker));
    while (slab.remaining(taker) > 0) {
      final int resting = book.nextToTake(side, limitRank);
      if (resting == 0) {
        return;
      }
      final long quantity = Math.min(slab.remaining(taker), slab.remaining(resting));
      final long price = book.priceOfTick(slab.tick(resting));
      slab.take(taker, quantity);
      slab.take(resting, quantity);
      feed.executed(nextExecutionId++, slab.id(taker), slab.id(resting), price, quantity);
      reference = price;
      final int restingSide = side ^ 1;
      book.quantityChanged(restingSide, resting, -quantity);
      if (slab.remaining(resting) == 0) {
        book.remove(restingSide, resting);
        slab.release(resting);
      }
    }
  }

  // Amend and cancel ----------------------------------------------------------------------------

  private void cancel(final DirectBuffer buffer, final int body) {
    final long clientOrderId = buffer.getLong(body + CANCEL_CLIENT, WIRE);
    final int participantId = buffer.getInt(body + CANCEL_PARTICIPANT, WIRE);
    if (state == CLOSED) {
      feed.rejected(clientOrderId, participantId, STATE_NOT_PERMITTED);
      return;
    }
    final int resting = book.named(participantId, clientOrderId);
    if (resting == 0) {
      feed.rejected(clientOrderId, participantId, UNKNOWN_ORDER);
      return;
    }
    book.remove(slab.side(resting), resting);
    feed.removed(slab.id(resting), slab.remaining(resting), CANCELLED);
    slab.release(resting);
  }

  private void replace(final DirectBuffer buffer, final int body) {
    final long clientOrderId = buffer.getLong(body + REPLACE_CLIENT, WIRE);
    final int participantId = buffer.getInt(body + REPLACE_PARTICIPANT, WIRE);
    if (state == CLOSED) {
      feed.rejected(clientOrderId, participantId, STATE_NOT_PERMITTED);
      return;
    }
    final int resting = book.named(participantId, clientOrderId);
    if (resting == 0) {
      feed.rejected(clientOrderId, participantId, UNKNOWN_ORDER);
      return;
    }
    final long quantity = buffer.getLong(body + REPLACE_QUANTITY, WIRE);
    final long price = buffer.getLong(body + REPLACE_PRICE, WIRE);
    final long verdict = refusalOrTickForReplace(resting, quantity, price);
    if (verdict < 0) {
      feed.rejected(clientOrderId, participantId, reasonOf(verdict));
      return;
    }
    final int side = slab.side(resting);
    final int newTick = (int) verdict;
    final long remainder = quantity - slab.executed(resting);
    if (newTick == slab.tick(resting) && remainder < slab.remaining(resting)) {
      // (FR-4.4) Lowering quantity at the same price keeps queue position.
      final long remainingBefore = slab.remaining(resting);
      slab.reduceTo(resting, remainder);
      book.quantityChanged(side, resting, remainder - remainingBefore);
      feed.reduced(slab.id(resting), slab.remaining(resting));
      return;
    }
    book.remove(side, resting);
    feed.removed(slab.id(resting), slab.remaining(resting), REPLACED);
    // The same order under a new price or quantity, keeping both of its ids (FR-4.8).
    final long id = slab.id(resting);
    final int pricing = slab.pricing(resting);
    final int timeInForce = slab.timeInForce(resting);
    final long executed = slab.executed(resting);
    slab.release(resting);
    final int fresh = slab.acquire();
    slab.init(
        fresh,
        id,
        clientOrderId,
        participantId,
        side,
        pricing,
        timeInForce,
        newTick,
        remainder,
        ++arrival,
        executed);
    if (state == CONTINUOUS) {
      match(fresh, side);
    }
    settle(fresh, side);
  }

  private void massCancel(final DirectBuffer buffer, final int body) {
    final long clientOrderId = buffer.getLong(body + MASS_CLIENT, WIRE);
    final int participantId = buffer.getInt(body + MASS_PARTICIPANT, WIRE);
    if (state == CLOSED) {
      feed.rejected(clientOrderId, participantId, STATE_NOT_PERMITTED);
      return;
    }
    gathered.clear();
    book.of(participantId, gathered);
    gathered.sortByArrival(slab);
    for (int at = 0; at < gathered.size(); at++) {
      final int slot = gathered.get(at);
      book.remove(slab.side(slot), slot);
      feed.removed(slab.id(slot), slab.remaining(slot), MASS_CANCELLED);
      slab.release(slot);
    }
  }

  // Validation ----------------------------------------------------------------------------------

  /** A refusal encoded below zero, so one verdict carries either the reason or the tick. */
  private static long refusal(final int reason) {
    return -1 - (long) reason;
  }

  private static int reasonOf(final long verdict) {
    return (int) (-1 - verdict);
  }

  private long refusalOrTick(
      final int pricing, final int timeInForce, final long price, final long quantity) {
    if (state == CLOSED) {
      return refusal(STATE_NOT_PERMITTED);
    }
    if (quantity <= 0) {
      return refusal(NON_POSITIVE_QUANTITY);
    }
    if (lotSize != 1 && quantity % lotSize != 0) {
      return refusal(LOT_VIOLATION);
    }
    if (pricing == MARKET && timeInForce <= DAY) {
      // (VR-3.1) A market order cannot rest, so it cannot be told to.
      return refusal(INVALID_FIELDS);
    }
    if (pricing == LIMIT) {
      return tickOrRefusal(price);
    }
    return 0;
  }

  /**
   * The price's tick index, or the refusal that keeps it off the ladder, in one division: the
   * quotient that proves the price on tick (VR-2.2) is the index the ladder wants.
   */
  private long tickOrRefusal(final long price) {
    if (price <= 0) {
      return refusal(NON_POSITIVE_PRICE);
    }
    final long ticks = price / tickSize;
    if (ticks * tickSize != price) {
      return refusal(TICK_VIOLATION);
    }
    if (price < minPrice || price > maxPrice) {
      return refusal(STATIC_BAND_VIOLATION);
    }
    if (Math.abs(price - reference) > bandWidth) {
      return refusal(DYNAMIC_BAND_VIOLATION);
    }
    return ticks - baseTick;
  }

  private long refusalOrTickForReplace(final int resting, final long quantity, final long price) {
    if (quantity <= 0) {
      return refusal(NON_POSITIVE_QUANTITY);
    }
    if (quantity <= slab.executed(resting)) {
      return refusal(QUANTITY_BELOW_EXECUTED);
    }
    if (lotSize != 1 && quantity % lotSize != 0) {
      return refusal(LOT_VIOLATION);
    }
    return tickOrRefusal(price);
  }
}
