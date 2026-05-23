"""
p4a_recipes/opencv/__init__.py
===============================
Custom OpenCV recipe for python-for-android.

Strategy: pre-built Android SDK  +  standalone cv2.so compilation
------------------------------------------------------------------
The built-in p4a opencv recipe compiles all ~14 C++ modules from source
using CMake + Android NDK, which takes 40–60 minutes and frequently fails
due to version mismatches between the patch, CMake, and the NDK.

This recipe replaces that entire phase with three fast steps:

  Step A  Download  (5–10 min)
    – opencv-4.9.0-android-sdk.zip  (~235 MB, official prebuilt release)
      Contains arm64-v8a .so files AND OpenCVConfig.cmake which lets
      find_package(OpenCV) resolve to prebuilt libs without recompiling.
    – opencv-4.9.0-source.zip  (~90 MB, needed for binding generator only)

  Step B  Generate Python binding headers  (~30 sec, host Python)
    – Runs gen2.py from the source zip on the HOST machine.
    – Produces pyopencv_generated_*.h in a local temp dir.
    – No NDK cross-compilation involved.

  Step C  Cross-compile ONLY cv2.so  (~3–5 min)
    – Standalone CMakeLists.txt (p4a_recipes/opencv/CMakeLists.txt).
    – find_package(OpenCV) → resolves to prebuilt SDK libs (no recompile).
    – Compiles one file: modules/python/src2/cv2.cpp + generated headers.
    – Links against the prebuilt .so files from Step A.

Total time: ~15–20 min (vs 40–60 min built-in)

Compatibility
-------------
  OpenCV   : 4.9.0
  NDK      : 25.1.8937393
  Android  : API 33 / minAPI 26
  Arch     : arm64-v8a
  Python   : 3.11 (embedded by p4a)
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from glob import glob
from multiprocessing import cpu_count
from os.path import exists, join

import sh

from pythonforandroid.logger import info, shprint
from pythonforandroid.recipe import NDKRecipe
from pythonforandroid.util import current_directory, ensure_dir

# ---------------------------------------------------------------------------
# Version constants — change here only
# ---------------------------------------------------------------------------
OPENCV_VERSION = "4.9.0"

# Official prebuilt Android SDK (arm64-v8a .so + OpenCVConfig.cmake)
ANDROID_SDK_URL = (
    "https://github.com/opencv/opencv/releases/download/"
    f"{OPENCV_VERSION}/opencv-{OPENCV_VERSION}-android-sdk.zip"
)

# Source archive — used ONLY for gen2.py (binding code generator)
SOURCE_URL = (
    f"https://github.com/opencv/opencv/archive/{OPENCV_VERSION}.zip"
)

# The 14 native .so files bundled in every release of the prebuilt SDK.
# Must match what p4a expects from generated_libraries so it can determine
# whether the recipe needs to be rebuilt on subsequent CI runs.
_NATIVE_LIBS = [
    "libopencv_core.so",
    "libopencv_imgproc.so",
    "libopencv_imgcodecs.so",
    "libopencv_highgui.so",
    "libopencv_features2d.so",
    "libopencv_flann.so",
    "libopencv_calib3d.so",
    "libopencv_objdetect.so",
    "libopencv_video.so",
    "libopencv_videoio.so",
    "libopencv_dnn.so",
    "libopencv_ml.so",
    "libopencv_photo.so",
    "libopencv_stitching.so",
]


class OpenCVRecipe(NDKRecipe):
    """
    OpenCV recipe that skips C++ recompilation by using the official
    prebuilt Android SDK, then cross-compiles only the thin cv2.so
    Python extension module against those prebuilt libs.
    """

    version = OPENCV_VERSION
    # p4a downloads this URL into get_build_dir() automatically.
    # We use the source zip for gen2.py only (not for CMake compilation).
    url = SOURCE_URL

    depends = ["numpy"]
    patches = []  # no patches needed — we never compile the C++ libs

    # Tell p4a which .so files this recipe provides, so it can skip
    # rebuilding on subsequent runs when files are already present.
    generated_libraries = _NATIVE_LIBS

    # ── helpers ──────────────────────────────────────────────────────────

    def get_lib_dir(self, arch):
        """Compatibility shim used by p4a internals."""
        return join(self.get_build_dir(arch.arch), "build_cv2", "lib")

    def get_recipe_env(self, arch):
        env = super().get_recipe_env(arch)
        env["ANDROID_NDK"] = self.ctx.ndk_dir
        env["ANDROID_SDK"] = self.ctx.sdk_dir
        return env

    # ── main build ───────────────────────────────────────────────────────

    def build_arch(self, arch):
        build_dir = self.get_build_dir(arch.arch)

        # ------------------------------------------------------------------
        # A. Acquire the prebuilt Android SDK
        # ------------------------------------------------------------------
        sdk_zip = join(build_dir, "opencv-android-sdk.zip")
        sdk_extract = join(build_dir, "opencv-android-sdk")

        # The zip extracts to a directory named "OpenCV-android-sdk"
        sdk_root = join(sdk_extract, "OpenCV-android-sdk")
        sdk_native = join(sdk_root, "sdk", "native")
        sdk_libs_dir = join(sdk_native, "libs", arch.arch)
        # OpenCVConfig.cmake lives here — used by find_package(OpenCV)
        sdk_jni_dir = join(sdk_native, "jni")

        if not exists(sdk_zip):
            info(f"[opencv] Downloading prebuilt Android SDK {OPENCV_VERSION} …")
            shprint(sh.wget,
                    "--quiet", "--show-progress",
                    ANDROID_SDK_URL,
                    "-O", sdk_zip)

        if not exists(sdk_root):
            info("[opencv] Extracting prebuilt Android SDK …")
            ensure_dir(sdk_extract)
            shprint(sh.unzip, "-q", sdk_zip, "-d", sdk_extract)

        # ------------------------------------------------------------------
        # B. Copy prebuilt .so files → APK jniLibs (replaces all C++ builds)
        # ------------------------------------------------------------------
        apk_libs = self.ctx.get_libs_dir(arch.arch)
        ensure_dir(apk_libs)

        info(f"[opencv] Copying prebuilt .so files to {apk_libs} …")
        copied, missing = 0, []
        for lib in _NATIVE_LIBS:
            src = join(sdk_libs_dir, lib)
            if exists(src):
                shutil.copy2(src, apk_libs)
                copied += 1
            else:
                missing.append(lib)

        # Also copy the combined java wrapper if present
        java_lib = join(sdk_libs_dir, "libopencv_java4.so")
        if exists(java_lib):
            shutil.copy2(java_lib, apk_libs)

        info(f"[opencv] Copied {copied}/{len(_NATIVE_LIBS)} native libs. "
             f"Missing: {missing or 'none'}")

        # ------------------------------------------------------------------
        # C. Generate Python binding headers on the HOST  (no NDK needed)
        # ------------------------------------------------------------------
        # The source zip was extracted by p4a into:
        #   <build_dir>/opencv-<version>/
        source_dir = join(build_dir, f"opencv-{OPENCV_VERSION}")
        gen2_script = join(source_dir, "modules", "python", "src2", "gen2.py")
        generated_dir = join(build_dir, "pyopencv_generated")

        if not exists(generated_dir) or not glob(
            join(generated_dir, "pyopencv_generated_*.h")
        ):
            ensure_dir(generated_dir)
            info("[opencv] Running gen2.py to generate Python binding headers …")

            # Collect every opencv2/*.hpp from all relevant modules.
            # gen2.py signature:  gen2.py <out_dir> <src_root> <header1> ...
            header_files: list[str] = []
            modules_dir = join(source_dir, "modules")
            for mod in sorted(os.listdir(modules_dir)):
                inc_dir = join(modules_dir, mod, "include", "opencv2")
                if os.path.isdir(inc_dir):
                    for hpp in glob(join(inc_dir, "*.hpp")):
                        header_files.append(hpp)
                    # Also grab immediate sub-directory headers
                    for hpp in glob(join(inc_dir, "*", "*.hpp")):
                        header_files.append(hpp)

            if not header_files:
                raise RuntimeError(
                    "[opencv] No header files found under "
                    f"{modules_dir}. Is the source zip fully extracted?"
                )

            # Run on the HOST Python (not cross-compiled Python)
            result = subprocess.run(
                [sys.executable, gen2_script, generated_dir, source_dir]
                + header_files,
                check=True,
                capture_output=True,
                text=True,
            )
            if result.stdout:
                info(f"[opencv] gen2.py stdout: {result.stdout[:500]}")

            generated = glob(join(generated_dir, "pyopencv_generated_*.h"))
            info(f"[opencv] Generated {len(generated)} binding header(s).")

        # ------------------------------------------------------------------
        # D. Cross-compile cv2.so with standalone CMakeLists.txt
        # ------------------------------------------------------------------
        cmake_build = join(build_dir, "build_cv2")
        ensure_dir(cmake_build)

        env = self.get_recipe_env(arch)

        # Python paths inside the cross-compiled Android Python (from p4a)
        python_major = self.ctx.python_recipe.version[0]
        python_inc = self.ctx.python_recipe.include_root(arch.arch)
        python_sp = self.ctx.get_site_packages_dir(arch)
        python_lr = self.ctx.python_recipe.link_root(arch.arch)
        python_lv = self.ctx.python_recipe.link_version
        python_lib = join(python_lr, f"libpython{python_lv}.so")
        numpy_inc = join(python_sp, "numpy", "core", "include")

        # Path to our custom CMakeLists.txt (lives next to this __init__.py)
        recipe_dir = os.path.dirname(os.path.abspath(__file__))
        cmake_lists = recipe_dir  # CMakeLists.txt is in the recipe dir

        info("[opencv] Cross-compiling cv2.so …")
        with current_directory(cmake_build):
            shprint(
                sh.cmake,
                # ── Android NDK toolchain ──────────────────────────────
                f"-DANDROID_ABI={arch.arch}",
                f"-DANDROID_PLATFORM=android-{self.ctx.ndk_api}",
                f"-DANDROID_STANDALONE_TOOLCHAIN={self.ctx.ndk_dir}",
                f"-DCMAKE_TOOLCHAIN_FILE={join(self.ctx.ndk_dir, 'build', 'cmake', 'android.toolchain.cmake')}",
                # ── Prebuilt OpenCV (find_package resolves here) ───────
                f"-DOPENCV_ANDROID_SDK_JNI_DIR={sdk_jni_dir}",
                # ── Source and generated headers ───────────────────────
                f"-DOPENCV_SOURCE_DIR={source_dir}",
                f"-DOPENCV_GENERATED_DIR={generated_dir}",
                # ── Python (cross-compiled Android Python from p4a) ────
                f"-DPYTHON_INCLUDE_DIR={python_inc}",
                f"-DPYTHON_LIBRARY={python_lib}",
                f"-DNUMPY_INCLUDE_DIR={numpy_inc}",
                f"-DPython3_INCLUDE_DIRS={python_inc}",
                f"-DPython3_LIBRARIES={python_lib}",
                # ── cv2.so install target ──────────────────────────────
                f"-DCV2_INSTALL_DIR={python_sp}",
                # ── Linker: embed libpython so cv2.so loads correctly ──
                f"-DCMAKE_SHARED_LINKER_FLAGS=-L{python_lr} -lpython{python_lv}",
                "-DCMAKE_BUILD_TYPE=Release",
                # ── Source: our standalone CMakeLists.txt ──────────────
                cmake_lists,
                _env=env,
            )
            shprint(sh.make, f"-j{cpu_count()}")
            shprint(sh.make, "install")

        info("[opencv] cv2.so built and installed to site-packages.")


recipe = OpenCVRecipe()
