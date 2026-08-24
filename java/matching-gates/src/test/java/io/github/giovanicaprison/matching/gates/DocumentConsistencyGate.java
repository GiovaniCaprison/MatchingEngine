package io.github.giovanicaprison.matching.gates;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The build fails when the documents have drifted apart.
 *
 * <p>Review does not catch this class of error. A stale claim reads perfectly well on its own page,
 * and the reader who would notice is the one who remembers the decision that changed. This project
 * has shipped that mistake more than once.
 */
class DocumentConsistencyGate {

  /** An identifier in backticks, which is how a document quotes something the schema defines. */
  private static final Pattern QUOTED = Pattern.compile("`([A-Za-z][A-Za-z0-9_]*)`");

  /** The leading word of a line in the corpus format, which is a directive or an output verb. */
  private static final Pattern CORPUS_WORD = Pattern.compile("(?m)^([A-Z][A-Z]+)\\b");

  @Test
  @DisplayName("every requirement id cited anywhere is one the document lists")
  void cited_requirements_exist() {
    final List<String> unknown =
        citations(Requirements.ID).stream()
            .filter(citation -> !Requirements.ids().contains(citation.id()))
            .map(Citation::describe)
            .toList();

    assertThat(unknown)
        .as("citations of a requirement that REQUIREMENTS.md does not list")
        .isEmpty();
  }

  @Test
  @DisplayName("every principle id cited anywhere is one the document defines")
  void cited_principles_exist() {
    final Set<String> defined = Requirements.principles();
    final List<String> unknown =
        citations(Requirements.PRINCIPLE_ID).stream()
            .filter(citation -> !defined.contains(citation.id()))
            .map(Citation::describe)
            .toList();

    assertThat(unknown).as("citations of a principle that PRINCIPLES.md does not define").isEmpty();
  }

  @Test
  @DisplayName("every message in the schema is described in the protocol document")
  void messages_are_documented() {
    final String protocol = Repository.read("docs/PROTOCOL.md");
    final Set<String> undocumented =
        Schema.messages().stream()
            .filter(message -> !protocol.contains("`" + message + "`"))
            .collect(Collectors.toCollection(LinkedHashSet::new));

    assertThat(undocumented)
        .as(
            "messages the schema defines that PROTOCOL.md never names. A message nobody documents"
                + " is one nobody outside this repository can implement against")
        .isEmpty();
  }

  @Test
  @DisplayName("every identifier the protocol document quotes exists in the schema")
  void quoted_identifiers_exist() {
    final Set<String> names = Schema.names();
    final Set<String> absent =
        QUOTED
            .matcher(Repository.read("docs/PROTOCOL.md"))
            .results()
            .map(result -> result.group(1))
            .filter(quoted -> !names.contains(quoted))
            .collect(Collectors.toCollection(LinkedHashSet::new));

    assertThat(absent)
        .as(
            "identifiers PROTOCOL.md quotes that the schema does not define. This is the direction"
                + " a rename breaks: the schema changes and the prose keeps the old name")
        .isEmpty();
  }

  @Test
  @DisplayName("every message in the schema can be expressed in the corpus format")
  void the_corpus_format_reaches_every_message() {
    final Set<String> words = corpusFormatWords();
    final Set<String> unreachable =
        Schema.messages().stream()
            .filter(message -> words.stream().noneMatch(word -> containsWord(message, word)))
            .collect(Collectors.toCollection(LinkedHashSet::new));

    assertThat(unreachable)
        .as("messages no corpus directive or output verb covers, so no fixture can exercise them")
        .isEmpty();
  }

  @Test
  @DisplayName("every word in the corpus format names a message")
  void corpus_format_words_name_messages() {
    final Set<String> messages = Schema.messages();
    final Set<String> orphaned =
        corpusFormatWords().stream()
            .filter(word -> messages.stream().noneMatch(message -> containsWord(message, word)))
            .collect(Collectors.toCollection(LinkedHashSet::new));

    assertThat(orphaned)
        .as("directives and verbs in the corpus format that match no message in the schema")
        .isEmpty();
  }

  /**
   * A directive is the message's name shortened, so the match is by containment rather than
   * equality. Loose enough that a plausible typo still matches, which is why this is a check on
   * renames and removals and not on spelling.
   */
  private static boolean containsWord(final String message, final String word) {
    return message.toLowerCase().contains(word.toLowerCase());
  }

  /** The leading words of the example fixture in the corpus format section of TESTING.md. */
  private static Set<String> corpusFormatWords() {
    final String testing = Repository.read("docs/TESTING.md");
    final int section = testing.indexOf("## Corpus format");
    if (section < 0) {
      throw new IllegalStateException("TESTING.md no longer has a corpus format section");
    }
    final Set<String> words =
        CORPUS_WORD
            .matcher(testing.substring(section))
            .results()
            .map(result -> result.group(1))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (words.isEmpty()) {
      throw new IllegalStateException("no directives found in the corpus format section");
    }
    return words;
  }

  private record Citation(Path file, int line, String id) {
    String describe() {
      return Repository.describe(file) + ":" + line + " cites " + id;
    }
  }

  private static List<Citation> citations(final Pattern pattern) {
    final List<Citation> found = new ArrayList<>();
    for (final Path file : Repository.citingFiles()) {
      final String content = Repository.contentOf(file);
      int line = 1;
      for (final String text : content.lines().toList()) {
        final int number = line++;
        pattern
            .matcher(text)
            .results()
            .map(MatchResult::group)
            .forEach(id -> found.add(new Citation(file, number, id)));
      }
    }
    return found;
  }
}
