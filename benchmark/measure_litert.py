"""Measure GeCo2 at 1024 x 1024 under LiteRT and ONNX Runtime on the attached phone's CPU.

Part of the LiteRT spike (see export_geco2_litert.py). Pushes both int8 exports, the marbles input
written by compare_litert.py and the two measuring programs to the phone over adb, then runs each
with the same settings: 4 threads, CPU only (LiteRT with XNNPACK, ONNX Runtime with its CPU
execution provider), warm-up runs, then timed runs on the same input. Prints load time, inference
time (median, minimum, maximum) and peak memory (the process's maximum resident set).

- LiteRT: the official prebuilt benchmark_model for Android arm64.
- ONNX Runtime: there is no prebuilt onnxruntime_perf_test for Android, so device/ort_benchmark.cc,
  built with the Android NDK against libonnxruntime.so from the onnxruntime-android AAR the app
  ships, with the app's session options.

Usage: uv run measure_litert.py [--runs 10] [--warmup-runs 2] [--cooldown 60]
"""

import argparse
import logging
import re
import shutil
import subprocess
import time
import zipfile
from pathlib import Path

from compare_litert import DEVICE_DIR, DEVICE_EXEMPLARS, DEVICE_IMAGE, LITERT_MODEL, ONNX_MODEL
from dataset import BENCHMARK_DIR, configure_logging, download

logger = logging.getLogger(__name__)

THREADS = 4
ORT_VERSION = "1.30.0"
ORT_AAR_URL = (
    "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/"
    f"{ORT_VERSION}/onnxruntime-android-{ORT_VERSION}.aar"
)
BENCHMARK_MODEL_URL = (
    "https://storage.googleapis.com/tensorflow-nightly-public/prod/tensorflow/release/lite/tools/"
    "nightly/latest/android_aarch64_benchmark_model"
)
ORT_BENCHMARK_SOURCE = BENCHMARK_DIR / "device" / "ort_benchmark.cc"
ANDROID_DIR = BENCHMARK_DIR.parent / "android"
PHONE_DIR = "/data/local/tmp/quantify-litert"


def _ndk_clang() -> Path:
    """The newest installed NDK's clang++ for arm64, from the SDK in android/local.properties."""
    properties = (ANDROID_DIR / "local.properties").read_text()
    match = re.search(r"^sdk\.dir=(.+)$", properties, re.MULTILINE)
    if not match:
        raise RuntimeError("android/local.properties has no sdk.dir")
    ndks = sorted((Path(match[1]) / "ndk").iterdir(), key=lambda path: path.name)
    if not ndks:
        raise RuntimeError("No Android NDK installed")
    return ndks[-1] / "toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang++"


def _prepare_programs() -> tuple[Path, Path, Path]:
    """benchmark_model, ort_benchmark and libonnxruntime.so, fetched and built once."""
    benchmark_model = DEVICE_DIR / "benchmark_model"
    if not benchmark_model.exists():
        download(BENCHMARK_MODEL_URL, benchmark_model)
    ort_dir = DEVICE_DIR / f"onnxruntime-android-{ORT_VERSION}"
    if not ort_dir.exists():
        aar = DEVICE_DIR / f"onnxruntime-android-{ORT_VERSION}.aar"
        download(ORT_AAR_URL, aar)
        with zipfile.ZipFile(aar) as archive:
            archive.extractall(ort_dir)
    library = ort_dir / "jni" / "arm64-v8a" / "libonnxruntime.so"
    ort_benchmark = DEVICE_DIR / "ort_benchmark"
    if (
        not ort_benchmark.exists()
        or ort_benchmark.stat().st_mtime < ORT_BENCHMARK_SOURCE.stat().st_mtime
    ):
        subprocess.run(
            [
                str(_ndk_clang()),
                "-std=c++17",
                "-O2",
                f"-I{ort_dir / 'headers'}",
                str(ORT_BENCHMARK_SOURCE),
                f"-L{library.parent}",
                "-lonnxruntime",
                "-static-libstdc++",
                "-o",
                str(ort_benchmark),
            ],
            check=True,
        )
    return benchmark_model, ort_benchmark, library


def _adb(*command: str) -> str:
    result = subprocess.run(["adb", *command], check=True, capture_output=True, text=True)
    return result.stdout + result.stderr


def _push(files: list[Path]) -> None:
    _adb("shell", f"mkdir -p {PHONE_DIR}")
    for file in files:
        logger.info("Pushing %s (%.0f MB)", file.name, file.stat().st_size / 1e6)
        _adb("push", str(file), f"{PHONE_DIR}/{file.name}")
    _adb("shell", f"chmod +x {PHONE_DIR}/benchmark_model {PHONE_DIR}/ort_benchmark")


def _run_on_phone(command: str) -> str:
    """Run a command in the phone's work directory under toybox time, which reports peak memory."""
    logger.info("Running on the phone: %s", command)
    return _adb("shell", f"cd {PHONE_DIR} && /system/bin/time -v {command}")


