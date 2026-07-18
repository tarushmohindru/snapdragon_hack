cmake_minimum_required(VERSION 3.22.1)
project("formfusion")

# Import the ML team's prebuilt library (will exist after they hand it over)
add_library(pose SHARED IMPORTED)
set_target_properties(pose PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libpose.so")

# Our thin bridge (empty for now — no source files yet, just the scaffold)
# When we need custom JNI code, .cpp files go here
# add_library(formfusion_bridge SHARED bridge.cpp)
# target_link_libraries(formfusion_bridge pose android log)
