cmake_minimum_required(VERSION 3.22.1)

project(instrumental_engine)

find_package(oboe REQUIRED CONFIG)

set(NAM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/nam")
# NAM architectures register themselves from translation-unit static
# initializers. An OBJECT library guarantees every registrar reaches the final
# Android shared object; a static archive can discard unreferenced parsers.
add_library(nam_core OBJECT
        "${NAM_ROOT}/NAM/activations.cpp"
        "${NAM_ROOT}/NAM/container.cpp"
        "${NAM_ROOT}/NAM/conv1d.cpp"
        "${NAM_ROOT}/NAM/convnet.cpp"
        "${NAM_ROOT}/NAM/dsp.cpp"
        "${NAM_ROOT}/NAM/get_dsp.cpp"
        "${NAM_ROOT}/NAM/linear.cpp"
        "${NAM_ROOT}/NAM/lstm.cpp"
        "${NAM_ROOT}/NAM/ring_buffer.cpp"
        "${NAM_ROOT}/NAM/util.cpp"
        "${NAM_ROOT}/NAM/wavenet/a2_fast.cpp"
        "${NAM_ROOT}/NAM/wavenet/model.cpp"
        "${NAM_ROOT}/NAM/wavenet/slimmable.cpp")
target_compile_features(nam_core PRIVATE cxx_std_20)
target_compile_definitions(nam_core PRIVATE
        NAM_SAMPLE_FLOAT
        NAM_ENABLE_A2_FAST
        NAM_DEFAULT_MAX_BUFFER_SIZE=2048)
target_include_directories(nam_core
        PUBLIC
        "${NAM_ROOT}/NAM"
        "${NAM_ROOT}/Dependencies/nlohmann"
        "${NAM_ROOT}/Dependencies/eigen")

add_library(instrumental_engine SHARED native_engine.cpp)

target_compile_features(instrumental_engine PRIVATE cxx_std_20)
target_compile_definitions(instrumental_engine PRIVATE NAM_SAMPLE_FLOAT)
target_include_directories(instrumental_engine PRIVATE
        "${NAM_ROOT}/NAM"
        "${NAM_ROOT}/Dependencies/nlohmann"
        "${NAM_ROOT}/Dependencies/eigen")

target_link_libraries(
        instrumental_engine
        PRIVATE
        nam_core
        oboe::oboe
        android
        log
)
