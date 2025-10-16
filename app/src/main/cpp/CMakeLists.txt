cmake_minimum_required(VERSION 3.22)
project(vishnuplayer CXX)

# ===== Настройки компилятора =====
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# ===== Пути к WebRTC (LiveKit SDK + отдельные заголовки) =====
# AAR распакованная папка со .so:  app/src/main/cpp/third_party/webrtc-137.7151.03
set(WEBRTC_SDK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/webrtc-137.7151.03)
# Заголовки M137 (branch-heads/7151): app/src/main/cpp/third_party/webrtc-headers-137.7151
set(WEBRTC_HEADERS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/webrtc-headers-137.7151)

# Проверим, что заголовки существуют (хватит наличия api/)
if (NOT EXISTS "${WEBRTC_HEADERS_DIR}/api")
    message(FATAL_ERROR "WebRTC headers not found at ${WEBRTC_HEADERS_DIR} (expected subdir 'api').")
endif()

# Папка с so внутри AAR: jni/<ABI>/
set(WEBRTC_JNI_DIR "${WEBRTC_SDK_DIR}/jni")
if (NOT EXISTS "${WEBRTC_JNI_DIR}/${ANDROID_ABI}/libjingle_peerconnection_so.so")
    message(FATAL_ERROR "libjingle_peerconnection_so.so not found at ${WEBRTC_JNI_DIR}/${ANDROID_ABI}")
endif()

# ===== Источники нашей нативной библиотеки =====
add_library(
        vishnuplayer
        SHARED
        NativePlayerSource.cpp
        PlayerSendEngine.cpp
        PlayerJniBridge.cpp
        # Если уже добавляли NativePlayerSourceWebRtc.cpp — раскомментируйте:
        # NativePlayerSourceWebRtc.cpp
)

# Подключаем include после объявления таргета
target_include_directories(vishnuplayer PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${WEBRTC_HEADERS_DIR} # наши заголовки WebRTC (api/, rtc_base/, pc/, media/, ...)
)

# Логи Android
find_library(log-lib log)

# ===== Импортируем и линкуем WebRTC so =====
add_library(webrtc SHARED IMPORTED)
set_target_properties(webrtc PROPERTIES
        IMPORTED_LOCATION "${WEBRTC_JNI_DIR}/${ANDROID_ABI}/libjingle_peerconnection_so.so"
)

target_link_libraries(vishnuplayer
        PRIVATE
        webrtc
        ${log-lib}
)
