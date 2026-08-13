package com.imc.me.structural;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.imc.me.support.Requirement;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * For non-behavioural rules that protect the architecture. DoNotIncludeTests means only production
 * classes are analysed.
 */
@AnalyzeClasses(packages = "com.imc.me", importOptions = ImportOption.DoNotIncludeTests.class)
@Requirement({"NFR-5.1", "API-11.1", "FR-5.5", "NFR-4.1"})
class ArchitectureTest {

  // NFR-5.1: the core engine depends only on itself and the JDK (no frameworks
  // on the hot path). This is the test that proves the dependency-free claim.
  @ArchTest
  static final ArchRule core_depends_only_on_jdk =
      classes()
          .that()
          .resideInAPackage("com.imc.me..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage("com.imc.me..", "java..")
          .allowEmptyShould(true);

  // API-11.1: no public method leaks a mutable collection out of the engine.
  @ArchTest
  static final ArchRule no_public_mutable_list =
      methods().that().arePublic().should().notHaveRawReturnType(List.class).allowEmptyShould(true);

  @ArchTest
  static final ArchRule no_public_mutable_map =
      methods().that().arePublic().should().notHaveRawReturnType(Map.class).allowEmptyShould(true);

  @ArchTest
  static final ArchRule no_public_mutable_set =
      methods().that().arePublic().should().notHaveRawReturnType(Set.class).allowEmptyShould(true);

  // NFR-4.1 (OOD-2): single-writer per book. The absence of thread-safety machinery is the
  // assertion -- its presence would mean someone assumed a book could be shared. Note the
  // rule is inverted from the usual one: we are BANNING concurrency utilities, because a
  // lock-free single writer is faster than any number of threads coordinating on shared state,
  // and because determinism (NFR-1) is free only while there is one writer.
  @ArchTest
  static final ArchRule no_thread_safety_machinery_in_the_core =
      noClasses()
          .that()
          .resideInAPackage("com.imc.me..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("java.util.concurrent..", "java.lang.ref..")
          .allowEmptyShould(true);

  // FR-5.5 (OOD-4): values are immutable. Everything at the edge is a record, an enum, or an
  // interface -- there is exactly ONE mutable class in the system (the order entity) and it is
  // confined to the book package, where its mutators are package-private (OOD-1).
  @ArchTest
  static final ArchRule edge_types_are_immutable =
      classes()
          .that()
          .resideInAnyPackage("com.imc.me.domain..", "com.imc.me.event..")
          .should()
          .beRecords()
          .orShould()
          .beEnums()
          .orShould()
          .beInterfaces()
          .allowEmptyShould(true);

  // TODO (Step 1+), once packages exist:
  //  API-8.* : nothing outside the gateway/validation package calls the matcher
}
