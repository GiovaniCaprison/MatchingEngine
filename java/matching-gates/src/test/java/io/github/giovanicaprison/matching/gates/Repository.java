package io.github.giovanicaprison.matching.gates;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
          .filter(path -> !path.toString().contains("/build/"))
          .filter(path -> Stream.of(extensions).anyMatch(path.getFileName().toString()::endsWith))
          .sorted()
          .toList();
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot walk " + relativeDir, e);
    }
  }

  /** Java test sources, which is where a requirement is claimed on that side. */
  static List<Path> javaTestSources() {
    return filesUnder("java", ".java").stream()
        .filter(path -> path.toString().contains("/src/test/"))
        .toList();
  }

  /** The same on the C++ side, where a test is a file in a test directory. */
  static List<Path> cppTestSources() {
    return filesUnder("cpp", ".cpp").stream()
        .filter(path -> path.toString().contains("/test/"))
        .toList();
  }

  /**
   * Everything a requirement or a principle can be cited from: the documents, the sources in both
   * languages, the schema, and the corpus, whose comments cite ids beyond the title line the
   * coverage gate reads. A stale id reads perfectly well wherever it sits, so nowhere an id can sit
   * is left out.
   */
  static List<Path> citingFiles() {
    final List<Path> found = new ArrayList<>(filesUnder("docs", ".md"));
    found.add(ROOT.resolve("README.md"));
    found.addAll(filesUnder("java", ".java"));
    found.addAll(filesUnder("cpp", ".cpp", ".hpp"));
    found.addAll(filesUnder("corpus", ".txt"));
    found.addAll(filesUnder("schema", ".xml"));
    return List.copyOf(found);
  }

  /** Where the fixture format's vocabulary is declared, which two gates need to read. */
  static final String RUNNER =
      "java/matching-conformance/src/main/java/io/github/"
          + "giovanicaprison/matching/conformance/";

  /**
   * The constants of a bare enum, read as text so that the gates depend on no module.
   *
   * <p>Text because a gate that imported the enum would be a gate that compiles against the thing
   * it is checking, and a rename would then be invisible to it.
   */
  static Set<String> enumConstants(final String file) {
    final String source = read(file);
    final int body = source.indexOf('{', source.indexOf("enum "));
    return Pattern.compile("\\b([A-Z][A-Z_]+)\\b")
        .matcher(source.substring(body, source.indexOf('}', body)))
        .results()
        .map(result -> result.group(1))
        .collect(Collectors.toCollection(LinkedHashSet::new));
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