def _peak_memory_mb(output: str) -> float:
    match = re.search(r"Max RSS \(KiB\): (\d+)", output)
    if not match:
        raise RuntimeError(f"No peak memory in output:\n{output}")
    return int(match[1]) / 1024


def _measure_litert(runs: int, warmup_runs: int) -> dict[str, float]:
    output = _run_on_phone(
        f"./benchmark_model --graph={LITERT_MODEL.name} --num_threads={THREADS} "
        "--use_xnnpack=true --use_gpu=false "
        f"--warmup_runs={warmup_runs} --warmup_min_secs=0 --num_runs={runs} --min_secs=0 "
        "--max_secs=100000 --input_layer=args_0,args_1 --input_layer_shape=1,3,1024,1024:1,1,4 "
        f"--input_layer_value_files=args_0:{DEVICE_IMAGE.name},args_1:{DEVICE_EXEMPLARS.name}"
    )
    logger.info("benchmark_model output:\n%s", output)
    init = re.search(r"Inference timings in us: Init: (\d+)", output)
    # Statistics lines like "count=10 first=... min=... max=... avg=... median=...", in us; the
    # timed runs' come after the warm-up's.
    stats = [
        dict(re.findall(r"(\w+)=([\d.e+]+)", line))
        for line in output.splitlines()
        if "count=" in line
    ]
    if not init or not stats:
        raise RuntimeError(f"Unexpected benchmark_model output:\n{output}")
    timed = stats[-1]
    if int(timed["count"]) != runs:
        raise RuntimeError(f"benchmark_model ran {timed['count']} times, not {runs}:\n{output}")
    return {
        "load_ms": int(init[1]) / 1000,
        "median_ms": float(timed["median"]) / 1000,
        "min_ms": float(timed["min"]) / 1000,
        "max_ms": float(timed["max"]) / 1000,
        "mean_ms": float(timed["avg"]) / 1000,
        "peak_mb": _peak_memory_mb(output),
    }


def _measure_onnx_runtime(runs: int, warmup_runs: int) -> dict[str, float]:
    output = _run_on_phone(
        f"env LD_LIBRARY_PATH=. ./ort_benchmark {ONNX_MODEL.name} {DEVICE_IMAGE.name} "
        f"{DEVICE_EXEMPLARS.name} {THREADS} {warmup_runs} {runs}"
    )
    logger.info("ort_benchmark output:\n%s", output)
    match = re.search(
        r"load_ms=(\d+) runs=(\d+) median_ms=(\d+) min_ms=(\d+) max_ms=(\d+) mean_ms=(\d+)", output
    )
    if not match:
        raise RuntimeError(f"Unexpected ort_benchmark output:\n{output}")
    load, count, median, minimum, maximum, mean = (float(value) for value in match.groups())
    if int(count) != runs:
        raise RuntimeError(f"ort_benchmark ran {count} times, not {runs}")
    return {
        "load_ms": load,
        "median_ms": median,
        "min_ms": minimum,
        "max_ms": maximum,
        "mean_ms": mean,
        "peak_mb": _peak_memory_mb(output),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--runs", type=int, default=10)
    parser.add_argument("--warmup-runs", type=int, default=2)
    parser.add_argument(
        "--cooldown", type=int, default=60, help="seconds to let the phone cool between runtimes"
    )
    arguments = parser.parse_args()
    configure_logging()

    for required in (LITERT_MODEL, ONNX_MODEL, DEVICE_IMAGE, DEVICE_EXEMPLARS):
        if not required.exists():
            raise FileNotFoundError(
                f"{required} missing, see export_geco2_litert.py and compare_litert.py"
            )
    if shutil.which("adb") is None:
        raise RuntimeError("adb not found")
    benchmark_model, ort_benchmark, library = _prepare_programs()
    _push(
        [
            benchmark_model,
            ort_benchmark,
            library,
            LITERT_MODEL,
            ONNX_MODEL,
            DEVICE_IMAGE,
            DEVICE_EXEMPLARS,
        ]
    )

    results = {"ONNX Runtime": _measure_onnx_runtime(arguments.runs, arguments.warmup_runs)}
    logger.info("Letting the phone cool down for %d s", arguments.cooldown)
    time.sleep(arguments.cooldown)
    results["LiteRT"] = _measure_litert(arguments.runs, arguments.warmup_runs)

    print("| runtime | load | median | min | max | mean | peak memory |")
    print("|---|---|---|---|---|---|---|")
    for runtime, result in results.items():
        print(
            f"| {runtime} | {result['load_ms'] / 1000:.1f} s | {result['median_ms'] / 1000:.2f} s "
            f"| {result['min_ms'] / 1000:.2f} s | {result['max_ms'] / 1000:.2f} s "
            f"| {result['mean_ms'] / 1000:.2f} s | {result['peak_mb']:.0f} MB |"
        )
    sizes = {
        "libonnxruntime.so": library,
        ONNX_MODEL.name: ONNX_MODEL,
        LITERT_MODEL.name: LITERT_MODEL,
    }
    for name, path in sizes.items():
        print(f"{name}: {path.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
