cmake_minimum_required(VERSION 3.22.1)
project(neri_usb LANGUAGES C CXX)

# ============================================================================
# NeriPlayer USB 独占输出模块（移植）
# 原生源码来自 NeriPlayer（https://github.com/cwuom/NeriPlayer，作者 cwuom / ouom），
# 按 NeriPlayer Native Attribution License 获得闭源使用授权；
# 内嵌 libusb 为 LGPL-2.1-or-later（https://libusb.info）。
# JNI 符号已重命名到 com.sdw.music.player.core.audio.engine.NeriUsbNativeBridge。
# ============================================================================

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT ANDROID)
    message(FATAL_ERROR "neri_usb: this module is Android-only")
endif()

# ── libusb（LGPL-2.1）—— 只编 Android/Linux usbfs 后端 ──
add_library(neri_libusb STATIC
    third_party/libusb/core.c
    third_party/libusb/descriptor.c
    third_party/libusb/hotplug.c
    third_party/libusb/io.c
    third_party/libusb/strerror.c
    third_party/libusb/sync.c
    third_party/libusb/os/linux_usbfs.c
    third_party/libusb/os/linux_netlink.c
    third_party/libusb/os/events_posix.c
    third_party/libusb/os/threads_posix.c
)
set_target_properties(neri_libusb PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(neri_libusb PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/libusb"
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party/libusb/os"
)
# 供外部以 "libusb/libusb.h" 形式 include
target_include_directories(neri_libusb PUBLIC
    "${CMAKE_CURRENT_SOURCE_DIR}/third_party"
)
target_compile_definitions(neri_libusb PRIVATE HAVE_CONFIG_H)
target_compile_options(neri_libusb PRIVATE
    -w
    -ffunction-sections
    -fdata-sections
)

# ── NeriPlayer USB 核心（格式/反馈/PCM/UAC 解析） ──
add_library(neri_usb_core STATIC
    usb/control/usb_feature_unit.cpp
    usb/control/usb_sample_rate_readback.cpp
    usb/exclusive/usb_recovery_action_latch.cpp
    usb/exclusive/usb_runtime_report_v2.cpp
    usb/feedback/usb_explicit_feedback_engine.cpp
    usb/feedback/usb_explicit_feedback_engine_state.cpp
    usb/feedback/usb_explicit_feedback_runtime.cpp
    usb/feedback/usb_feedback_clock.cpp
    usb/feedback/usb_feedback_decoder.cpp
    usb/feedback/usb_feedback_estimator.cpp
    usb/feedback/usb_feedback_in_transfer_set.cpp
    usb/feedback/usb_feedback_packet_scheduler.cpp
    usb/feedback/usb_feedback_rate_math.cpp
    usb/feedback/usb_feedback_stream_gate.cpp
    usb/feedback/usb_feedback_transfer_lifecycle.cpp
    usb/feedback/usb_feedback_types.cpp
    usb/pcm/usb_pcm_codec.cpp
    usb/pcm/usb_pcm_pipeline.cpp
    usb/pcm/usb_pcm_resampler.cpp
    usb/uac1/usb_uac1_format.cpp
    usb/uac2/usb_uac2_candidate_model.cpp
    usb/uac2/usb_uac2_clock_graph.cpp
    usb/uac2/usb_uac2_descriptor_snapshot.cpp
    usb/uac2/usb_uac2_feedback_model.cpp
    usb/uac2/usb_uac2_feedback_profile.cpp
    usb/uac2/usb_uac2_format.cpp
)
set_target_properties(neri_usb_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
# 供外部以 "usb/..." 形式 include
target_include_directories(neri_usb_core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(neri_usb_core PUBLIC neri_libusb)

# ── Android 会话层（含 JNI 桥） ──
add_library(neri_usb_android OBJECT
    usb/exclusive/usb_exclusive_bridge.cpp
    usb/exclusive/usb_exclusive_device_selection.cpp
    usb/exclusive/usb_exclusive_device_session.cpp
    usb/exclusive/usb_exclusive_feature_units.cpp
    usb/exclusive/usb_exclusive_handle_registry.cpp
    usb/exclusive/usb_exclusive_recovery.cpp
    usb/exclusive/usb_exclusive_runtime_report.cpp
    usb/exclusive/usb_exclusive_transfer_run.cpp
    usb/exclusive/usb_exclusive_pcm_playback.cpp
    usb/feedback/usb_libusb_feedback_backend.cpp
)
set_target_properties(neri_usb_android PROPERTIES POSITION_INDEPENDENT_CODE ON)
# 这些文件会起线程 / 做 JNI 入口，显式打开异常与 RTTI，
# 防止全局 -fno-exceptions/-fno-rtti 时 std::thread 抛出导致 nativeUsbStart 类崩溃
# （历史 tombstone_08: nativeUsbStart -> std::terminate -> SIGABRT）。
set_source_files_properties(
    usb/exclusive/usb_exclusive_bridge.cpp
    usb/exclusive/usb_exclusive_device_session.cpp
    usb/exclusive/usb_exclusive_feature_units.cpp
    usb/exclusive/usb_exclusive_handle_registry.cpp
    usb/exclusive/usb_exclusive_recovery.cpp
    usb/exclusive/usb_exclusive_runtime_report.cpp
    usb/exclusive/usb_exclusive_transfer_run.cpp
    usb/exclusive/usb_exclusive_pcm_playback.cpp
    usb/feedback/usb_libusb_feedback_backend.cpp
    PROPERTIES COMPILE_FLAGS "-fexceptions -frtti"
)
target_link_libraries(neri_usb_android PRIVATE neri_usb_core neri_libusb)

# ── 独立 .so（与 oboe_bridge 隔离，先验证编译与运行，后续可合并） ──
add_library(neri_usb SHARED
    $<TARGET_OBJECTS:neri_usb_android>
)
target_link_libraries(neri_usb
    PRIVATE
    neri_usb_core
    neri_libusb
    android
    log
    dl
)
set_target_properties(neri_usb PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)
