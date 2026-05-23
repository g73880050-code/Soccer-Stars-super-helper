# =============================================================================
# p4a_recipes/opencv/CMakeLists.txt
# =============================================================================
#
# Standalone CMake project that builds ONLY cv2.so — the Python extension
# module for OpenCV — without recompiling any of the OpenCV C++ libraries.
#
# How it works
# ------------
#   1. find_package(OpenCV) resolves to the PREBUILT Android SDK by pointing
#      OpenCV_DIR at the SDK's sdk/native/jni/ directory, which contains
#      OpenCVConfig.cmake and OpenCVModules.cmake.  Those cmake files define
#      IMPORTED targets (opencv_core, opencv_imgproc, …) that wrap the
#      prebuilt .so files.  No C++ code is recompiled.
#
#   2. The cv2.cpp Python wrapper is compiled once (~3–5 min) using the
#      NDK cross-compiler.  It includes:
#        – headers from the prebuilt SDK (sdk/native/jni/include/)
#        – pyopencv_generated_*.h headers produced by gen2.py
#        – Python and NumPy cross-compiled includes (from p4a)
#
#   3. The resulting cv2.so is installed directly into p4a's site-packages
#      directory, so `import cv2` works inside the APK.
#
# Variables injected by the recipe's cmake call
# ----------------------------------------------
#   OPENCV_ANDROID_SDK_JNI_DIR   path to sdk/native/jni/  (OpenCVConfig.cmake)
#   OPENCV_SOURCE_DIR            extracted opencv source (for cv2.cpp)
#   OPENCV_GENERATED_DIR         pyopencv_generated_*.h output of gen2.py
#   PYTHON_INCLUDE_DIR           cross-compiled Android Python headers
#   PYTHON_LIBRARY               libpython3.11.so path
#   NUMPY_INCLUDE_DIR            numpy/core/include path
#   CV2_INSTALL_DIR              p4a site-packages directory
# =============================================================================

cmake_minimum_required(VERSION 3.18)
project(cv2_python_binding CXX)

# ---------------------------------------------------------------------------
# 1. Validate required input variables
# ---------------------------------------------------------------------------
foreach(_var
    OPENCV_ANDROID_SDK_JNI_DIR
    OPENCV_SOURCE_DIR
    OPENCV_GENERATED_DIR
    PYTHON_INCLUDE_DIR
    PYTHON_LIBRARY
    NUMPY_INCLUDE_DIR
    CV2_INSTALL_DIR)
  if(NOT DEFINED ${_var} OR "${${_var}}" STREQUAL "")
    message(FATAL_ERROR
      "[cv2 recipe] Required variable not set: ${_var}\n"
      "This CMakeLists.txt must be invoked from the p4a opencv recipe, "
      "not directly.")
  endif()
endforeach()

# ---------------------------------------------------------------------------
# 2. Find prebuilt OpenCV from the official Android SDK
#    OpenCVConfig.cmake in sdk/native/jni/ defines IMPORTED targets that
#    wrap the prebuilt .so files — nothing gets recompiled here.
# ---------------------------------------------------------------------------
set(OpenCV_DIR "${OPENCV_ANDROID_SDK_JNI_DIR}" CACHE PATH
    "Path to OpenCVConfig.cmake inside the prebuilt Android SDK" FORCE)

find_package(OpenCV 4.9 REQUIRED
    COMPONENTS
        core imgproc imgcodecs highgui
        features2d flann calib3d objdetect
        video videoio dnn ml photo stitching
    NO_DEFAULT_PATH)

message(STATUS "[cv2] Found OpenCV ${OpenCV_VERSION} via prebuilt SDK")
message(STATUS "[cv2]   includes : ${OpenCV_INCLUDE_DIRS}")
message(STATUS "[cv2]   libs     : ${OpenCV_LIBS}")

# ---------------------------------------------------------------------------
# 3. Locate cv2.cpp  (opencv source  →  modules/python/src2/cv2.cpp)
# ---------------------------------------------------------------------------
set(_CV2_CPP "${OPENCV_SOURCE_DIR}/modules/python/src2/cv2.cpp")
if(NOT EXISTS "${_CV2_CPP}")
  message(FATAL_ERROR
    "[cv2 recipe] cv2.cpp not found at:\n  ${_CV2_CPP}\n"
    "Check that OPENCV_SOURCE_DIR points to the extracted opencv source.")
endif()

