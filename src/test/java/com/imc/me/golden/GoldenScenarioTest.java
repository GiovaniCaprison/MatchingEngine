package com.imc.me.golden;

import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.imc.me.support.Requirement;
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
 * <p>One dynamic test is generated per fixture pair in src/test/resources/scenarios. Add a scenario
 * = drop in two text files; no code change. Traceability lives in the fixture filename *
 */
@Tag(TestTags.GOLDEN)
@DisplayName("Golden | Deterministic scenarios")
@Requirement({
  "FR-3.1", "FR-3.2", "FR-3.3", "FR-3.4", "FR-3.5", "FR-2.3", "FR-2.4", "FR-2.5", "FR-2.6",
  "FR-4.4", "FR-4.5", "API-3.1", "VR-4.1", "VR-4.2", "NFR-1.1", "NFR-1.2", "FR-6.1"
})
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
