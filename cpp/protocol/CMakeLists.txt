# The codecs, generated from the same schema the Java side generates from.
#
# The generator is a Java tool, so it is fetched by exact version and checksum rather than expected to
# be installed. Both jars land in the build directory, which makes every build after the first one
# offline: a measurement instance does not need to reach the internet to compile.

set(SBE_VERSION 1.34.1)
set(SBE_SHA256 f510861f1307e8480a86748b09fdc1c8e5a6164ee306f00b0becdfe4885e740d)
set(AGRONA_VERSION 2.2.1)
set(AGRONA_SHA256 49d7dd589b5cdd3aa3ae57212c244c8d2f0c42e2b4c7a1de86f2571825662ee7)

set(MAVEN_CENTRAL https://repo1.maven.org/maven2)
set(JARS ${CMAKE_CURRENT_BINARY_DIR}/tools)
set(SBE_JAR ${JARS}/sbe-tool-${SBE_VERSION}.jar)
set(AGRONA_JAR ${JARS}/agrona-${AGRONA_VERSION}.jar)

find_package(Java REQUIRED COMPONENTS Runtime)

file(DOWNLOAD
  ${MAVEN_CENTRAL}/uk/co/real-logic/sbe-tool/${SBE_VERSION}/sbe-tool-${SBE_VERSION}.jar
  ${SBE_JAR}
  EXPECTED_HASH SHA256=${SBE_SHA256})

file(DOWNLOAD
  ${MAVEN_CENTRAL}/org/agrona/agrona/${AGRONA_VERSION}/agrona-${AGRONA_VERSION}.jar
  ${AGRONA_JAR}
  EXPECTED_HASH SHA256=${AGRONA_SHA256})

set(GENERATED ${CMAKE_CURRENT_BINARY_DIR}/generated)
set(NAMESPACE_DIR ${GENERATED}/io_github_giovanicaprison_matching_protocol)
set(SENTINEL ${NAMESPACE_DIR}/NewOrder.h)

add_custom_command(
  OUTPUT ${SENTINEL}
  COMMAND ${CMAKE_COMMAND} -E make_directory ${GENERATED}
  COMMAND ${Java_JAVA_EXECUTABLE}
          -Dsbe.output.dir=${GENERATED}
          -Dsbe.target.language=Cpp
          -cp ${SBE_JAR}:${AGRONA_JAR}
          uk.co.real_logic.sbe.SbeTool
          ${MATCHING_SCHEMA}
  DEPENDS ${MATCHING_SCHEMA}
  COMMENT "Generating C++ codecs from the schema"
  VERBATIM)

add_custom_target(matching-protocol-codecs DEPENDS ${SENTINEL})

add_library(matching-protocol INTERFACE)
add_dependencies(matching-protocol matching-protocol-codecs)
target_include_directories(matching-protocol INTERFACE ${GENERATED})
