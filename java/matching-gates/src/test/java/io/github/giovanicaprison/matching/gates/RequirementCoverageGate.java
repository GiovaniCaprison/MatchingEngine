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
 *
 * <p>A requirement is claimed either by a test that names it or by a rule in the corpus that opens
 * with it. Most are the second, since the suite belongs to the specification rather than to any one
 * implementation, and a claim in a fixture is checked the same way: it has to expect an event.
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
    unclaimed.removeAll(claimed());

    assertThat(unclaimed)
        .as("requirements marked unit that nothing claims, in a test name or a rule fixture")
        .isEmpty();
  }

  @Test
  @DisplayName("no test claims a requirement the document does not list")
  void claims_name_real_requirements() {
    final Set<String> unknown = new LinkedHashSet<>(claimed());
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
    final Set<String> contradicted = new LinkedHashSet<>(claimed());
    contradicted.retainAll(Requirements.withoutTests());

    assertThat(contradicted)
        .as(
            "ids claimed by a test whose mechanism is review. Either the test automates a"
                + " judgement it cannot hold, or the document is wrong about the mechanism")
        .isEmpty();
  }

  @Test
  @DisplayName("a test claiming a requirement asserts something")
  void claims_are_backed_by_an_assertion() {
    final Set<String> hollow =
        TestSources.all().stream()
            .filter(declaration -> !declaration.requirementsClaimed().isEmpty())
            .filter(declaration -> !declaration.assertsSomething())
            .map(TestSources.Declaration::describe)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    assertThat(hollow)
        .as(
            "tests that name a requirement and check nothing. An earlier version of this project"
                + " had seventeen of these and its coverage report called them covered")
        .isEmpty();
  }

  @Test
  @DisplayName("a rule claiming a requirement expects an event")
  void rules_are_backed_by_an_expectation() {
    final Set<String> hollow =
        Fixtures.all(verbs()).stream()
            .filter(fixture -> !fixture.requirements().isEmpty())
            .filter(fixture -> !fixture.expectsOutput())
            .map(Fixtures.Fixture::describe)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    assertThat(hollow)
        .as(
            "rules that name a requirement and expect nothing. A fixture with no expected output"
                + " passes against an engine that does nothing at all")
        .isEmpty();
  }

  /** Everything anything claims, whether a test named it or a rule opened with it. */
  private static Set<String> claimed() {
    final Set<String> claimed = new LinkedHashSet<>(TestSources.requirementsClaimed());
    claimed.addAll(Fixtures.requirementsClaimed(verbs()));
    return claimed;
  }

  /** The output verbs, read from the runner rather than kept here as a second list. */
  private static Set<String> verbs() {
    return Repository.enumConstants(Repository.RUNNER + "Verb.java");
  }

  private static boolean anImplementationExists() {
    return Repository.filesUnder("java", ".java").stream()
        .filter(path -> path.toString().contains("/src/main/"))
        .map(Repository::contentOf)
        .anyMatch(source -> IMPLEMENTS_ENGINE.matcher(source).find());
  }
}
