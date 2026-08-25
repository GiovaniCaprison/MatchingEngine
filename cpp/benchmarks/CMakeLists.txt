# The measurement harness in this language, mirroring the Java one artifact for artifact: the same
# manifest, the same measurement.json keys, the same METIMES1 and METYPES1 files, so one analysis
# script reads a campaign without caring which language took which run.
#
# Profile guidance and link time optimisation are options here because METHODOLOGY requires the
# headline C++ numbers to carry them, and requires recording them per run: the flags are compiled
# into the binary, which then writes them into every manifest it produces.

set(MATCHING_PGO "" CACHE STRING "profile guided optimisation: empty, generate, or use")
option(MATCHING_LTO "link time optimisation for the measured binaries" OFF)

add_executable(benchmarks runner_main.cpp)
target_link_libraries(benchmarks PRIVATE matching-api matching-conformance matching-naive matching-indexed matching-pooled matching-lean-naive matching-lean-indexed)
target_include_directories(benchmarks PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/..)

if(MATCHING_LTO)
  set_property(TARGET benchmarks PROPERTY INTERPROCEDURAL_OPTIMIZATION TRUE)
endif()

if(MATCHING_PGO STREQUAL "generate")
  target_compile_options(benchmarks PRIVATE -fprofile-generate)
  target_link_options(benchmarks PRIVATE -fprofile-generate)
elseif(MATCHING_PGO STREQUAL "use")
  target_compile_options(benchmarks PRIVATE -fprofile-use -fprofile-correction)
  target_link_options(benchmarks PRIVATE -fprofile-use)
endif()

target_compile_definitions(benchmarks PRIVATE
  MATCHING_BUILD_FLAGS="${CMAKE_BUILD_TYPE} LTO=${MATCHING_LTO} PGO=${MATCHING_PGO}")

# The harness headers as a library for the tests, carrying the same build flag record.
add_library(matching-benchmarks INTERFACE)
target_include_directories(matching-benchmarks INTERFACE ${CMAKE_CURRENT_SOURCE_DIR}/..)
target_link_libraries(matching-benchmarks INTERFACE matching-api matching-conformance matching-protocol)
