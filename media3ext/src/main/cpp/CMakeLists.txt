# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.22.1)

# Enable C++11 features.
set(CMAKE_CXX_STANDARD 11)

# Declares and names the project.
project(media3ext C CXX)

set(ffmpeg_dir ${CMAKE_SOURCE_DIR}/../../../../ffmpeg/output)
set(ffmpeg_libs ${ffmpeg_dir}/lib/${ANDROID_ABI})

foreach(ffmpeg_lib avutil avcodec swresample swscale)
    add_library(
            ${ffmpeg_lib}
            STATIC
            IMPORTED)
    set_target_properties(
            ${ffmpeg_lib} PROPERTIES
            IMPORTED_LOCATION
            ${ffmpeg_libs}/lib${ffmpeg_lib_name}.a)
endforeach()

include_directories(${ffmpeg_dir}/include/${ANDROID_ABI})
find_library(android_log_lib log)

add_library(${CMAKE_PROJECT_NAME} 
        SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        ffmain.cpp
        ffcommon.cpp
        ffaudio.cpp
        ffvideo.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library
        PRIVATE android
        PRIVATE swscale
        PRIVATE swresample
        PRIVATE avcodec
        PRIVATE avutil
        PRIVATE ${android_log_lib})
        
