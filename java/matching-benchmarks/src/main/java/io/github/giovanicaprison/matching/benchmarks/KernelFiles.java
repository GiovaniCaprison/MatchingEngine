package io.github.giovanicaprison.matching.benchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads the kernel's own files under a root, which is how every probe here learns anything.
 *
 * <p>One reader for the environment probes and the samples, so what counts as unreadable is decided
 * once. A file the kernel does not expose and a platform that never had it both come back as
 * nothing, and callers record that as unavailable rather than wrong.
 */
final class KernelFiles {

  private KernelFiles() {}

  static Optional<String> read(final Path file) {
    if (!Files.isReadable(file) || Files.isDirectory(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readString(file));
    } catch (final IOException e) {
      return Optional.empty();
    }
  }

  static String firstLine(final Path file) {
    return read(file).flatMap(text -> text.lines().findFirst()).map(String::strip).orElse(null);
  }

  static String firstLine(final Path root, final String path) {
    return firstLine(root.resolve(path));
  }

  /** The value after the colon on the line opening with the key, as {@code /proc} lays it out. */
  static String keyed(final Path root, final String path, final String key) {
    return read(root.resolve(path))
        .flatMap(
            text ->
                text.lines()
                    .filter(line -> line.startsWith(key) && line.contains(":"))
                    .map(line -> line.substring(line.indexOf(':') + 1).strip())
                    .findFirst())
        .orElse(null);
  }
}
