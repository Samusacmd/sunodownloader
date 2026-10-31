cmake_minimum_required(VERSION 3.22.1)
project(sunolame C)

# LAME 3.100 (LGPL) compilato dai sorgenti inclusi in cpp/lame
file(GLOB LAME_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/lame/libmp3lame/*.c)

add_library(sunolame SHARED
        ${LAME_SOURCES}
        lame_jni.c)

target_include_directories(sunolame PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/lame
        ${CMAKE_CURRENT_SOURCE_DIR}/lame/include
        ${CMAKE_CURRENT_SOURCE_DIR}/lame/libmp3lame)

target_compile_definitions(sunolame PRIVATE HAVE_CONFIG_H)
target_compile_options(sunolame PRIVATE -O3 -w)

# Allineamento a 16 KB richiesto dai telefoni Android 15+
target_link_options(sunolame PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(sunolame m)
