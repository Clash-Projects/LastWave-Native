cmake_minimum_required(VERSION 3.22.1)
project("decent_usb_audio" C CXX)

set(CMAKE_CXX_STANDARD 11)

# Only the USB output core is built here. The native FLAC engine
# (native-audio-engine.cpp) needs the decoder-flac sources, which are not
# part of this integration; playback goes through the ExoPlayer float path.
set(LIBUSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libusb)

add_library(usb1.0 STATIC
    ${LIBUSB_ROOT}/libusb/core.c
    ${LIBUSB_ROOT}/libusb/descriptor.c
    ${LIBUSB_ROOT}/libusb/hotplug.c
    ${LIBUSB_ROOT}/libusb/io.c
    ${LIBUSB_ROOT}/libusb/sync.c
    ${LIBUSB_ROOT}/libusb/strerror.c
    ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
    ${LIBUSB_ROOT}/libusb/os/events_posix.c
    ${LIBUSB_ROOT}/libusb/os/threads_posix.c
    ${LIBUSB_ROOT}/libusb/os/linux_netlink.c
)
target_include_directories(usb1.0 PUBLIC
    ${LIBUSB_ROOT}/libusb
    ${LIBUSB_ROOT}/android
    ${LIBUSB_ROOT}/libusb/os
)
target_compile_definitions(usb1.0 PRIVATE _GNU_SOURCE)
target_compile_options(usb1.0 PRIVATE -fvisibility=hidden)
target_link_options(usb1.0 PRIVATE "-Wl,-z,max-page-size=16384")

add_library(decent_usb_audio SHARED
    usb-audio-output.cpp
    LibUsbUac.cpp
)

find_library(log-lib log)
target_link_libraries(usb1.0 ${log-lib})
target_link_libraries(decent_usb_audio
    usb1.0
    ${log-lib}
)
target_link_options(decent_usb_audio PRIVATE "-Wl,-z,max-page-size=16384")
