package io.github.giovanicaprison.matching.benchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The commit a run was taken at, read out of the repository rather than shelled out for.
 *
 * <p>A run that cannot name its commit is a number nobody can reproduce, so this is recorded rather
 * than optional. Reading the files is enough: a process launch would be the only other way and it
 * would fail in exactly the same place, on a machine holding the jar and not the repository.
 */
final class Git {

  static final String UNKNOWN = "unknown";

  private Git() {}

  static String head(final Path repository) {
    final Path git = repository.resolve(".git");
    final String head = read(git.resolve("HEAD"));
    if (head == null) {
      return UNKNOWN;
    }
    if (!head.startsWith("ref: ")) {
      return head;
    }
    final String reference = head.substring("ref: ".length());
    final String resolved = read(git.resolve(reference));
    return resolved == null ? packed(git, reference) : resolved;
  }

  /** A repository whose refs have been packed keeps them in one file instead of one file each. */
  private static String packed(final Path git, final String reference) {
    final String packedRefs = read(git.resolve("packed-refs"));
    if (packedRefs == null) {
      return UNKNOWN;
    }
    return packedRefs
        .lines()
        .filter(line -> line.endsWith(" " + reference))
        .map(line -> line.substring(0, line.indexOf(' ')))
        .findFirst()
        .orElse(UNKNOWN);
  }

  private static String read(final Path file) {
    try {
      return Files.isReadable(file) ? Files.readString(file).strip() : null;
    } catch (final IOException e) {
      return null;
    }
  }
}
