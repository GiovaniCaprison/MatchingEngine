# Rung three's lean twin in this language, so the feature cost question can be asked per language
# at the flyweight layout. Its differential binary holds it byte identical to the Java lean
# engine, and its allocation probe holds it to the rung's mechanism (NFR-4.3).

add_library(matching-lean-flyweight STATIC
  lean_engine.cpp)

target_include_directories(matching-lean-flyweight PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/..)
target_link_libraries(matching-lean-flyweight PUBLIC matching-api matching-protocol)

add_executable(differential-lean-flyweight differential_main.cpp)
target_link_libraries(differential-lean-flyweight
  PRIVATE matching-lean-flyweight matching-conformance)

add_executable(allocation-probe-lean-flyweight allocation_probe_main.cpp)
target_link_libraries(allocation-probe-lean-flyweight
  PRIVATE matching-lean-flyweight matching-conformance)
