# Catch2 for the same reason JUnit's display names are used on the Java side: a test's name is prose,
# so it can carry the requirement id it covers and the coverage gate can read it.

include(FetchContent)

FetchContent_Declare(
  Catch2
  GIT_REPOSITORY https://github.com/catchorg/Catch2.git
  GIT_TAG v3.7.1
  GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(Catch2)

add_executable(schema-round-trip schema_round_trip.cpp)
target_link_libraries(schema-round-trip PRIVATE matching-protocol Catch2::Catch2WithMain)

add_executable(conformance-format conformance_format.cpp)
target_link_libraries(conformance-format PRIVATE matching-conformance Catch2::Catch2WithMain)

add_executable(corpus-conformance corpus_conformance.cpp)
target_link_libraries(corpus-conformance
  PRIVATE matching-conformance matching-naive Catch2::Catch2WithMain)

add_executable(corpus-indexed corpus_indexed.cpp)
target_link_libraries(corpus-indexed
  PRIVATE matching-conformance matching-indexed Catch2::Catch2WithMain)

add_executable(corpus-subset-lean corpus_subset_lean.cpp)
target_link_libraries(corpus-subset-lean
  PRIVATE matching-conformance matching-lean-naive Catch2::Catch2WithMain)

add_executable(corpus-subset-lean-pooled corpus_subset_lean_pooled.cpp)
target_link_libraries(corpus-subset-lean-pooled
  PRIVATE matching-conformance matching-lean-pooled Catch2::Catch2WithMain)

add_executable(corpus-pooled corpus_pooled.cpp)
target_link_libraries(corpus-pooled
  PRIVATE matching-conformance matching-pooled Catch2::Catch2WithMain)

add_executable(corpus-flyweight corpus_flyweight.cpp)
target_link_libraries(corpus-flyweight
  PRIVATE matching-conformance matching-flyweight Catch2::Catch2WithMain)

add_executable(corpus-subset-lean-indexed corpus_subset_lean_indexed.cpp)
target_link_libraries(corpus-subset-lean-indexed
  PRIVATE matching-conformance matching-lean-indexed Catch2::Catch2WithMain)

add_executable(corpus-subset-lean-flyweight corpus_subset_lean_flyweight.cpp)
target_link_libraries(corpus-subset-lean-flyweight
  PRIVATE matching-conformance matching-lean-flyweight Catch2::Catch2WithMain)

add_executable(harness harness.cpp)
target_link_libraries(harness
  PRIVATE matching-benchmarks matching-naive Catch2::Catch2WithMain)

include(Catch)
catch_discover_tests(schema-round-trip)
catch_discover_tests(conformance-format)
catch_discover_tests(corpus-conformance)
catch_discover_tests(corpus-indexed)
catch_discover_tests(corpus-subset-lean)
catch_discover_tests(corpus-subset-lean-indexed)
catch_discover_tests(corpus-pooled)
catch_discover_tests(corpus-flyweight)
catch_discover_tests(corpus-subset-lean-pooled)
catch_discover_tests(corpus-subset-lean-flyweight)
catch_discover_tests(harness)
