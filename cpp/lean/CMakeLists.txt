# The second honest engine in this language, mirroring the Java arm so the feature cost question
# can be asked per language. Its differential binary is what holds it byte identical to the Java
# lean engine, and to the full rung on the shared remit.

add_library(matching-lean STATIC
  lean_engine.cpp)

target_include_directories(matching-lean PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/..)
target_link_libraries(matching-lean PUBLIC matching-api matching-protocol)

add_executable(differential-lean differential_main.cpp)
target_link_libraries(differential-lean PRIVATE matching-lean matching-conformance)
