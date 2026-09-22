cmake_minimum_required(VERSION 3.22.1)
project(ae_usb CXX C)

set(CMAKE_CXX_STANDARD 17)

set(AE_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/upstream)
set(LIBUSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libusb)

add_library(ae_libusb STATIC
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
target_include_directories(ae_libusb PUBLIC
    ${LIBUSB_ROOT}/libusb
    ${LIBUSB_ROOT}/android
    ${LIBUSB_ROOT}/libusb/os
)
target_compile_definitions(ae_libusb PRIVATE _GNU_SOURCE)
target_compile_options(ae_libusb PRIVATE -fvisibility=hidden)

add_library(ae_usb SHARED
    AeUsbJni.cpp
    ${AE_ROOT}/backends/usb/usb_audio.cpp
)
target_include_directories(ae_usb PRIVATE
    ${AE_ROOT}/backends/usb
    ${AE_ROOT}/core/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
)
target_compile_definitions(ae_usb PRIVATE __ANDROID__)
find_library(log-lib log)
target_link_libraries(ae_libusb ${log-lib})
target_link_libraries(ae_usb ae_libusb ${log-lib})
target_link_options(ae_usb PRIVATE "-Wl,-z,max-page-size=16384")
