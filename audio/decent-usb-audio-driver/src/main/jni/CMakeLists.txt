cmake_minimum_required(VERSION 3.22.1)
project("decent_usb_audio" C CXX)

set(CMAKE_CXX_STANDARD 11)

# Only the USB output core is built here. The native FLAC engine
# (native-audio-engine.cpp) needs the decoder-flac sources, which are not
# part of this integration; playback goes through the ExoPlayer float path.
add_library(decent_usb_audio SHARED
    usb-audio-output.cpp
)

find_library(log-lib log)
target_link_libraries(decent_usb_audio
    ${log-lib}
)
target_link_options(decent_usb_audio PRIVATE "-Wl,-z,max-page-size=16384")
