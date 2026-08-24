package io.github.giovanicaprison.matching.benchmarks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * One run's directory, and the name it is known by.
 *
 * <p>Everything a run produces lands in one place and is pulled down whole, so a figure produced
 * months later still has the manifest that says what it came from. Nothing is written outside it
 * and nothing is inspected on the machine that made it.
 *
 * @param id how the run is referred to everywhere
 * @param directory where its artifacts go
 * @param startedAt when it began
 */
public record Run(String id, Path directory, Instant startedAt) {

  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  /**
   * A new directory under {@code results}.
   *
   * @param results the root every run writes under
   * @param label what this run is for, which becomes part of its name
   */
  public static Run create(final Path results, final String label) {
    final Instant now = Instant.now();
    final String id = STAMP.format(now) + "-" + label;
    final Path directory = results.resolve(id);
    try {
      Files.createDirectories(directory);
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot create the run directory " + directory, e);
    }
    return new Run(id, directory, now);
  }

  public Path file(final String name) {
    return directory.resolve(name);
  }
}
