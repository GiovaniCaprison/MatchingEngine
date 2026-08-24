package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A publisher with nowhere to put an event waits, and says how long for. Dropping the event is the
 * alternative, and an output stream with a hole in it cannot rebuild a book.
 */
class RingPublisherTest {

  private static final int EVENT = 64;

  private final OneToOneRingBuffer ring = Rings.of(1024);
  private final RingPublisher publisher = new RingPublisher(ring);

  @Test
  @DisplayName("an event written through the publisher is what the consumer reads")
  void an_event_survives_the_ring() {
    final int at = publisher.claim(EVENT);
    publisher.buffer().putLong(at, 0x0123456789ABCDEFL);
    publisher.commit();

    final long[] seen = {0};
    final int read = ring.read((type, buffer, index, length) -> seen[0] = buffer.getLong(index));

    assertThat(read).isEqualTo(1);
    assertThat(seen[0]).isEqualTo(0x0123456789ABCDEFL);
    assertThat(publisher.waits()).isZero();
  }

  @Test
  @DisplayName("a full ring is a wait that gets counted, not an event that gets dropped")
  void a_full_ring_is_counted() throws InterruptedException {
    while (fill()) {
      // Right up to the point where the next claim cannot be satisfied.
    }
    final CountDownLatch claiming = new CountDownLatch(1);
    final CountDownLatch claimed = new CountDownLatch(1);
    final Thread engine =
        new Thread(
            () -> {
              claiming.countDown();
              publisher.claim(EVENT);
              publisher.commit();
              claimed.countDown();
            });
    engine.setDaemon(true);
    engine.start();

    assertThat(claiming.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(claimed.await(100, TimeUnit.MILLISECONDS))
        .as("the claim cannot succeed while the ring is full")
        .isFalse();

    // Draining one record is not always enough room: a claim at the end of the buffer has to wrap,
    // and wrapping needs contiguous space at the front.
    ring.read((type, buffer, index, length) -> {});

    assertThat(claimed.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(publisher.waits()).isEqualTo(1);
    assertThat(publisher.waitedNanos()).isPositive();
  }

  private boolean fill() {
    final int at = ring.tryClaim(Rings.EVENT, EVENT);
    if (at < 0) {
      return false;
    }
    ring.commit(at);
    return true;
  }
}
