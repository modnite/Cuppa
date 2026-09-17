# CMakeLists.txt for Cuppa Native (cups-core)
#
# Phase 1: Compile full CUPS client library (AOSP 2.2.9 source) into cuppa_native.

cmake_minimum_required(VERSION 3.22.1)
project("cuppa_native" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/cups
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/openssl/include/${ANDROID_ABI}
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/openssl/include/common
)

# CUPS compilation definitions
add_definitions(
    -D_PPD_DEPRECATED=
    -D_CUPS_SOURCE
    # Real TLS client support, backed by a statically-linked OpenSSL 1.1.1 built per-ABI (see
    # third_party/openssl/ and cups/tls.c's HAVE_OPENSSL branch, which pulls in
    # cups/tls-boringssl.c — its OpenSSL 1.1.0+ API usage works unmodified against real OpenSSL).
    # Without this, printers that require/prefer a secure connection (HTTP 426 Upgrade Required)
    # could never be printed to — reproduced live against a real network printer.
    -DHAVE_SSL=1
    -DHAVE_OPENSSL=1
)

# CUPS source files from AOSP
set(CUPS_SOURCES
    cups/array.c
    cups/auth.c
    cups/backchannel.c
    cups/debug.c
    cups/dest.c
    cups/dest-job.c
    cups/dest-localization.c
    cups/dest-options.c
    cups/dir.c
    cups/encode.c
    cups/file.c
    cups/getdevices.c
    cups/getputfile.c
    cups/globals.c
    cups/hash.c
    cups/http.c
    cups/http-addr.c
    cups/http-addrlist.c
    cups/http-support.c
    cups/ipp.c
    cups/ipp-support.c
    cups/langprintf.c
    cups/language.c
    cups/md5.c
    cups/md5passwd.c
    cups/notify.c
    cups/options.c
    cups/pwg-media.c
    cups/raster-error.c
    cups/raster-stream.c
    cups/raster-stubs.c
    cups/request.c
    cups/sidechannel.c
    cups/snmp.c
    cups/string.c
    cups/tempfile.c
    cups/thread.c
    cups/tls.c
    cups/transcode.c
    cups/usersys.c
    cups/util.c
)

# Shared library
add_library(
    cuppa_native
    SHARED
    cups_jni.cpp
    cups_server.cpp
    cups_ipp.cpp
    ${CUPS_SOURCES}
)

# Suppress benign warnings in upstream CUPS C code
target_compile_options(cuppa_native PRIVATE
    $<$<COMPILE_LANGUAGE:C>:
        -Wno-unused-parameter
        -Wno-unused-function
        -Wno-unused-variable
        -Wno-unused-value
        -Wno-sign-compare
        -Wno-missing-field-initializers
        -Wno-deprecated-declarations
        -Wno-format
    >
)

# Link against Android log library and zlib
find_library(log-lib log)
find_library(z-lib z)

# Prebuilt static OpenSSL 1.1.1 (see third_party/openssl/); libssl depends on libcrypto so it
# must come first in link order.
set(OPENSSL_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/openssl/lib/${ANDROID_ABI})

target_link_libraries(
    cuppa_native
    ${OPENSSL_LIB_DIR}/libssl.a
    ${OPENSSL_LIB_DIR}/libcrypto.a
    ${log-lib}
    ${z-lib}
)
