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
        PlayerSendEngine.cpp
        PlayerJniBridge.cpp
)

# Заголовки — текущая папка
target_include_directories(vishnuplayer PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# Линкуем только с логом — пока без libwebrtc (на следующем шаге добавим)
find_library(log-lib log)
target_link_libraries(vishnuplayer PRIVATE ${log-lib})
