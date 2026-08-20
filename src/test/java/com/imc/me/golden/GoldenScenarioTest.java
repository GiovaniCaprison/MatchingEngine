package com.imc.me.golden;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.imc.me.support.BookImplementations;
import com.imc.me.support.ScenarioRunner;
import com.imc.me.support.TestTags;
import java.net.URL;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;

/**
 * The scenario corpus, run against every book implementation.
 *
 * <p>The engine is a deterministic function from an input log to an output log, so most of its
 * behaviour is provable by diffing text. Fixtures live in {@code src/test/resources/scenarios} and
 * carry their requirement id in the filename, which is the whole of the traceability scheme.
 *
 * <p>Every implementation is held to the same blessed output, byte for byte. That is what makes a
 * later comparison between them a measurement rather than a guess, and it is the reason the engine
 * rather than the book stamps sequence numbers.
 */
@Tag(TestTags.GOLDEN)
@DisplayName("Golden | Deterministic scenarios")
class GoldenScenarioTest {

  @TestFactory
  @DisplayName("each scenarios/*.input is replayed and diffed against its .expected")
  Stream<DynamicNode> scenarios() throws Exception {
    final List<Path> fixtures = fixtures();
    if (fixtures.isEmpty()) {
      return Stream.of(
          dynamicTest(
              "no scenarios yet",
              () ->
                  Assumptions.assumeTrue(
                      false, "Add fixtures under src/test/resources/scenarios")));
    }

    return BookImplementations.list().stream()
        .map(
            impl ->
                DynamicContainer.dynamicContainer(
                    impl.name(),
                    fixtures.stream()
                        .map(
                            in ->
                                dynamicTest(
                                    in.getFileName().toString(),
                                    () -> ScenarioRunner.run(in, impl.book())))));
  }

  private List<Path> fixtures() throws Exception {
    final URL url = getClass().getClassLoader().getResource("scenarios");
    if (url == null) return List.of();
    final Path dir = Paths.get(url.toURI());
    if (!Files.isDirectory(dir)) return List.of();
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(p -> p.toString().endsWith(".input")).sorted().toList();
    }
  }
}
