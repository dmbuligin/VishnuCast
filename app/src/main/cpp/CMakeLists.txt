cmake_minimum_required(VERSION 3.22.1)
project(vishnuplayer)

add_library(vishnuplayer SHARED
        vishnuplayer.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(vishnuplayer
        ${log-lib}
        ${android-lib}
)
