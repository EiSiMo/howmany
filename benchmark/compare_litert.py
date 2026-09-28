"""Compare GeCo2 at a fixed 1024 x 1024 input under LiteRT and ONNX Runtime on this computer's CPU.

Part of the LiteRT spike (see export_geco2_litert.py). Runs both int8 exports on the marbles image
and a few more benchmark images, each with its first exemplar, scaled and padded to 1024 x 1024 as
prototype 4 prepares them, and prints how far LiteRT's outputs deviate from ONNX Runtime's and
the counts both lead to. Also writes the marbles input as raw float32 files for measure_litert.py.

Usage: uv run --with ai-edge-litert==2.2.0 compare_litert.py
"""

import multiprocessing
from collections.abc import Callable, Sequence
from concurrent.futures import ProcessPoolExecutor
from functools import partial
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime
from numpy.typing import NDArray

from dataset import BENCHMARK_DIR, DATA_DIR, Box, Sample, load_samples
from prototype import load_module

INPUT_SIZE = 1024
THREADS = 4
ONNX_MODEL = DATA_DIR / f"geco2-int8-{INPUT_SIZE}.onnx"
# The unquantized network (export_geco2.py), as the reference both quantizations are held against.
REFERENCE_MODEL = DATA_DIR / "geco2-fp32.onnx"
LITERT_MODEL = DATA_DIR / f"geco2-int8-{INPUT_SIZE}.tflite"
# The marbles fill the whole input: the worst case the app is timed on, and the input measured on
# the phone. The other images are the manifest's first, a mix of categories and sizes.
MARBLES = "5574.jpg"
OTHER_IMAGES = 7
DEVICE_DIR = DATA_DIR / "device"
DEVICE_IMAGE = DEVICE_DIR / "image.raw"
DEVICE_EXEMPLARS = DEVICE_DIR / "exemplars.raw"

Outputs = list[NDArray[np.float32]]
Run = Callable[[dict[str, NDArray[np.float32]]], Outputs]

# Prototype 4 prepares images and turns the network's outputs into boxes; padding every side to
# the minimum makes its input the fixed 1024 x 1024 the exports take.
prototype = load_module(BENCHMARK_DIR / "prototypes" / "prototype-4.py")
setattr(prototype, "MIN_INPUT_SIDE", INPUT_SIZE)  # noqa: B010


class _Session:
    """Stands in for prototype 4's ONNX Runtime session and keeps the last outputs."""

    def __init__(self, run: Run) -> None:
        self._run = run
        self.outputs: Outputs = []

    def run(self, names: Sequence[str], feeds: dict[str, NDArray[np.float32]]) -> Outputs:
        self.outputs = self._run(feeds)
        return self.outputs


def _onnx_runtime(model: Path = ONNX_MODEL) -> Run:
    options = onnxruntime.SessionOptions()
    options.intra_op_num_threads = THREADS
    session = onnxruntime.InferenceSession(str(model), options, providers=["CPUExecutionProvider"])
    return lambda feeds: [np.asarray(output) for output in session.run(None, feeds)]


def _litert() -> Run:
    from ai_edge_litert.interpreter import Interpreter  # type: ignore[import-not-found]

    runner = Interpreter(str(LITERT_MODEL), num_threads=THREADS).get_signature_runner()

    def run(feeds: dict[str, NDArray[np.float32]]) -> Outputs:
        outputs: dict[str, Any] = runner(args_0=feeds["image"], args_1=feeds["exemplars"])
        return [outputs["output_0"], outputs["output_1"]]

    return run


def _counts(runtime: Callable[[], Run], samples: Sequence[Sample]) -> list[tuple[int, Outputs]]:
    """Each sample's count and the network's outputs under one runtime."""
    session = _Session(runtime())
    setattr(prototype, "_session", lambda: session)  # noqa: B010
    return [
        (len(prototype.detect(sample.image_path, sample.exemplars)), session.outputs)
        for sample in samples
    ]


def _counts_in_own_process(
    runtime: Callable[[], Run], samples: Sequence[Sample]
) -> list[tuple[int, Outputs]]:
    """_counts in a fresh process: at 1024 x 1024 the runtimes need more memory together than a
    laptop has, and ONNX Runtime does not hand its memory back to the system."""
    with ProcessPoolExecutor(1, mp_context=multiprocessing.get_context("spawn")) as executor:
        return executor.submit(_counts, runtime, samples).result()


def _deviation(actual: NDArray[np.float32], expected: NDArray[np.float32]) -> tuple[float, float]:
    """The largest and the mean absolute difference, each relative to the expected magnitude."""
    difference = np.abs(actual - expected)
    return (
        float(difference.max() / np.abs(expected).max()),
        float(difference.mean() / np.abs(expected).mean()),
    )


def _write_device_inputs(sample: Sample) -> None:
    import cv2

    image = cv2.imread(str(sample.image_path))
    if image is None:
        raise ValueError(f"Cannot read image {sample.image_path}")
    image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    exemplars = np.array(sample.exemplars, dtype=np.float32)
    pixels, scale = prototype._prepare(image, exemplars)
    DEVICE_DIR.mkdir(parents=True, exist_ok=True)
    pixels.astype(np.float32).tofile(DEVICE_IMAGE)
    (exemplars * scale)[None].astype(np.float32).tofile(DEVICE_EXEMPLARS)


def _first_exemplar(sample: Sample) -> Sample:
    exemplars: tuple[Box, ...] = sample.exemplars[:1]
    return Sample(sample.image_path, sample.category, sample.true_count, exemplars)


def _deviations(actual: Outputs, expected: Outputs) -> str:
    """Largest / mean deviation of objectness and of box offsets."""
    return ", ".join(
        f"{largest:.0%} / {mean:.1%}"
        for largest, mean in (
            _deviation(value.reshape(reference.shape), reference)
            for value, reference in zip(actual, expected, strict=True)
        )
    )


def main() -> None:
    samples = [_first_exemplar(sample) for sample in load_samples()]
    marbles = next(sample for sample in samples if sample.image_path.name == MARBLES)
    others = [sample for sample in samples if sample is not marbles][:OTHER_IMAGES]
    _write_device_inputs(marbles)
    samples = [marbles, *others]
    reference = _counts_in_own_process(partial(_onnx_runtime, REFERENCE_MODEL), samples)
    onnx = _counts_in_own_process(_onnx_runtime, samples)
    litert = _counts_in_own_process(_litert, samples)

    print("Counts, and deviations as largest / mean for objectness, box offsets:\n")
    print(
        "| image | category | true | fp32 | ORT int8 | LiteRT int8 "
        "| ORT vs fp32 | LiteRT vs fp32 | LiteRT vs ORT |"
    )
    print("|---|---|---|---|---|---|---|---|---|")
    for sample, (fp32_count, fp32), (onnx_count, onnx_outputs), (
        litert_count,
        litert_outputs,
    ) in zip(samples, reference, onnx, litert, strict=True):
        print(
            f"| {sample.image_path.name} | {sample.category} | {sample.true_count} | {fp32_count} "
            f"| {onnx_count} | {litert_count} | {_deviations(onnx_outputs, fp32)} "
            f"| {_deviations(litert_outputs, fp32)} | {_deviations(litert_outputs, onnx_outputs)} |"
        )


if __name__ == "__main__":
    main()
