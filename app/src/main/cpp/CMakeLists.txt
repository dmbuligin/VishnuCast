cmake_minimum_required(VERSION 3.22)
project(vishnuplayer CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Если нужно — убери оптимизацию/добавь дебаг символы
if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

add_library(
        vishnuplayer
        SHARED
        NativePlayerSource.cpp
        PlayerSendEngine.cpp
        PlayerJniBridge.cpp
)

target_include_directories(vishnuplayer PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
target_link_libraries(vishnuplayer PRIVATE ${log-lib})

# === Подготовка к интеграции с libwebrtc (следующий шаг) ===
# Примерно так будем подключать заголовки из AAR:
# set(WEBRTC_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/../sdk/webrtc/include)  # пример
# target_include_directories(vishnuplayer PRIVATE ${WEBRTC_INCLUDE_DIR})
#
# И линковка *.a/*.so:
# add_library(webrtc STATIC IMPORTED)  # или SHARED IMPORTED
# set_target_properties(webrtc PROPERTIES IMPORTED_LOCATION <path_to_lib>)
# target_link_libraries(vishnuplayer PRIVATE webrtc)
