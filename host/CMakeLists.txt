# builds FEXCore for android/bionic arm64.
#
# FEX's own top-level CMakeLists refuses to configure for android:
#   "Unsupported system type Android. FEX only supports Linux and Windows."
# but that guard lives only in the top level. FEXCore/ is a standalone project() with its
# own architecture detection and no references to parent targets, so we can assemble the
# dependency graph ourselves and add FEXCore directly.
#
# doing it this way keeps every line of our build glue in our tree and leaves the FEX
# checkout pristine -- which matters, because FEX bans AI-generated contributions, so any
# patch we made to it could never go upstream and would be a permanent private delta.

cmake_minimum_required(VERSION 3.22)
project(fexcore-android CXX C ASM)

# the submodule is the intended source. the sibling checkout beside the repository is a fallback so
# a workspace that predates the submodule still builds; whichever answers is described below and
# named in what the build produces, because the two are not obliged to be at the same commit.
set(FEX_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../external/FEX" CACHE PATH "Path to the FEX checkout")
get_filename_component(FEX_ROOT "${FEX_ROOT}" ABSOLUTE)
if (NOT EXISTS "${FEX_ROOT}/FEXCore/CMakeLists.txt")
  get_filename_component(FEX_FALLBACK "${CMAKE_CURRENT_SOURCE_DIR}/../../FEX" ABSOLUTE)
  if (EXISTS "${FEX_FALLBACK}/FEXCore/CMakeLists.txt")
    set(FEX_ROOT "${FEX_FALLBACK}")
    message(STATUS "FEX: using the workspace checkout at ${FEX_ROOT}")
  endif()
endif()

if (NOT EXISTS "${FEX_ROOT}/FEXCore/CMakeLists.txt")
  message(FATAL_ERROR
    "FEX not found at ${FEX_ROOT}. run:\n"
    "  git submodule update --init --recursive")
endif()
if (NOT EXISTS "${FEX_ROOT}/External/vixl/CMakeLists.txt")
  message(FATAL_ERROR
    "FEX submodules are not populated. run:\n"
    "  git -C ${FEX_ROOT} submodule update --init --recursive")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# FEXCore/Source calls helper macros that the top-level CMakeLists would normally have
# defined for it. pull them in from the same place FEX does.
list(APPEND CMAKE_MODULE_PATH "${FEX_ROOT}/Data/CMake/")
include(LinkerGC)
include(CheckIncludeFiles)
include(CheckCXXCompilerFlag)
include(CheckCXXSourceCompiles)

# FEXCore keys off these rather than inspecting the toolchain itself.
set(ARCHITECTURE_arm64 1)
add_compile_definitions(ARCHITECTURE_arm64=1)
add_compile_definitions(_M_ARM_64=1)

# bionic gaps, patched in from our side rather than by editing FEX. see the header for why.
#
# the header is referenced by bare filename and found via the include path, deliberately: an
# absolute path in -include arrives at the compiler unquoted and splits at the first space, so a
# checkout anywhere with a space in its path fails to compile. -I directories cmake quotes for us.
#
# CXX only: the .c files in cephes/SoftFloat and the ASM sources neither need it nor would
# tolerate a C++ header. SHELL: keeps "-include <file>" together as one option group so cmake
# cannot split or de-duplicate the two halves.
include_directories("${CMAKE_CURRENT_SOURCE_DIR}/include")
add_compile_options("$<$<COMPILE_LANGUAGE:CXX>:SHELL:-include bionic-compat.h>")

# --- knobs the FEX subdirectories read ------------------------------------------------
# glibc-specific allocator machinery: off. bionic's allocator is what we have.
set(ENABLE_JEMALLOC_GLIBC_ALLOC FALSE CACHE BOOL "" FORCE)
set(ENABLE_FEX_ALLOCATOR FALSE CACHE BOOL "" FORCE)
set(ENABLE_OFFLINE_TELEMETRY FALSE CACHE BOOL "" FORCE)
set(ENABLE_FEXCORE_PROFILER FALSE CACHE BOOL "" FORCE)
set(ENABLE_GDB_SYMBOLS FALSE CACHE BOOL "" FORCE)
set(ENABLE_VIXL_SIMULATOR FALSE CACHE BOOL "" FORCE)
set(ENABLE_VIXL_DISASSEMBLER FALSE CACHE BOOL "" FORCE)
set(ENABLE_ZYDIS FALSE CACHE BOOL "" FORCE)
set(ENABLE_LTO FALSE CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_STEAM_SUPPORT FALSE CACHE BOOL "" FORCE)

# --- the FEX identity compiled into the host layer -------------------------------------
#
# FEXCore generates `generated/git_version.h` from GIT_DESCRIBE_STRING and GIT_HASH_ARRAY and sets
# neither of them itself. OVERRIDE_VERSION and OVERRIDE_HASH, which are what a FEX build would
# normally answer that with, are read only by FEX's top-level CMakeLists -- the one file this build
# deliberately does not use -- so setting those here names a value nothing reads, and the header
# comes out with an empty version and a zeroed hash. these are the variables it actually consumes.
#
# **both are load-bearing and neither may be invented.** the version is what the app reports and
# what the guest reads out of its CPUID leaf; the hash is what CodeCache compares a persisted cache
# against, so a fabricated one is identical across every build and lets a cache written by some
# other FEX be accepted as this one's. and a version hardcoded here states something about the
# checkout that stops being true the moment the submodule moves, with nothing anywhere to say so.
#
# an absent or untagged checkout leaves them empty, which is the degradation FEX's own build takes:
# the version becomes an empty string and the hash twenty zero bytes.
find_package(Git QUIET)
set(GIT_DESCRIBE_STRING "")
set(GIT_HASH "")
if (GIT_FOUND)
  # --dirty is the marker this repository should never see: FEX is a pinned submodule that is never
  # modified, so a suffix here is that rule having been broken rather than an ordinary working tree.
  # --abbrev=7 is what holds an untagged commit's description inside the 32 characters CPUID.cpp
  # asserts against.
  execute_process(
    COMMAND ${GIT_EXECUTABLE} describe --tags --dirty --abbrev=7
    WORKING_DIRECTORY "${FEX_ROOT}"
    OUTPUT_VARIABLE GIT_DESCRIBE_STRING
    ERROR_QUIET
    OUTPUT_STRIP_TRAILING_WHITESPACE)
  # the whole hash and never an abbreviation. CodeCache's header field is twenty bytes wide and the
  # generated array is asserted against it, so a short one is a compile error rather than a subtly
  # weaker key.
  execute_process(
    COMMAND ${GIT_EXECUTABLE} rev-parse HEAD
    WORKING_DIRECTORY "${FEX_ROOT}"
    OUTPUT_VARIABLE GIT_HASH
    ERROR_QUIET
    OUTPUT_STRIP_TRAILING_WHITESPACE)
endif()
string(REGEX REPLACE "(..|.$)" "0x\\1, " GIT_HASH_ARRAY "${GIT_HASH}")
message(STATUS "FEX version: ${GIT_DESCRIBE_STRING}")

# vixl: we only ever emit aarch64, and its tests/tools pull in host-only deps.
set(VIXL_TARGET_AARCH64 ON CACHE BOOL "" FORCE)
set(VIXL_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(VIXL_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(VIXL_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

set(FMT_TEST OFF CACHE BOOL "" FORCE)
set(FMT_DOC OFF CACHE BOOL "" FORCE)
set(XXHASH_BUILD_XXHSUM OFF CACHE BOOL "" FORCE)

# every dependency links statically into the one FEXCore shared object we ship. left on, fmt
# builds itself as libfmt.so and fails to link against libc++, and we would be shipping a
# pile of .so files into the APK for no reason.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# --- dependency graph, in the order the top-level FEX CMakeLists builds it -------------
add_subdirectory("${FEX_ROOT}/External/unordered_dense"        External/unordered_dense)
add_subdirectory("${FEX_ROOT}/External/vixl"                   External/vixl)
add_subdirectory("${FEX_ROOT}/External/xxhash/cmake_unofficial" External/xxhash)
add_subdirectory("${FEX_ROOT}/External/fmt"                    External/fmt)
add_subdirectory("${FEX_ROOT}/External/range-v3"               External/range-v3)
add_subdirectory("${FEX_ROOT}/External/tiny-json"              External/tiny-json)
add_subdirectory("${FEX_ROOT}/External/SoftFloat-3e"           External/SoftFloat-3e)
add_subdirectory("${FEX_ROOT}/External/cephes"                 External/cephes)
add_subdirectory("${FEX_ROOT}/FEXHeaderUtils"                  FEXHeaderUtils)
add_subdirectory("${FEX_ROOT}/CodeEmitter"                     CodeEmitter)
add_subdirectory("${FEX_ROOT}/FEXCore"                         FEXCore)

# --- what we actually want out of the above --------------------------------------------
#
# FEXCore/Source builds two libraries from the same objects: libFEXCore.a and libFEXCore.so.
# neither links FEXCore_Base (the static half holding Config, LogMan, the allocator hooks and
# SoftFloat) except under MinGW -- on linux the .so is *meant* to ship with those symbols
# undefined, and the final FEXInterpreter executable satisfies them at load time.
#
# android's linker does not allow that: the NDK passes -Wl,--no-undefined for shared
# libraries, so libFEXCore.so cannot link and never will. that is fine, because the host layer
# wants the static library regardless -- FEXCore is linked directly into the single bionic .so we
# ship, together with the rest of the host layer. so drop the .so from the default target.
set_target_properties(FEXCore_shared PROPERTIES EXCLUDE_FROM_ALL TRUE)

# and make the static target self-describing, so whatever links FEXCore picks up FEXCore_Base
# (and transitively fmt, vixl, xxhash, SoftFloat, cephes) without having to know it must.
target_link_libraries(FEXCore INTERFACE FEXCore_Base)

# --- our host layer ---------------------------------------------------------------------
#
# built twice, from one set of objects, because the app was needed without giving up the
# shell binary. everything the host layer does is in the static library; the two things on top of
# it are entry points and nothing else:
#
#   sharpdroid-host-layer         an executable. every measurement taken before the app came through it,
#                               regression.sh runs it, and it is a far better place to bisect a
#                               JIT problem from than an activity is.
#   libsharpdroid-host-layer.so   the JNI surface the APK loads.
#
# the host layer itself never assumed it was a process, which is why this is a build change and
# an entry file rather than a rewrite.
add_library(host-layer-core STATIC
  "${CMAKE_CURRENT_SOURCE_DIR}/src/host_layer.cpp"
  "${CMAKE_CURRENT_SOURCE_DIR}/src/audio_thunk.cpp"
  "${CMAKE_CURRENT_SOURCE_DIR}/src/boot_progress.cpp"
  "${CMAKE_CURRENT_SOURCE_DIR}/src/elf_loader.cpp"
  "${CMAKE_CURRENT_SOURCE_DIR}/src/linux_syscalls.cpp"
  "${CMAKE_CURRENT_SOURCE_DIR}/src/guest_files.cpp"
  "${CMAKE_CURRENT_SOURCE_DIR}/src/guest_log.cpp"
  "${CMAKE_CURRENT_SOURCE_DIR}/src/guest_procfs.cpp"
  "${CMAKE_CURRENT_SOURCE_DIR}/src/host_features.cpp"
  # the window of printed lines a viewer reads. it links into the shell binary too, where nothing
  # fills it -- the log pump is the library's, so there the ring stays empty and unallocated.
  "${CMAKE_CURRENT_SOURCE_DIR}/src/log_ring.cpp"
  "${CMAKE_CURRENT_SOURCE_DIR}/src/pad_bridge.cpp"
  # the java side of the guest file layer. it links into the shell binary too, where there is no
  # JavaVM to hand it -- Available() answers false there and the mount is refused, which is a better
  # answer than a second build configuration would be.
  "${CMAKE_CURRENT_SOURCE_DIR}/src/saf_bridge.cpp"
  "${CMAKE_CURRENT_SOURCE_DIR}/src/guest_signals.cpp"
  "${CMAKE_CURRENT_SOURCE_DIR}/src/guest_threads.cpp"
  "${CMAKE_CURRENT_SOURCE_DIR}/src/vma_tracker.cpp"
  "${CMAKE_CURRENT_SOURCE_DIR}/src/vulkan_thunk.cpp")
# --- libadrenotools ----------------------------------------------------------------------
#
# built separately by scripts/build-adrenotools.py rather than added as a subdirectory here, and the
# reason is the STL. the hook libraries are dlopened into an *isolated* linker namespace, so they
# are built against c++_static and carry their own; this build uses c++_shared. one ANDROID_STL
# setting cannot be two things, so the two builds stay two builds and only the static archives
# cross over -- which is safe, because an archive records no STL choice of its own.
set(ADRENOTOOLS_BUILD "${CMAKE_CURRENT_SOURCE_DIR}/../build/adrenotools")

# same submodule-first, workspace-fallback rule as FEX above.
get_filename_component(ADRENOTOOLS_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/../external/libadrenotools/include" ABSOLUTE)
if (NOT EXISTS "${ADRENOTOOLS_INCLUDE}")
  get_filename_component(ADRENOTOOLS_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/../../libadrenotools/include" ABSOLUTE)
endif()
if (NOT EXISTS "${ADRENOTOOLS_INCLUDE}")
  message(FATAL_ERROR
    "libadrenotools headers not found. run:\n"
    "  git submodule update --init --recursive")
endif()
if (NOT EXISTS "${ADRENOTOOLS_BUILD}/libadrenotools.a")
  message(FATAL_ERROR
    "libadrenotools is not built. run:\n"
    "  py scripts/build-adrenotools.py")
endif()

add_library(adrenotools STATIC IMPORTED)
set_target_properties(adrenotools PROPERTIES
  IMPORTED_LOCATION "${ADRENOTOOLS_BUILD}/libadrenotools.a"
  INTERFACE_INCLUDE_DIRECTORIES "${ADRENOTOOLS_INCLUDE}")

add_library(linkernsbypass STATIC IMPORTED)
set_target_properties(linkernsbypass PROPERTIES
  IMPORTED_LOCATION "${ADRENOTOOLS_BUILD}/lib/linkernsbypass/liblinkernsbypass.a")

# PUBLIC, so both entry targets inherit them rather than repeating the list.
target_link_libraries(host-layer-core PUBLIC FEXCore JemallocDummy android log adrenotools linkernsbypass)
target_include_directories(host-layer-core PUBLIC
  "${FEX_ROOT}/FEXCore/include"
  "${CMAKE_BINARY_DIR}/include"
  "${CMAKE_CURRENT_SOURCE_DIR}/src"
  # the generated half of the vulkan thunk. the guest half of the same generator output is
  # assembled separately into libvulkan.so.1 by scripts/build-thunks.py.
  "${CMAKE_CURRENT_SOURCE_DIR}/thunks/vulkan/generated"
  # and the same for audio, into libaaudio.so by scripts/build-thunks.py. note that
  # libaaudio is *not* linked: the host half dlsyms it, because eleven of its 72 entry points are
  # __INTRODUCED_IN(29..36) and this builds at API 28.
  "${CMAKE_CURRENT_SOURCE_DIR}/thunks/audio/generated")

add_executable(sharpdroid-host-layer "${CMAKE_CURRENT_SOURCE_DIR}/src/entry_exe.cpp")
target_link_libraries(sharpdroid-host-layer PRIVATE host-layer-core)

add_library(sharpdroid-host-layer-jni SHARED "${CMAKE_CURRENT_SOURCE_DIR}/src/entry_jni.cpp")
target_link_libraries(sharpdroid-host-layer-jni PRIVATE host-layer-core)
# so the APK loads it as System.loadLibrary("sharpdroid-host-layer"). the cmake target has to be
# named differently from the executable's; the file on disk does not.
set_target_properties(sharpdroid-host-layer-jni PROPERTIES OUTPUT_NAME "sharpdroid-host-layer")
# the JNI entry point is reached by name from the java side, and nothing in the link graph refers
# to it. without this the linker's --gc-sections drops both symbols and the app dies at
# UnsatisfiedLinkError with the library sitting right there.
target_link_options(sharpdroid-host-layer-jni PRIVATE "-Wl,-u,Java_com_mircowuffwuff_sharpdroid_HostLayer_nativeRun")
# and JNI_OnLoad, for exactly the same reason and with a worse failure: the runtime looks it up by
# name at load time, nothing in the link graph refers to it either, and dropping it costs no error
# at all -- the library loads, and the file layer simply never has a class to call.
target_link_options(sharpdroid-host-layer-jni PRIVATE "-Wl,-u,JNI_OnLoad")
