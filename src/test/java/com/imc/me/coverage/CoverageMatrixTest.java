package com.imc.me.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.imc.me.support.Requirement;
import com.imc.me.support.TestTags;
import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * This test scans the classpath for every {@link Requirement} annotation and diffs the two:
 *
 * <ul>
 *   <li>everyRequirementHasATest - a claimable requirement with no test fails.
 *   <li>noTrimmedRequirementIsTested - a deliberately-cut requirement that gains a test fails.
 *   <li>noUnknownRequirementClaimed - a test claiming an ID absent from the inventory fails.
 * </ul>
 *
 * <p>A human-readable matrix is written to target/coverage-matrix.md on every run.
 */
@Tag(TestTags.FAST)
@DisplayName("Coverage | every requirement is traceable to a test")
class CoverageMatrixTest {

  /** Layers whose requirements MUST be claimed by a @Requirement annotation. */
  private static final Set<String> CLAIMABLE_LAYERS =
      Set.of("explicit", "golden", "property", "structural");

  private static final String INVENTORY = "requirements.txt";
  private static final String SCAN_PACKAGE = "com.imc.me";

  // requirement id -> layer, parsed from requirements.txt
  private static Map<String, String> inventory;
  // requirement id -> set of test sites that claim it (for the report)
  private static Map<String, Set<String>> claims;

  @BeforeAll
  static void loadAndScan() {
    inventory = parseInventory();
    claims = scanClaims();
    writeReport();
  }

  @Test
  @DisplayName("every claimable requirement (explicit/golden/property/structural) has a test")
  void everyRequirementHasATest() {
    List<String> missing =
        inventory.entrySet().stream()
            .filter(e -> CLAIMABLE_LAYERS.contains(e.getValue()))
            .map(Map.Entry::getKey)
            .filter(id -> !claims.containsKey(id))
            .sorted()
            .toList();

    assertThat(missing)
        .as(
            "Requirements in requirements.txt with NO @Requirement test claiming them.%n"
                + "Add a test (or move the ID to the benchmark/trimmed section): %s",
            missing)
        .isEmpty();
  }

  @Test
  @DisplayName("no trimmed requirement has been re-introduced as a test")
  void noTrimmedRequirementIsTested() {
    List<String> resurrected =
        claims.keySet().stream()
            .filter(id -> "trimmed".equals(inventory.get(id)))
            .sorted()
            .toList();

    assertThat(resurrected)
        .as(
            "These IDs were deliberately TRIMMED but a test now claims them.%n"
                + "Either un-trim them in requirements.txt or remove the @Requirement: %s",
            resurrected)
        .isEmpty();
  }

  @Test
  @DisplayName("no test claims a requirement absent from the inventory (typo / orphan guard)")
  void noUnknownRequirementClaimed() {
    List<String> unknown =
        claims.keySet().stream().filter(id -> !inventory.containsKey(id)).sorted().toList();

    assertThat(unknown)
        .as(
            "These IDs are claimed by a @Requirement but are NOT in requirements.txt.%n"
                + "Fix the typo or add them to the inventory: %s",
            unknown)
        .isEmpty();
  }

  private static Map<String, String> parseInventory() {
    Map<String, String> out = new LinkedHashMap<>();
    try (InputStream in =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(INVENTORY)) {
      if (in == null) {
        throw new IllegalStateException("Cannot find " + INVENTORY + " on the test classpath");
      }
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      for (String raw : text.split("\n")) {
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#")) continue;
        String[] parts = line.split("\\|");
        if (parts.length < 2) continue;
        String id = parts[0].strip();
        String layer = parts[1].strip();
        out.put(id, layer);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out;
  }

  private static Map<String, Set<String>> scanClaims() {
    Map<String, Set<String>> out = new TreeMap<>();
    String ann = Requirement.class.getName();
    try (ScanResult scan =
        new ClassGraph()
            .enableClassInfo()
            .enableMethodInfo()
            .enableAnnotationInfo()
            .acceptPackages(SCAN_PACKAGE)
            .scan()) {

      // class-level @Requirement (golden / property / structural)
      for (ClassInfo ci : scan.getClassesWithAnnotation(ann)) {
        AnnotationInfo ai = ci.getAnnotationInfo(ann);
        for (String id : idsOf(ai)) {
          out.computeIfAbsent(id, k -> new TreeSet<>()).add(ci.getSimpleName());
        }
      }
      // method-level @Requirement (explicit)
      for (ClassInfo ci : scan.getClassesWithMethodAnnotation(ann)) {
        for (MethodInfo mi : ci.getMethodInfo()) {
          AnnotationInfo ai = mi.getAnnotationInfo(ann);
          if (ai == null) continue;
          for (String id : idsOf(ai)) {
            out.computeIfAbsent(id, k -> new TreeSet<>())
                .add(ci.getSimpleName() + "#" + mi.getName());
          }
        }
      }
    }
    return out;
  }

  /** Extract the String[] value() from a ClassGraph AnnotationInfo, defensively. */
  private static List<String> idsOf(AnnotationInfo ai) {
    if (ai == null) return List.of();
    Object value = ai.getParameterValues().getValue("value");
    if (value == null) return List.of();
    List<String> ids = new ArrayList<>();
    if (value instanceof Object[] arr) {
      for (Object o : arr) if (o != null) ids.add(o.toString());
    } else {
      ids.add(value.toString());
    }
    return ids;
  }

  private static void writeReport() {
    StringBuilder sb = new StringBuilder();
    sb.append("# Requirement Coverage Matrix\n\n");
    sb.append("Generated by CoverageMatrixTest. ")
        .append("Source of truth: src/test/resources/requirements.txt\n\n");

    Map<String, List<String>> byLayer =
        inventory.keySet().stream()
            .collect(Collectors.groupingBy(inventory::get, TreeMap::new, Collectors.toList()));

    for (var layerEntry : byLayer.entrySet()) {
      String layer = layerEntry.getKey();
      sb.append("## ").append(layer).append("\n\n");
      sb.append("| Requirement | Status | Claimed by |\n");
      sb.append("|---|---|---|\n");
      for (String id : layerEntry.getValue().stream().sorted().toList()) {
        Set<String> sites = claims.getOrDefault(id, Set.of());
        String status;
        if ("trimmed".equals(layer)) {
          status = sites.isEmpty() ? "trimmed (ok)" : "TRIMMED BUT TESTED";
        } else if ("benchmark".equals(layer)) {
          status = sites.isEmpty() ? "deferred (JMH)" : "covered";
        } else {
          status = sites.isEmpty() ? "MISSING" : "covered";
        }
        sb.append("| ")
            .append(id)
            .append(" | ")
            .append(status)
            .append(" | ")
            .append(sites.isEmpty() ? "-" : String.join(", ", sites))
            .append(" |\n");
      }
      sb.append("\n");
    }

    try {
      Path target = Path.of("target");
      Files.createDirectories(target);
      Files.writeString(
          target.resolve("coverage-matrix.md"), sb.toString(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      // Report writing is best-effort; never fail the build because of it.
      System.err.println("coverage-matrix.md not written: " + e.getMessage());
    }
  }
}
