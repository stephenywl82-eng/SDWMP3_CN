cmake_minimum_required(VERSION 3.22.1)
project("audio_analyzer")

add_library(audio_analyzer SHARED
    fft_analyzer.cpp
    jni_bridge.cpp
)

target_link_libraries(audio_analyzer
    android
    log
)

target_compile_options(audio_analyzer PRIVATE -O3 -ffast-math)
