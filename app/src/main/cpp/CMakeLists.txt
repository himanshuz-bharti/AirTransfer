cmake_minimum_required(VERSION 3.22.1)

project(gallerymatcher LANGUAGES CXX)

if (NOT DEFINED OpenCV_DIR OR OpenCV_DIR STREQUAL "")
    message(FATAL_ERROR
            "OpenCV SDK path is not configured. Add "
            "opencv.sdk.dir=<OpenCV-android-sdk folder> to local.properties")
endif ()

if (NOT EXISTS "${OpenCV_DIR}/OpenCVConfig.cmake")
    message(FATAL_ERROR
            "OpenCVConfig.cmake was not found at ${OpenCV_DIR}. "
            "Check opencv.sdk.dir in local.properties")
endif ()

find_package(OpenCV REQUIRED)

add_library(
        gallerymatcher
        SHARED
        native_bridge.cpp
)

target_include_directories(
        gallerymatcher
        PRIVATE
        ${OpenCV_INCLUDE_DIRS}
)

find_library(android-lib android)
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
        gallerymatcher
        ${OpenCV_LIBS}
        ${android-lib}
        ${log-lib}
        ${jnigraphics-lib}
)
