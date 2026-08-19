package com.imc.me.structural;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.imc.me.support.Requirement;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Collection;
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

  // API-11.1 (OOD-9), widened: Collection is the loophole the three rules above leave open -- it
  // advertises add/remove just as List does, so returning one leaks the same mutability. Seq is the
  // sanctioned outbound sequence type; it has no mutator to call, so the guarantee is in the type
  // rather than in a runtime UnsupportedOperationException.
  //
  // Iterator is deliberately NOT banned: Seq.iterator() is Iterable's contract, which is what makes
  // a Seq usable in a for-each, and Iterator.remove() defaults to throwing. Banning it would force
  // either a hand-rolled cursor at every call site or an exclusion for Seq -- a rule bent around one
  // class is worse than the narrower rule that is actually true.
  @ArchTest
  static final ArchRule no_public_mutable_collection =
      methods()
          .that()
          .arePublic()
          .should()
          .notHaveRawReturnType(Collection.class)
          .allowEmptyShould(true);

  // NFR-5.1 (OOD-9/OOD-11): no streams in the core. A stream on the hot path is an allocation
  // cascade -- spliterator, pipeline stages, boxed accumulators -- and it hides that cost behind
  // very pleasant syntax. The book walks levels with a plain loop into a sink instead.
  @ArchTest
  static final ArchRule no_streams_on_the_hot_path =
      noClasses()
          .that()
          .resideInAnyPackage("com.imc.me.book..", "com.imc.me.matching..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("java.util.stream..")
          .allowEmptyShould(true);

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

  // FR-5.5 (OOD-4): values are immutable. Every DTO is a record, an enum, or an interface -- the
  // order entity is the one mutable class in the system, and it is confined to the book package
  // where its mutators are package-private (OOD-1).
  //
  // Scoped to the DTO packages rather than all of event.. because event.sink holds the collecting
  // adapters, which are mutable BY DESIGN: accumulating primitive callbacks into immutable values
  // is exactly their job (OOD-9). The rule's intent is "the values crossing the boundary cannot be
  // changed underneath a consumer", and a spent, single-use builder does not cross the boundary.
  @ArchTest
  static final ArchRule dto_types_are_immutable =
      classes()
          .that()
          .resideInAnyPackage(
              "com.imc.me.domain..", "com.imc.me.event.dto..", "com.imc.me.event.result..")
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
