package io.github.giovanicaprison.matching.benchmarks;

import io.github.giovanicaprison.matching.protocol.MessageHeaderDecoder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.agrona.DirectBuffer;

/**
 * What an engine actually produced, in a form two runs can be compared by.
 *
 * <p>A benchmark that does not check its output will report a fast wrong engine. Identical input
 * has to produce byte identical output, so the strongest cheap check is a hash of the whole stream:
 * two implementations agreeing on it produced the same bytes in the same order, sequence numbers
 * and all. The counts by type are there for the weaker question of what a run was made of, and for
 * reading a flow's composition back out of its results.
 *
 * <p>This runs on the consumer's core, never the engine's, which is why it can afford to touch
 * every byte. A hash on the engine's thread would cost about what an event costs to produce.
 */
public final class VerificationRecord {

  private static final long FNV_OFFSET = 0xcbf29ce484222325L;
  private static final long FNV_PRIME = 0x100000001b3L;

  private final MessageHeaderDecoder header = new MessageHeaderDecoder();
  private final Map<Integer, Long> counts = new TreeMap<>();

  private long events;
  private long bytes;
  private long digest = FNV_OFFSET;

  /** One event, header included. Bytes are hashed exactly as the engine wrote them. */
  public void record(final DirectBuffer buffer, final int offset, final int length) {
    header.wrap(buffer, offset);
    counts.merge(header.templateId(), 1L, Long::sum);
    events++;
    bytes += length;
    digest = hash(digest, buffer, offset, length);
  }

  /**
   * FNV-1a, 64 bit.
   *
   * <p>Written out rather than taken from a library so that the C++ side computes the same number,
   * and simple enough that both can be checked against the published vectors for the algorithm.
   */
  static long hash(final long seed, final DirectBuffer buffer, final int offset, final int length) {
    long digest = seed;
    for (int at = 0; at < length; at++) {
      digest = (digest ^ (buffer.getByte(offset + at) & 0xFFL)) * FNV_PRIME;
    }
    return digest;
  }

  static long basis() {
    return FNV_OFFSET;
  }

  public long events() {
    return events;
  }

  public long bytes() {
    return bytes;
  }

  /** FNV-1a over every byte of every event, in order. */
  public long digest() {
    return digest;
  }

  public Map<String, Long> countsByName() {
    final Map<String, Long> named = new LinkedHashMap<>();
    counts.forEach((template, count) -> named.put(EventNames.of(template), count));
    return named;
  }

  public String toJson() {
    final Json json = new Json().object().field("events", events).field("bytes", bytes);
    json.field("digest", Long.toHexString(digest)).object("counts");
    countsByName().forEach(json::field);
    return json.end().end().toString();
  }

  public void writeTo(final Path file) {
    try {
      Files.writeString(file, toJson());
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot write the verification record to " + file, e);
    }
  }
}
