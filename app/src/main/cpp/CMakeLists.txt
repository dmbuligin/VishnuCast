cmake_minimum_required(VERSION 3.22)
project(vishnuplayer CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

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

# (подключение libwebrtc и include-директория — на следующем шаге)
