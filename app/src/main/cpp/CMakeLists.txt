cmake_minimum_required(VERSION 3.22.1)
project("gipuinputd" C)

# A standalone executable, run as the shell user through Shizuku's process API.
# It is named lib*.so so that Gradle packages it into lib/arm64-v8a/ and Android extracts it
# (with exec permission) next to the app's other native libraries.
add_executable(gipuinputd gipuinputd.c)
set_target_properties(gipuinputd PROPERTIES OUTPUT_NAME "libgipuinputd.so" SUFFIX "")
target_link_libraries(gipuinputd log)
target_link_options(gipuinputd PRIVATE "-pie" "-Wl,-z,max-page-size=16384")
