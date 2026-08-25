package io.github.giovanicaprison.matching.flyweight;

import io.github.giovanicaprison.matching.api.EventPublisher;
import io.github.giovanicaprison.matching.protocol.AuctionIndicativeEncoder;
import io.github.giovanicaprison.matching.protocol.MessageHeaderEncoder;
import io.github.giovanicaprison.matching.protocol.OrderAcceptedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderExecutedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderReducedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderRejectedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderRemovedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderRestedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderTriggeredEncoder;
import io.github.giovanicaprison.matching.protocol.SessionStateChangedEncoder;
import java.nio.ByteOrder;
import org.agrona.MutableDirectBuffer;

/**
 * The engine's output, encoded as a run of plain puts at fixed offsets into the space the publisher
 * hands over.
 *
 * <p>Encoding is part of an implementation's cost and stays inside it. The rung below wrapped a
 * generated encoder around every claim; here each event is written directly, the message header as
 * one precomputed long, every field at an offset taken from the generated encoder for that message,
 * so the schema in schema/ stays the source of truth for where each byte lands and a schema change
 * breaks this file at compile time rather than on the wire.
 *
 * <p>The publisher's buffer is held once, which its contract licenses, so an event costs no call to
 * fetch it. Nothing is buffered and nothing is held back: every event leaves the visible book valid
 * on its own, and carries the engine's own sequence rather than anything about its cause.
 */
final class Feed {

  private static final ByteOrder WIRE = ByteOrder.LITTLE_ENDIAN;
  private static final int BODY = MessageHeaderEncoder.ENCODED_LENGTH;

  /** EventFrame: instrumentId at the body's start, the engine's sequence beside it. */
  private static final int FRAME_INSTRUMENT = BODY;

  private static final int FRAME_SEQUENCE = BODY + 4;

  /** The four header shorts as one little-endian word: blockLength, templateId, schemaId, 0. */
  private static long header(final int blockLength, final int templateId) {
    return (blockLength & 0xFFFFL)
        | ((templateId & 0xFFFFL) << 16)
        | ((long) MessageHeaderEncoder.SCHEMA_ID << 32);
  }

  private static final long ACCEPTED =
      header(OrderAcceptedEncoder.BLOCK_LENGTH, OrderAcceptedEncoder.TEMPLATE_ID);
  private static final long REJECTED =
      header(OrderRejectedEncoder.BLOCK_LENGTH, OrderRejectedEncoder.TEMPLATE_ID);
  private static final long RESTED =
      header(OrderRestedEncoder.BLOCK_LENGTH, OrderRestedEncoder.TEMPLATE_ID);
  private static final long EXECUTED =
      header(OrderExecutedEncoder.BLOCK_LENGTH, OrderExecutedEncoder.TEMPLATE_ID);
  private static final long REDUCED =
      header(OrderReducedEncoder.BLOCK_LENGTH, OrderReducedEncoder.TEMPLATE_ID);
  private static final long REMOVED =
      header(OrderRemovedEncoder.BLOCK_LENGTH, OrderRemovedEncoder.TEMPLATE_ID);
  private static final long TRIGGERED =
      header(OrderTriggeredEncoder.BLOCK_LENGTH, OrderTriggeredEncoder.TEMPLATE_ID);
  private static final long STATE_CHANGED =
      header(SessionStateChangedEncoder.BLOCK_LENGTH, SessionStateChangedEncoder.TEMPLATE_ID);
  private static final long INDICATIVE =
      header(AuctionIndicativeEncoder.BLOCK_LENGTH, AuctionIndicativeEncoder.TEMPLATE_ID);

  private final EventPublisher events;
  private final MutableDirectBuffer out;

  private int instrumentId;
  private long sequence;

  Feed(final EventPublisher events) {
    this.events = events;
    this.out = events.buffer();
  }

  void instrument(final int id) {
    this.instrumentId = id;
  }

  private int claim(final long header, final int blockLength) {
    final int at = events.claim(BODY + blockLength);
    out.putLong(at, header, WIRE);
    out.putInt(at + FRAME_INSTRUMENT, instrumentId, WIRE);
    out.putLong(at + FRAME_SEQUENCE, ++sequence, WIRE);
    return at;
  }

