package io.github.giovanicaprison.matching.gates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The build fails when the test suite and {@code REQUIREMENTS.md} disagree about what is covered.
 *
 * <p>A coverage report would be the softer option and the worse one. An unmet requirement that
 * produces a slightly shorter report is easy not to notice, where a failing build has to be either
 * fixed or argued with. Both are gameable; one is gameable in a way that leaves evidence.
 */
class RequirementCoverageGate {

  private static final Pattern IMPLEMENTS_ENGINE =
      Pattern.compile("implements\\s+MatchingEngine\\b");

  @Test
  @DisplayName("every requirement shown to hold by a unit test is claimed by one")
  void unit_requirements_are_all_claimed() {
    // Vacuous until something implements the engine, since the suite these ids belong to is the one
    // that drives an implementation through the api. The first implementation owes all of them.
    assumeTrue(anImplementationExists(), "no implementation yet, so there is no suite to hold");

    final Set<String> unclaimed = new LinkedHashSet<>(Requirements.coveredByUnitTests());
    unclaimed.removeAll(JavaTests.requirementsClaimed());

    assertThat(unclaimed)
        .as("requirements marked unit that no test names in its display name")
        .isEmpty();
  }

  @Test
  @DisplayName("no test claims a requirement the document does not list")
  void claims_name_real_requirements() {
    final Set<String> unknown = new LinkedHashSet<>(JavaTests.requirementsClaimed());
    unknown.removeAll(Requirements.ids());

    assertThat(unknown)
        .as(
            "ids named by a test that REQUIREMENTS.md does not list. A renamed requirement leaves"
                + " its old id behind in a display name, and the test then covers nothing")
        .isEmpty();
  }

  @Test
  @DisplayName("no test claims a requirement the document says has no test")
  void claims_do_not_contradict_the_mechanism() {
    final Set<String> contradicted = new LinkedHashSet<>(JavaTests.requirementsClaimed());
    contradicted.retainAll(Requirements.withoutTests());

    assertThat(contradicted)
        .as(
            "ids claimed by a test whose mechanism is compiler or review. Either the test is"
                + " restating a declaration, or the document is wrong about the mechanism")
        .isEmpty();
  }

  @Test
  @DisplayName("a test claiming a requirement asserts something")
  void claims_are_backed_by_an_assertion() {
    final Set<String> hollow =
        JavaTests.all().stream()
            .filter(declaration -> !declaration.requirementsClaimed().isEmpty())
            .filter(declaration -> !declaration.assertsSomething())
            .map(JavaTests.Declaration::describe)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    assertThat(hollow)
        .as(
            "tests that name a requirement and check nothing. An earlier version of this project"
                + " had seventeen of these and its coverage report called them covered")
        .isEmpty();
  }

  private static boolean anImplementationExists() {
    return Repository.filesUnder("java", ".java").stream()
        .filter(path -> path.toString().contains("/src/main/"))
        .map(Repository::contentOf)
        .anyMatch(source -> IMPLEMENTS_ENGINE.matcher(source).find());
  }
}
