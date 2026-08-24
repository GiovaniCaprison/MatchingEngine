package io.github.giovanicaprison.matching.gates;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The files the gates read.
 *
 * <p>The root is found by walking up rather than configured, so a gate behaves the same under
 * Maven, where the working directory is the module, and in an IDE, where it is anybody's guess.
 */
final class Repository {

  private static final Path ROOT = findRoot();

  private Repository() {}

  static Path root() {
    return ROOT;
  }

  static String read(final String relativePath) {
    try {
      return Files.readString(ROOT.resolve(relativePath));
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot read " + relativePath, e);
    }
  }

  /** Every file under {@code relativeDir} with one of the given extensions, build output aside. */
  static List<Path> filesUnder(final String relativeDir, final String... extensions) {
    final Path start = ROOT.resolve(relativeDir);
    if (!Files.isDirectory(start)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(start)) {
      return walk.filter(Files::isRegularFile)
          .filter(path -> !path.toString().contains("/target/"))
          .filter(path -> Stream.of(extensions).anyMatch(path.getFileName().toString()::endsWith))
          .sorted()
          .toList();
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot walk " + relativeDir, e);
    }
  }

  /** Test sources, which is where a requirement is claimed. */
  static List<Path> testSources() {
    return filesUnder("java", ".java").stream()
        .filter(path -> path.toString().contains("/src/test/"))
        .toList();
  }

  /**
   * Everything a requirement or a principle can be cited from: the documents, and the sources that
   * cite one in a comment to say why the code below exists.
   */
  static List<Path> citingFiles() {
    return Stream.concat(
            Stream.concat(filesUnder("docs", ".md").stream(), Stream.of(ROOT.resolve("README.md"))),
            Stream.concat(
                filesUnder("java", ".java").stream(), filesUnder("schema", ".xml").stream()))
        .toList();
  }

  static String contentOf(final Path path) {
    try {
      return Files.readString(path);
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot read " + path, e);
    }
  }

  static String describe(final Path path) {
    return ROOT.relativize(path).toString();
  }

  private static Path findRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.isDirectory(candidate.resolve("docs"))
          && Files.isDirectory(candidate.resolve("schema"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
  }
}
