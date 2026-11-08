cmake_minimum_required(VERSION 3.22.1)
project(amigwd C)

add_library(amigwd SHARED amiya_gwd.c)

find_library(log-lib log)
target_link_libraries(amigwd ${log-lib})

set_target_properties(amigwd PROPERTIES
        C_STANDARD 11
        C_STANDARD_REQUIRED ON)