  void accepted(final long orderId, final long clientOrderId, final int participantId) {
    final int at = claim(ACCEPTED, OrderAcceptedEncoder.BLOCK_LENGTH);
    out.putLong(at + BODY + OrderAcceptedEncoder.orderIdEncodingOffset(), orderId, WIRE);
    out.putLong(
        at + BODY + OrderAcceptedEncoder.clientOrderIdEncodingOffset(), clientOrderId, WIRE);
    out.putInt(at + BODY + OrderAcceptedEncoder.participantIdEncodingOffset(), participantId, WIRE);
    events.commit();
  }

  void rejected(final long clientOrderId, final int participantId, final int reason) {
    final int at = claim(REJECTED, OrderRejectedEncoder.BLOCK_LENGTH);
    out.putLong(
        at + BODY + OrderRejectedEncoder.clientOrderIdEncodingOffset(), clientOrderId, WIRE);
    out.putInt(at + BODY + OrderRejectedEncoder.participantIdEncodingOffset(), participantId, WIRE);
    out.putByte(at + BODY + OrderRejectedEncoder.reasonEncodingOffset(), (byte) reason);
    events.commit();
  }

  /** Displayed quantity only. Hidden quantity is never reported (FR-5.2). */
  void rested(final long orderId, final int side, final long price, final long displayed) {
    final int at = claim(RESTED, OrderRestedEncoder.BLOCK_LENGTH);
    out.putLong(at + BODY + OrderRestedEncoder.orderIdEncodingOffset(), orderId, WIRE);
    out.putByte(at + BODY + OrderRestedEncoder.sideEncodingOffset(), (byte) side);
    out.putLong(at + BODY + OrderRestedEncoder.priceEncodingOffset(), price, WIRE);
    out.putLong(at + BODY + OrderRestedEncoder.quantityEncodingOffset(), displayed, WIRE);
    events.commit();
  }

  void executed(
      final long executionId,
      final long aggressor,
      final long resting,
      final long price,
      final long quantity) {
    final int at = claim(EXECUTED, OrderExecutedEncoder.BLOCK_LENGTH);
    out.putLong(at + BODY + OrderExecutedEncoder.executionIdEncodingOffset(), executionId, WIRE);
    out.putLong(at + BODY + OrderExecutedEncoder.aggressorOrderIdEncodingOffset(), aggressor, WIRE);
    out.putLong(at + BODY + OrderExecutedEncoder.restingOrderIdEncodingOffset(), resting, WIRE);
    out.putLong(at + BODY + OrderExecutedEncoder.priceEncodingOffset(), price, WIRE);
    out.putLong(at + BODY + OrderExecutedEncoder.quantityEncodingOffset(), quantity, WIRE);
    events.commit();
  }

  void reduced(final long orderId, final long displayed) {
    final int at = claim(REDUCED, OrderReducedEncoder.BLOCK_LENGTH);
    out.putLong(at + BODY + OrderReducedEncoder.orderIdEncodingOffset(), orderId, WIRE);
    out.putLong(at + BODY + OrderReducedEncoder.quantityEncodingOffset(), displayed, WIRE);
    events.commit();
  }

  void removed(final long orderId, final long quantity, final int reason) {
    final int at = claim(REMOVED, OrderRemovedEncoder.BLOCK_LENGTH);
    out.putLong(at + BODY + OrderRemovedEncoder.orderIdEncodingOffset(), orderId, WIRE);
    out.putLong(at + BODY + OrderRemovedEncoder.quantityEncodingOffset(), quantity, WIRE);
    out.putByte(at + BODY + OrderRemovedEncoder.reasonEncodingOffset(), (byte) reason);
    events.commit();
  }

  void triggered(final long orderId) {
    final int at = claim(TRIGGERED, OrderTriggeredEncoder.BLOCK_LENGTH);
    out.putLong(at + BODY + OrderTriggeredEncoder.orderIdEncodingOffset(), orderId, WIRE);
    events.commit();
  }

  void stateChanged(final int state) {
    final int at = claim(STATE_CHANGED, SessionStateChangedEncoder.BLOCK_LENGTH);
    out.putByte(at + BODY + SessionStateChangedEncoder.stateEncodingOffset(), (byte) state);
    events.commit();
  }

  void indicative(final long price, final long quantity) {
    final int at = claim(INDICATIVE, AuctionIndicativeEncoder.BLOCK_LENGTH);
    out.putLong(at + BODY + AuctionIndicativeEncoder.priceEncodingOffset(), price, WIRE);
    out.putLong(at + BODY + AuctionIndicativeEncoder.quantityEncodingOffset(), quantity, WIRE);
    events.commit();
  }
}
