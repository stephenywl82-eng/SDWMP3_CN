NOTICE — third-party native source code
=======================================

This directory contains native source code ported from NeriPlayer.

  Project:   NeriPlayer
  Homepage:  https://github.com/cwuom/NeriPlayer
  Author:    cwuom / ouom
  License:   The `usb/` tree (and upstream `dsp/`, `crash/`, `cmake/`) is covered by the
             NeriPlayer Native Attribution License (see the upstream repository
             modules/native/src/main/cpp/README.md), which permits use in other
             projects — including closed-source ones — with attribution. The
             repository root LICENSE is GPL-3.0.

  Ported files: usb/ (control, exclusive, feedback, iso, pcm, uac1, uac2),
                cmake/NeriNativeCompileOptions.cmake
  Changes:      JNI symbol names were renamed from
                Java_moe_ouom_neriplayer_core_player_usb_transport_UsbExclusiveNativeBridge_*
                to
                Java_com_sdw_music_player_core_audio_engine_NeriUsbNativeBridge_*
                so the native bridge binds to this application's package.
                dsp/ and crash/ were NOT ported (this app has its own).

libusb
======

third_party/libusb is the libusb-1.0 sources (LGPL-2.1-or-later),
https://libusb.info . Only the Android/Linux usbfs backend is compiled
(core.c, descriptor.c, hotplug.c, io.c, strerror.c, sync.c,
os/linux_usbfs.c, os/linux_netlink.c, os/events_posix.c, os/threads_posix.c).
