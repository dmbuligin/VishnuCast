cmake_minimum_required(VERSION 3.22.1)
project(vcmix)

# Стандарт C++
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# JNI / android system libs
find_library(log-lib     log)
find_library(android-lib android)


add_library(
        vcmix
        SHARED
        mix_adm_stub.cc
)

target_include_directories(vcmix PRIVATE
        ${CMAKE_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(
        vcmix
        ${log-lib}
        ${android-lib}
)


add_library(vishnuadm SHARED vishnu_adm.cc)

# STUB-линковка без WebRTC: so не нужна, т.к. мы не используем webrtc-символы
target_link_libraries(
        vishnuadm
        ${log-lib}
        ${android-lib}
)
