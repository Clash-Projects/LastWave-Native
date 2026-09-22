cmake_minimum_required(VERSION 3.22.1)
project("decent_usb_audio" C CXX)

set(CMAKE_CXX_STANDARD 17)

# USB isochronous driver only. LastWave keeps its own Media3 FLAC decoder;
# the decent NativeAudioEngine / libFLAC path is not built.
add_library(decent_usb_audio SHARED
    usb-audio-output.cpp
)

find_library(log-lib log)
target_link_libraries(decent_usb_audio ${log-lib})
