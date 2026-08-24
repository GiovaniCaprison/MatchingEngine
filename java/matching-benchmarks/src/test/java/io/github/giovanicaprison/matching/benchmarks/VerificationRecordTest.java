package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.giovanicaprison.matching.protocol.MessageHeaderEncoder;
import io.github.giovanicaprison.matching.protocol.OrderAcceptedEncoder;
import io.github.giovanicaprison.matching.protocol.OrderExecutedEncoder;
import java.nio.charset.StandardCharsets;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two implementations agreeing on the digest produced the same bytes in the same order, so what
 * these check is that the digest is sensitive to everything a wrong engine could get wrong.
 */
class VerificationRecordTest {

  private final MutableDirectBuffer buffer = new ExpandableArrayBuffer(256);
  private final MessageHeaderEncoder header = new MessageHeaderEncoder();
  private final OrderAcceptedEncoder accepted = new OrderAcceptedEncoder();
  private final OrderExecutedEncoder executed = new OrderExecutedEncoder();

  @Test
  @DisplayName("the hash is the published algorithm, so another language can check itself")
  void the_hash_matches_the_reference_vectors() {
    assertThat(hashOf("")).isEqualTo(0xcbf29ce484222325L);
    assertThat(hashOf("a")).isEqualTo(0xaf63dc4c8601ec8cL);
    assertThat(hashOf("foobar")).isEqualTo(0x85944171f73967e8L);
  }

  @Test
  @DisplayName("events are counted by name and their bytes accumulated")
  void a_run_is_summarised_by_type() {
    final VerificationRecord record = new VerificationRecord();
    record.record(buffer, 0, acceptance(11, 1));
    record.record(buffer, 0, acceptance(12, 2));
    final int execution = execution(1, 11, 12, 100_000, 5);
    record.record(buffer, 0, execution);

    assertThat(record.events()).isEqualTo(3);
    assertThat(record.countsByName())
        .containsEntry("OrderAccepted", 2L)
        .containsEntry("OrderExecuted", 1L);
    assertThat(record.bytes()).isPositive();
    assertThat(record.toJson()).contains("\"OrderAccepted\": 2").contains("\"digest\"");
  }

  @Test
  @DisplayName("one wrong field changes the digest")
  void the_digest_notices_a_wrong_number() {
    final VerificationRecord right = new VerificationRecord();
    right.record(buffer, 0, execution(1, 11, 12, 100_000, 5));

    final VerificationRecord wrong = new VerificationRecord();
    wrong.record(buffer, 0, execution(1, 11, 12, 100_000, 4));

    assertThat(wrong.digest()).isNotEqualTo(right.digest());
  }

  @Test
  @DisplayName("the same events in another order change the digest")
  void the_digest_notices_a_reordering() {
    final VerificationRecord first = new VerificationRecord();
    first.record(buffer, 0, acceptance(11, 1));
    first.record(buffer, 0, acceptance(12, 2));

    final VerificationRecord second = new VerificationRecord();
    second.record(buffer, 0, acceptance(12, 2));
    second.record(buffer, 0, acceptance(11, 1));

    assertThat(second.digest()).isNotEqualTo(first.digest());
    assertThat(second.countsByName()).isEqualTo(first.countsByName());
  }

  @Test
  @DisplayName("every event the protocol defines has a name to be counted under")
  void every_event_is_named() {
    assertThat(EventNames.byTemplate()).hasSize(9).doesNotContainValue(null);
    assertThat(EventNames.of(OrderExecutedEncoder.TEMPLATE_ID)).isEqualTo("OrderExecuted");
  }

  private static long hashOf(final String text) {
    final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    return VerificationRecord.hash(
        VerificationRecord.basis(), new UnsafeBuffer(bytes), 0, bytes.length);
  }

  private int acceptance(final long orderId, final long clientOrderId) {
    accepted.wrapAndApplyHeader(buffer, 0, header);
    accepted.frame().instrumentId(1).sequence(orderId);
    accepted.orderId(orderId).clientOrderId(clientOrderId).participantId(1);
    return MessageHeaderEncoder.ENCODED_LENGTH + accepted.encodedLength();
  }

  private int execution(
      final long executionId,
      final long aggressor,
      final long resting,
      final long price,
      final long quantity) {
    executed.wrapAndApplyHeader(buffer, 0, header);
    executed.frame().instrumentId(1).sequence(executionId);
    executed
        .executionId(executionId)
        .aggressorOrderId(aggressor)
        .restingOrderId(resting)
        .price(price)
        .quantity(quantity);
    return MessageHeaderEncoder.ENCODED_LENGTH + executed.encodedLength();
  }
}
