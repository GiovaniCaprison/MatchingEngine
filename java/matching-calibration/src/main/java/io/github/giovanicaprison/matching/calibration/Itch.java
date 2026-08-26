package io.github.giovanicaprison.matching.calibration;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads Nasdaq TotalView-ITCH 5.0, as the files on Nasdaq's public directory are laid out.
 *
 * <p>Each message is preceded by its length in two big-endian bytes, and the first byte of the
 * message is its type. Every field is big-endian, a price is an unsigned integer with four implied
 * decimals, and a timestamp is six bytes of nanoseconds since midnight.
 *
 * <p>Only the messages a flow is made of are decoded. The rest are counted and stepped over, which
 * is the honest way round: a message this does not understand should not silently become something
 * it does understand.
 *
 * <p>This is a reader for somebody else's feed, kept well away from the engine's own protocol. The
 * two have similar jobs and no relationship, and a shared type between them would be a claim that
 * Nasdaq's decisions are ours.
 */
final class Itch {

  /** What a message says, reused across messages so a long file costs no allocation. */
  static final class Message {

    char type;
    int stockLocate;
    long timestamp;
    long orderReference;
    long newOrderReference;
    long shares;
    long price;
    char side;
    String stock;

    void clear() {
      type = 0;
      stockLocate = 0;
      timestamp = 0;
      orderReference = 0;
      newOrderReference = 0;
      shares = 0;
      price = 0;
      side = 0;
      stock = null;
    }
  }

  private final DataInputStream in;
  private final byte[] body = new byte[64];
  private final Message message = new Message();

  Itch(final InputStream stream) {
    this.in = new DataInputStream(stream);
  }

  /** The next message, or null at the end of the file. */
  Message next() throws IOException {
    final int length;
    try {
      length = in.readUnsignedShort();
    } catch (final EOFException e) {
      return null;
    }
    if (length == 0 || length > body.length) {
      throw new IOException("a message of " + length + " bytes is not ITCH 5.0");
    }
    try {
      in.readFully(body, 0, length);
    } catch (final EOFException e) {
      // A session read in part ends mid message, which is the end of what there is rather than a
      // problem with the feed.
      return null;
    }
    return decode(length);
  }

  @SuppressWarnings("UnusedVariable") // Every decoder takes the frame the feed hands it.
  private Message decode(final int length) {
    message.clear();
    message.type = (char) (body[0] & 0xFF);
    message.stockLocate = (int) unsigned(1, 2);
    message.timestamp = unsigned(5, 6);
    switch (message.type) {
      case 'R' -> message.stock = text(11, 8);
      case 'A', 'F' -> {
        // Add order. An order that crossed on arrival never appears here at all, which is why the
        // aggressive share of a real flow cannot be read straight off the feed.
        message.orderReference = unsigned(11, 8);
        message.side = (char) (body[19] & 0xFF);
        message.shares = unsigned(20, 4);
        message.stock = text(24, 8);
        message.price = unsigned(32, 4);
      }
      case 'E' -> {
        message.orderReference = unsigned(11, 8);
        message.shares = unsigned(19, 4);
      }
      case 'C' -> {
        message.orderReference = unsigned(11, 8);
        message.shares = unsigned(19, 4);
        message.price = unsigned(32, 4);
      }
      case 'X' -> {
        message.orderReference = unsigned(11, 8);
        message.shares = unsigned(19, 4);
      }
      case 'D' -> message.orderReference = unsigned(11, 8);
      case 'U' -> {
        message.orderReference = unsigned(11, 8);
        message.newOrderReference = unsigned(19, 8);
        message.shares = unsigned(27, 4);
        message.price = unsigned(31, 4);
      }
      case 'P' -> {
        // A trade against quantity the book never showed, which is how a hidden execution reaches
        // the tape without touching the visible book.
        message.orderReference = unsigned(11, 8);
        message.side = (char) (body[19] & 0xFF);
        message.shares = unsigned(20, 4);
        message.stock = text(24, 8);
        message.price = unsigned(32, 4);
      }
      case 'Q' -> {
        message.shares = unsigned(11, 8);
        message.stock = text(19, 8);
        message.price = unsigned(27, 4);
      }
      default -> {
        // Counted and stepped over. Guessing at a layout would turn an unread message into a wrong
        // number, which is worse than a number that says it does not know.
      }
    }
    return message;
  }

  private long unsigned(final int offset, final int bytes) {
    long value = 0;
    for (int at = 0; at < bytes; at++) {
      value = (value << 8) | (body[offset + at] & 0xFFL);
    }
    return value;
  }

  private String text(final int offset, final int bytes) {
    return new String(body, offset, bytes, StandardCharsets.US_ASCII).strip();
  }
}
