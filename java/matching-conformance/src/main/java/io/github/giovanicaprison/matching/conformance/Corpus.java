package io.github.giovanicaprison.matching.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The fixtures on disk.
 *
 * <p>They sit above both language trees, so neither owns the file that holds both to the same
 * behaviour. The directory is found by walking up rather than configured, so this works from a
 * module, from the repository root and from an IDE.
 *
 * <p>Two kinds, in two directories. A rule states one requirement, with its id on the first line,
 * and is what a unit test used to be. A scenario states an interaction that no single rule
 * describes. Both are the same file format replayed the same way, and every implementation runs all
 * of them.
 */
public final class Corpus {

  private static final String DIRECTORY = "corpus";

  private Corpus() {}

  public static List<Fixture> fixtures() {
    return files().stream().map(FixtureParser::parse).toList();
  }

  public static List<Path> files() {
    try (Stream<Path> entries = Files.walk(directory())) {
      return entries
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".txt"))
          .sorted()
          .toList();
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot list the corpus", e);
    }
  }

  public static Path directory() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      final Path corpus = candidate.resolve(DIRECTORY);
      if (Files.isDirectory(corpus)) {
        return corpus;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException(
        "no " + DIRECTORY + " directory above " + Path.of("").toAbsolutePath());
  }
}
