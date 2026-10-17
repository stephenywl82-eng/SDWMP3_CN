cmake_minimum_required(VERSION 3.22.1)
project("oboe_bridge")

# Prebuilt liboboe.so from jniLibs (already in APK)
add_library(oboe SHARED IMPORTED)
set_target_properties(oboe PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/liboboe.so"
)

add_library(oboe_bridge SHARED
    oboe_bridge.cpp
    hrtf_probe.cpp
    hrtf_surround.cpp
    usb_audio_driver.cpp
    usb_audio_jni.cpp
    flac_decoder_jni.cpp
    alac_decoder_jni.cpp
    alac/ALACDecoder.cpp
    alac/ALACBitUtilities.c
    alac/EndianPortable.c
    alac/ag_dec.c
    alac/dp_dec.c
    alac/matrix_dec.c
    bpm_detector_jni.cpp
    ndk_decoder.cpp

)

target_include_directories(oboe_bridge PRIVATE
    "${CMAKE_SOURCE_DIR}"
    "${CMAKE_SOURCE_DIR}/oboe"
    "${CMAKE_SOURCE_DIR}/alac"
)

# ── 【V8.222】Rubber Band（GPL 开源，变速不变调 keylock 引擎，Mixxx 同款）──
# 静态库隔离编译：只有交叉淡化变速路径（默认关）会链接到它，其余链路零接触。
file(GLOB RUBBERBAND_SRC
    "${CMAKE_SOURCE_DIR}/rubberband/src/RubberBandStretcher.cpp"
    "${CMAKE_SOURCE_DIR}/rubberband/src/common/*.cpp"
    "${CMAKE_SOURCE_DIR}/rubberband/src/faster/*.cpp"
    "${CMAKE_SOURCE_DIR}/rubberband/src/finer/*.cpp"
)
list(APPEND RUBBERBAND_SRC
    "${CMAKE_SOURCE_DIR}/rubberband/src/ext/kissfft/kiss_fft.c"
    "${CMAKE_SOURCE_DIR}/rubberband/src/ext/kissfft/kiss_fftr.c"
)
add_library(rubberband STATIC ${RUBBERBAND_SRC})
target_include_directories(rubberband PUBLIC
    "${CMAKE_SOURCE_DIR}/rubberband"
    "${CMAKE_SOURCE_DIR}/rubberband/src"
    "${CMAKE_SOURCE_DIR}/rubberband/src/ext/kissfft"
)
# USE_PTHREADS：Android 上 pthread 在 libc 里；src 内部头（Thread.h/sysutils.h 等）
# 按 src/ 为根互相 include，必须把 src/ 也放进 include 路径。
# HAVE_KISSFFT + kiss_fft_scalar=float：FFT 用捆绑的 kissfft（BSD），单精度。
# USE_BQRESAMPLER：重采样用 RB 自带的 BQResampler（无需外部 speex/libsamplerate）。
target_compile_definitions(rubberband PRIVATE USE_PTHREADS HAVE_KISSFFT kiss_fft_scalar=float USE_BQRESAMPLER)
# RB 的 Allocators.h / StlAllocator 里直接使用 throw / std::bad_alloc，因此它必须开异常。
# build_so_v8118.ps1 为了压体积在 CMAKE_CXX_FLAGS 里加了全局 -fno-exceptions -fno-rtti，
# 那两个开关早于 Rubber Band 引入，会导致 RB 编译失败（"cannot use 'throw' with exceptions
# disabled"）。这里在 target 级重新打开：target 级 options 排在 CMAKE_CXX_FLAGS 之后，
# 后出现的开关生效，故可覆盖全局设置。公开头 RubberBandStretcher.h 不含 throw，
# 所以 oboe_bridge 自身仍可保持 -fno-exceptions，不受影响。
target_compile_options(rubberband PRIVATE -O2 -fexceptions -frtti)
# RB 内部需要 RTTI/异常；上游用 std::thread（无 pthread 依赖）
set_target_properties(rubberband PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)

# 【V9.02 修复】USB DAC 重启崩溃根因（tombstone_08: nativeUsbStart -> std::terminate -> SIGABRT）。
# UsbAudioDriver::start() 内 std::thread 构造在 24bit alt 切换的重启路径上抛出 std::system_error，
# 而 nativeUsbStart 这个 extern "C" JNI 入口没有 try/catch，异常冒泡出原生边界进入 ART 的 C
# 调用帧触发 std::terminate()。build_so_v8118.ps1 当前已默认开启异常（早期 -fno-exceptions
# 已移除），故真正修复在 usb_audio_jni.cpp / usb_audio_driver.cpp 的 try/catch 兜底。
# 此处 per-file 再显式打开异常与 RTTI，作为防御：即便将来全局脚本回退到 -fno-exceptions，
# 这两个会起线程 / 做 JNI 入口的文件仍能正常抛捕而不致整进程崩溃。
set_source_files_properties(
    usb_audio_driver.cpp
    usb_audio_jni.cpp
    PROPERTIES COMPILE_FLAGS "-fexceptions -frtti"
)

# ALAC decoder uses multi-char constants ('alac', 'frma') — silence the warning
set_source_files_properties(
    alac/ALACDecoder.cpp
    alac/ALACBitUtilities.c
    alac/ag_dec.c
    alac/dp_dec.c
    alac/matrix_dec.c
    PROPERTIES COMPILE_FLAGS "-Wno-multichar"
)

target_link_libraries(oboe_bridge
    oboe
    log
    android
    mediandk
    rubberband
)

set_target_properties(oboe_bridge PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)
