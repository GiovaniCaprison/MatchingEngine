# Rung two's lean twin in this language, so the feature cost question can be asked per language
# at the pooled layout. Its differential binary holds it byte identical to the Java lean engine,
# and its allocation probe holds it to the rung's mechanism (NFR-4.3).

add_library(matching-lean-pooled STATIC
  lean_engine.cpp)

target_include_directories(matching-lean-pooled PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/..)
target_link_libraries(matching-lean-pooled PUBLIC matching-api matching-protocol)

add_executable(differential-lean-pooled differential_main.cpp)
target_link_libraries(differential-lean-pooled PRIVATE matching-lean-pooled matching-conformance)

add_executable(allocation-probe-lean-pooled allocation_probe_main.cpp)
target_link_libraries(allocation-probe-lean-pooled
  PRIVATE matching-lean-pooled matching-conformance)
