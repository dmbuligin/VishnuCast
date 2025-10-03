cmake_minimum_required(VERSION 3.22.1)
project(vcmix)

# Стандарт C++
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# JNI / android system libs
find_library(log-lib     log)
find_library(android-lib android)

# ---- Существующая библиотека (не трогаю) ----
add_library(
        vcmix
        SHARED
        mix_adm_jni.cc
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

# ---- НОВАЯ библиотека для B2-native: vishnuadm ----
# Минимальный ADM для режима PLAYER (10 мс @ 48k mono из PlayerPcmBus через JNI)
add_library(
        vishnuadm
        SHARED
        src/main/cpp/vishnu_adm.cc
)

# Если у тебя есть общий каталог инклюдов WebRTC, добавь его здесь:
# target_include_directories(vishnuadm PRIVATE ${WEBRTC_INCLUDE_DIRS})

# Основная webrtc so в типовых сборках называется jingle_peerconnection_so
# Если у тебя другое имя — замени ниже на свой таргет.
target_link_libraries(
        vishnuadm
        jingle_peerconnection_so
        ${log-lib}
        ${android-lib}
)
