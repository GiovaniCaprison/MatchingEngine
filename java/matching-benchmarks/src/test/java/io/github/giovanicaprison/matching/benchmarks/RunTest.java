package io.github.giovanicaprison.matching.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A run's artifacts are pulled down whole, so everything it writes has to be in one place. */
class RunTest {

  @TempDir private Path results;

  @Test
  @DisplayName("a run makes its own directory, named for what it is")
  void a_run_is_a_directory() {
    final Run run = Run.create(results, "naive-java");

    assertThat(run.directory()).isDirectory().hasParent(results);
    assertThat(run.id()).endsWith("-naive-java").matches("\\d{8}T\\d{6}Z-naive-java");
    assertThat(run.file("manifest.json")).isEqualTo(run.directory().resolve("manifest.json"));
  }

  @Test
  @DisplayName("the commit is read out of the repository")
  void the_commit_comes_from_the_checkout() throws IOException {
    final Path repository = results.resolve("checkout");
    Files.createDirectories(repository.resolve(".git/refs/heads"));
    Files.writeString(repository.resolve(".git/HEAD"), "ref: refs/heads/main\n");
    Files.writeString(
        repository.resolve(".git/refs/heads/main"), "9c1d0f7c1f4b2a3d4e5f60718293a4b5c6d7e8f9\n");

    assertThat(Git.head(repository)).isEqualTo("9c1d0f7c1f4b2a3d4e5f60718293a4b5c6d7e8f9");
  }

  @Test
  @DisplayName("a packed reference is resolved, and no repository is said rather than guessed")
  void packed_refs_and_missing_repositories() throws IOException {
    final Path repository = results.resolve("packed");
    Files.createDirectories(repository.resolve(".git"));
    Files.writeString(repository.resolve(".git/HEAD"), "ref: refs/heads/main\n");
    Files.writeString(
        repository.resolve(".git/packed-refs"),
        "# pack-refs with: peeled fully-peeled sorted\n"
            + "1111111111111111111111111111111111111111 refs/heads/main\n");

    assertThat(Git.head(repository)).isEqualTo("1111111111111111111111111111111111111111");
    assertThat(Git.head(results.resolve("nowhere"))).isEqualTo(Git.UNKNOWN);
  }
}
