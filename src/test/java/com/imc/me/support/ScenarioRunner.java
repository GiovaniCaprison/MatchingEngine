package com.imc.me.support;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.opentest4j.TestAbortedException;

/**
 * Drives a golden scenario: reads "<name>.input" (a command sequence) and "<name>.expected" (the
 * blessed output), and will replay the input through the engine then compare its serialized output
 * to the expected lines.
 */
public final class ScenarioRunner {
  private ScenarioRunner() {}

  public static void run(Path inputFile) throws IOException {
    Path expectedFile =
        inputFile.resolveSibling(
            inputFile.getFileName().toString().replaceFirst("\\.input$", ".expected"));

    List<String> input = Files.readAllLines(inputFile);
    List<String> expected = Files.readAllLines(expectedFile);

    // TODO (Step 1):
    //   MatchingEngine engine = new MatchingEngine(instrument);
    //   List<String> actual = replayAndSerialize(engine, input);
    //   assertThat(actual).isEqualTo(stripComments(expected));
    throw new TestAbortedException(
        "Golden harness OK (parsed "
            + input.size()
            + " input / "
            + expected.size()
            + " expected lines). Engine not implemented yet "
            + "- wire ScenarioRunner.run in Step 1.");
  }
}
