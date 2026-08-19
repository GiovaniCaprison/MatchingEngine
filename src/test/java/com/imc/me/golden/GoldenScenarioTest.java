package com.imc.me.golden;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.imc.me.support.ScenarioRunner;
import com.imc.me.support.TestTags;
import java.net.URL;
import java.nio.file.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;

/**
 * For explicit but rich deterministic output: an ordered trade stream plus the resulting book.
 * These also are the determinism tests (NFR-1.1/1.2) by construction: same input -> same output.
 *
 * <p>One dynamic test is generated per fixture pair in src/test/resources/scenarios, so adding a
 * scenario means dropping in two text files and changing no code. Traceability lives in the fixture
 * filename.
 */
@Tag(TestTags.GOLDEN)
@DisplayName("Golden | Deterministic scenarios")
class GoldenScenarioTest {

  @TestFactory
  @DisplayName("each scenarios/*.input is replayed and diffed against its .expected")
  Stream<DynamicTest> scenarios() throws Exception {
    Path dir = scenarioDir();
    if (dir == null || !Files.isDirectory(dir)) {
      return Stream.of(
          dynamicTest(
              "no scenarios yet",
              () ->
                  Assumptions.assumeTrue(
                      false, "Add fixtures under src/test/resources/scenarios")));
    }
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .filter(p -> p.toString().endsWith(".input"))
          .sorted()
          .map(in -> dynamicTest(in.getFileName().toString(), () -> ScenarioRunner.run(in)))
          .toList() // materialise so the dir stream can close
          .stream();
    }
  }

  private Path scenarioDir() throws Exception {
    URL url = getClass().getClassLoader().getResource("scenarios");
    return url == null ? null : Paths.get(url.toURI());
  }
}
