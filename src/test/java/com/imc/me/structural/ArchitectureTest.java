package com.imc.me.structural;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

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

  // TODO (Step 1+), once packages exist:
  //  FR-5.5  : query methods (topOfBook/depth/status) return only immutable types
  //  NFR-4.1 : single-writer contract - matching mutation confined to one package
  //  API-8.* : nothing outside the gateway/validation package calls the matcher
}
