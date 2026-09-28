# Spike: LiteRT vs ONNX Runtime on the CPU (GeCo2, 1024 x 1024)

Question: would GeCo2 under LiteRT (XNNPACK) on the CPU be clearly faster or smaller than under
ONNX Runtime, at the same accuracy? Result: **no**. It is about as fast, needs almost twice the
memory, and saves only ~18 MB of APK.

## Setup

- Both models: GeCo2's dense network, fixed 1024 x 1024 input and one exemplar, int8 weights with
  float32 activations (ORT `quantize_dynamic` QUInt8 per tensor, LiteRT `dynamic_wi8_afp32` per
  channel). Export: `uv run modal run export_geco2_litert.py`.
- LiteRT converts with builtin ops only (no Flex). This needed four equivalent rewrites, each
  checked against the original (at most 2e-6): RoiAlign, deformable attention with Python-number
  level sizes, grid sampling that gathers whole cells, and linear layers on 2D views.
- Phone: Pixel 8 Pro, 4 threads, 2 warm-up runs and 10 timed runs on the marbles input
  (`uv run measure_litert.py`). LiteRT uses the official `benchmark_model`. ORT uses
  `device/ort_benchmark.cc`, built against the app's onnxruntime-android 1.30.0 AAR (no prebuilt
  `onnxruntime_perf_test` exists for Android). Peak memory is toybox `time -v` Max RSS for both.

## Results

| | ONNX Runtime | LiteRT |
|---|---|---|
| Load | 3.2 s | 1.1 s |
| Inference median (min to max) | 11.3 s (9.3 to 12.8) | 13.2 s (9.6 to 14.8) |
| Peak memory | 2.4 GB | 4.6 GB |
| Runtime library (arm64) | 33 MB | ~5 MB |
| Model file | 77 MB | 87 MB |

The phone throttles over the ~2 minutes of each run, so run times scatter by ±20 %. A first LiteRT
run measured a median of 10.1 s (min 8.4 s). Neither runtime is 1.5x faster than the other.
Although LiteRT's model is bigger (constant-folded position encodings in float32), the APK would
shrink by ~18 MB net. XNNPACK runs 2723 of 3182 nodes; the gathers and index arithmetic of
deformable attention fall back to builtin kernels (13 partitions).

Accuracy on this computer (`uv run --with ai-edge-litert==2.2.0 compare_litert.py`): counts per
image, with the float32 network as reference.

| Image | True | fp32 | ORT int8 | LiteRT int8 |
|---|---|---|---|---|
| 5574 marbles | 90 | 93 | 93 | 93 |
| 6242 apples | 73 | 85 | 85 | 85 |
| 6735 markers | 73 | 17 | 23 | 18 |
| 2147 apples | 37 | 33 | 34 | 33 |
| 4920 strawberries | 20 | 27 | 33 | 29 |
| 7339 markers | 104 | 106 | 106 | 106 |
| 7131 keyboard keys | 123 | 130 | 130 | 129 |
| 6283 apples | 21 | 20 | 20 | 19 |

Mean objectness deviation from fp32: LiteRT 4 to 16 %, ORT 7 to 34 %. Per-channel weights keep
LiteRT closer to fp32. Per-channel quantization is also possible in ORT, so this is no reason to
switch runtimes.

## Assessment

Switching does not pay off. It misses the 1.5x speed-up, and the ~18 MB smaller APK does not
outweigh the other costs:

- **Memory:** 4.6 GB instead of 2.4 GB. Android throttles apps above ~3 GB.
- **Fixed input size:** litert-torch cannot export dynamic shapes, so every count runs at
  1024 x 1024. ORT counts a small crop at 416 x 320 in ~1.7 s instead of ~7 s. Several fixed-size
  models would cost APK size again.
- **Maintenance:** four rewrites of GeCo2 modules.

## GPU (follow-up)

Quick test of LiteRT's GPU delegate (OpenCL, float16) on the float32 export
(`uv run measure_litert.py --gpu`, with XNNPACK running whatever the GPU delegate rejects): only
**84 of 3182 ops** run on the GPU, and one run takes **18.4 s** (CPU: ~10 to 13 s), with 5.8 GB of
memory.

The delegate rejects Hiera's core ops, not just deformable attention. It handles only tensors of
up to four dimensions, and the window partitioning and multi-head reshapes in Hiera use five and
six (ADD v4, SLICE v5 and TRANSPOSE v6 are rejected, and so are FULLY_CONNECTED, MUL and RESHAPE on
these shapes). The gathers, casts and comparisons of deformable attention are unsupported
anyway (GATHER_ND, CAST to int64, LESS and the like).

Getting the backbone onto the GPU would mean rewriting Hiera throughout with tensors of at most
four dimensions. That is far beyond small rewrites. The newer GPU accelerator in LiteRT's
CompiledModel API may cover more, but it has no prebuilt benchmark tool, and even then deformable
attention would stay on the CPU. So the GPU path is not worth pursuing either.