# ---------------------------------------------------------------------------
# 4. Collect pyopencv_generated_*.h files (produced by gen2.py)
# ---------------------------------------------------------------------------
file(GLOB _GENERATED_HEADERS "${OPENCV_GENERATED_DIR}/pyopencv_generated_*.h")
list(LENGTH _GENERATED_HEADERS _N_HEADERS)
if(_N_HEADERS EQUAL 0)
  message(FATAL_ERROR
    "[cv2 recipe] No pyopencv_generated_*.h files found in:\n"
    "  ${OPENCV_GENERATED_DIR}\n"
    "gen2.py must have been run before CMake configure.")
endif()
message(STATUS "[cv2] Found ${_N_HEADERS} generated Python binding header(s)")

# ---------------------------------------------------------------------------
# 5. Build cv2.so
# ---------------------------------------------------------------------------
add_library(cv2 SHARED "${_CV2_CPP}")

# ── Include paths ────────────────────────────────────────────────────────────
target_include_directories(cv2 PRIVATE
    # OpenCV public headers from prebuilt SDK
    ${OpenCV_INCLUDE_DIRS}
    # Python cross-compiled headers (Android libpython from p4a)
    "${PYTHON_INCLUDE_DIR}"
    # NumPy headers
    "${NUMPY_INCLUDE_DIR}"
    # cv2.cpp internal include directory
    "${OPENCV_SOURCE_DIR}/modules/python/src2"
    # Generated binding headers (pyopencv_generated_*.h)
    "${OPENCV_GENERATED_DIR}"
    # Some generated files reference other module includes
    "${OPENCV_SOURCE_DIR}/modules/core/include"
    "${OPENCV_SOURCE_DIR}/modules/imgproc/include"
    "${OPENCV_SOURCE_DIR}/modules/imgcodecs/include"
    "${OPENCV_SOURCE_DIR}/modules/highgui/include"
    "${OPENCV_SOURCE_DIR}/modules/features2d/include"
    "${OPENCV_SOURCE_DIR}/modules/flann/include"
    "${OPENCV_SOURCE_DIR}/modules/calib3d/include"
    "${OPENCV_SOURCE_DIR}/modules/objdetect/include"
    "${OPENCV_SOURCE_DIR}/modules/video/include"
    "${OPENCV_SOURCE_DIR}/modules/videoio/include"
    "${OPENCV_SOURCE_DIR}/modules/dnn/include"
    "${OPENCV_SOURCE_DIR}/modules/ml/include"
    "${OPENCV_SOURCE_DIR}/modules/photo/include"
    "${OPENCV_SOURCE_DIR}/modules/stitching/include"
)

# ── Link libraries ───────────────────────────────────────────────────────────
# ${OpenCV_LIBS} contains the IMPORTED targets from the prebuilt SDK:
#   opencv_core, opencv_imgproc, … (they wrap the prebuilt .so files)
# PYTHON_LIBRARY is libpython3.11.so from the p4a cross-compiled Python.
target_link_libraries(cv2
    ${OpenCV_LIBS}
    "${PYTHON_LIBRARY}"
)

# ── Output naming ────────────────────────────────────────────────────────────
# Python requires the extension to be named exactly "cv2.so" (no lib prefix)
set_target_properties(cv2 PROPERTIES
    PREFIX  ""
    SUFFIX  ".so"
    OUTPUT_NAME "cv2"
)

# ── Compiler flags ────────────────────────────────────────────────────────────
target_compile_options(cv2 PRIVATE
    -O2
    -fvisibility=hidden     # keeps the .so symbol table small
    -Wno-unused-function
    -Wno-unused-variable
    -Wno-deprecated-declarations
)

# ── Ensure cv2.so can find the OpenCV .so files at runtime ───────────────────
# Android loads .so files from the APK's jniLibs, so the RPATH is not needed,
# but setting ORIGIN makes debugging easier in case of issues.
set_target_properties(cv2 PROPERTIES
    INSTALL_RPATH           "$ORIGIN"
    BUILD_WITH_INSTALL_RPATH ON
)

# ---------------------------------------------------------------------------
# 6. Install cv2.so into p4a site-packages
# ---------------------------------------------------------------------------
install(TARGETS cv2
    LIBRARY DESTINATION "${CV2_INSTALL_DIR}"
)

message(STATUS "[cv2] cv2.so will be installed to: ${CV2_INSTALL_DIR}")
