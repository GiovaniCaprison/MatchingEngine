# Rung three in this language, at a layout matched to the Java rung: the book as a flat ladder in
# rank space, the orders as slots in one slab, the same behaviour to the byte, and a probe whose
# allocator refuses once the ladder is sized (NFR-4.3).

add_library(matching-flyweight STATIC
  auction.cpp
  flyweight_engine.cpp)

target_include_directories(matching-flyweight PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/..)
target_link_libraries(matching-flyweight PUBLIC matching-api matching-protocol)

add_executable(differential-flyweight differential_main.cpp)
target_link_libraries(differential-flyweight PRIVATE matching-flyweight matching-conformance)

add_executable(allocation-probe-flyweight allocation_probe_main.cpp)
target_link_libraries(allocation-probe-flyweight PRIVATE matching-flyweight matching-conformance)
