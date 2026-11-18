cmake_minimum_required(VERSION 3.22.1)
project(framecut_vulkan LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)
find_library(android-lib android)

add_library(framecut_vulkan SHARED
    vulkan_pipeline.cpp
    jni_bridge.cpp
)

target_include_directories(framecut_vulkan PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_definitions(framecut_vulkan PRIVATE
    VK_USE_PLATFORM_ANDROID_KHR
)

target_link_libraries(framecut_vulkan
    ${android-lib}
    ${log-lib}
    dl
)
