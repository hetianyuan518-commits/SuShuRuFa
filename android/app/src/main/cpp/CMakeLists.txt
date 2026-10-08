cmake_minimum_required(VERSION 3.22)
project(susheng_rime)

# 只有设置了 librime 源码路径才真正链接引擎。否则编出的是空实现，键盘走内置拼音。
set(LIBRIME_ROOT "" CACHE PATH "librime 源码根目录")

add_library(susheng_rime SHARED rime_jni.cpp)

find_library(log-lib log)
target_link_libraries(susheng_rime ${log-lib})

add_library(handrec SHARED hand_jni.cpp)
add_library(ncnn SHARED IMPORTED)
set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libncnn.so)
target_include_directories(handrec PRIVATE ${CMAKE_SOURCE_DIR}/third_party)
target_link_libraries(handrec ncnn ${log-lib})

if (LIBRIME_ROOT)
    target_compile_definitions(susheng_rime PRIVATE SUSHENG_HAS_RIME=1)
    target_include_directories(susheng_rime PRIVATE "${LIBRIME_ROOT}/src")
    target_link_libraries(susheng_rime rime)
endif()
