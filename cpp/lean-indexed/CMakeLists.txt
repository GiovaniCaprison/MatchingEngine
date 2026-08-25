# Rung one's lean twin in this language, so the feature cost question
# can be asked per language. Its differential binary is what holds it byte identical to the Java
# lean engine, and to the full rung on the shared remit.

add_library(matching-lean-indexed STATIC
  lean_engine.cpp)

target_include_directories(matching-lean-indexed PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/..)
target_link_libraries(matching-lean-indexed PUBLIC matching-api matching-protocol)

add_executable(differential-lean-indexed differential_main.cpp)
target_link_libraries(differential-lean-indexed PRIVATE matching-lean-indexed matching-conformance)
