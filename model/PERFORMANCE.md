# Performance ideas

Ideas for lowering peak memory and time without losing precision, and what they measured. Tick an
idea once it is decided, and add every new measurement to the table.

Why it matters: the moto g06 (3.9 GB) was killed in the foreground at 1126 and 1360 MB, and the
model-v2 candidate peaks at 1086 MB on the Pixel 8 Pro.

## Checklist

Keeps counts essentially unchanged:
- [ ] 1. Load a pre-optimized `.ort` model with mapped weights and no prepacking
- [x] 2. More stripes: stages 1 and 2 windows, `up2`, fine-level adapter attention
- [ ] 3. Split encoder and decoder, reuse features, encode before the user marks
- [ ] 4. Tune thread count, `session.dynamic_block_base` and core affinity per phone
- [x] 5. Compute box offsets only at objectness peaks

Needs the benchmark to confirm:
- [ ] 6. `MatMulNBits` with 4-bit weights instead of dynamically quantized MatMul
- [ ] 7. Float convolutions on XNNPACK instead of `ConvInteger`

Big bets:
- [ ] 8. Encoder on the GPU via ONNX Runtime's WebGPU execution provider
- [ ] 9. A runtime with 16-bit float activations (MNN, ncnn)
- [ ] 10. Smaller backbone with distillation

Item 4 has no gain. 8 and 9 were spikes without app integration, 10 is an estimate. 2 and 5 are
merged. 1 is built and passes, but is left out for now: it adds a native crash path and its gain is
in memory Android cannot reclaim, unmeasured on a low-RAM phone. It waits on `perf/combined`.

## Measurements

PC: Ryzen 5 7500F, 4 threads, ONNX Runtime 1.30, 1024 x 1024 random input, one exemplar, the app's
session options. Anon is resident anonymous memory, which excludes file-backed weights. Phone:
Pixel 8 Pro, worst image `5574.jpg` and easy image `2147.jpg`, in seconds. MAE: 100 FSC-147 images
and 84 photos, one exemplar. A dash means not measured. Phone peak here is `VmHWM` over
`ObjectCounterTest`, which holds several counters and counts both images. It is only comparable
within this table, and only across runs from the same test code. The next table is the fair one.

| Variant | PC peak MB (anon) | PC peak MB (total) | PC load s | PC run s | Phone peak MB | Phone run s (worst / easy) | MAE FSC-147 | MAE photos |
|---|---|---|---|---|---|---|---|---|
| Baseline: model-v2 candidate | 819 | 863 | 1.55 | 3.08 | 1086 | 7.5 / 1.7 | 4.55 | 12.13 |
| 1 | 664 | 812 | 0.74 | 3.08 | – | – | – | – |
| 1, on a loaded PC | 649 | 792 | 1.45 | 6.74 | 979 | +5 to 9% / +9 to 15% | 4.55 | 12.13 |
| 2 | 728 | 770 | 2.06 | 3.07 | 1004 | no change | 4.55 | 12.13 |
| 3, encoder only, as 1 | 586 | 727 | 0.19 | 1.4 | – | – | same | same |
| 3, decoder only, as 1 | 683 | 733 | 0.20 | 1.8 | – | – | same | same |
| 3, app with feature cache | – | – | – | – | 1028 (one run) | recount -48% | same | same |
| 4 | – | – | – | – | see notes | no gain | same | same |
| 5, float box head | 831 | 872 | 1.72 | 3.13 | 1105 | 6.86 / 1.51 (baseline here 7.55 / 1.72) | 4.55 | 12.13 |
| 6, 4-bit | 802 | 844 | 1.56 | 3.75 | 993 | 9.4 / 2.1 | 5.66 | 14.02 |
| 6, 8-bit | 832 | 874 | 1.59 | 3.44 | 1057 | 10.6 / 3.0 | 4.67 | 12.12 |
| 7 | 885 | 927 | 1.85 | 3.39 | 1147 | 11.1 / 3.7 | 4.60 | 12.54 |
| 8 | – | – | – | – | – | hangs the GPU | – | – |
| 9, MNN fp16 wq8, 20 FSC-147 images only | – | – | – | – | 848 | 9.3 / 2.0 | 5.65 (ONNX 5.65) | – |
| 10 | – | – | – | – | – | – | – | – |

Combinations, in one session of 12 alternating rounds, fresh process each. Phone run: median of
each process's faster run. The percentages are medians of same-round
ratios against the baseline. Peak one image: `VmHWM` after one count of the worst image in a fresh
process, median of 3, with its anonymous part. Peak mixed: `VmHWM` after easy and worst images
alternate, which is what a user does. Each row has rel. error 0.1052 and 0.2703 and within 10%
70.0 and 53.6.

| Variant | PC peak MB (anon) | PC peak MB (total) | PC load s | PC run s | Phone peak MB, one image (anon) | Phone peak MB, mixed | Phone run s (worst / easy) | MAE FSC-147 | MAE photos |
|---|---|---|---|---|---|---|---|---|---|
| Baseline | 829 | 871 | 1.63 | 3.24 | 973 (821) | 1005 | 7.52 / 1.67 | 4.55 | 12.13 |
| 2 | 728 | 770 | 2.16 | 3.15 | 876 (724) | 991 | 7.20 / 1.61 (-3.0 / -1.8%) | 4.55 | 12.13 |
| 2 + 5 | 730 | 772 | 2.31 | 3.10 | 878 (725) | 993 | 6.55 / 1.45 (-12.6 / -12.8%) | 4.55 | 12.13 |
| 1 + 2 | 539 | 685 | 1.14 | 3.24 | 855 (600) | 965 | 7.33 / 1.64 (-2.5 / -0.1%) | 4.55 | 12.13 |
| 1 + 2 + 5 | 540 | 686 | 1.26 | 3.11 | 852 (597) | 966 | 6.62 / 1.48 (-11.7 / -9.3%) | 4.55 | 12.13 |

Notes:
- Conditions: the battery was at 14 to 29 C because the phone lay on a cooling pack without a case,
  so absolute times are better than in real use. Without cooling the same baseline took 7.8 to
  14.9 s. Compare only rows from one alternating session.
- All times above run in the `foreground` cpuset, which lacks the fastest core; only an app on
  screen gets it. On screen, in 6 rounds, the worst image took 7.35 s at baseline, 6.48 s with
  2 + 5, 6.90 s with 1 + 2 + 5. That is noisy: 1 + 2 was 9.01 s, and the same run went from 7.8 to
  6.0 s as the phone cooled. Item 5's gain holds there, its exact size does not.
- Counts of 1 + 2 + 5 and 2 + 5 from a clean checkout of their commits equal the baseline on all 184
  images.
- 1: outputs differ from the baseline by at most 4.5e-7 on the PC, and 0 of 184 counts change. The
  peak's total barely moves, but what Android cannot reclaim does: at the peak 597 instead of
  725 MB anonymous, after loading 247 instead of 380 MB resident (84 instead of 231 anonymous). Load
  3.4 instead of 6.6 s. Costs: APK 28 MB larger, model copy on disk 109 instead of 79 MB, run time
  about 1% worst and 4% easy on top of 2 + 5. Truncating the mapped file or a disk error during a
  count is now a native crash. Conversion isn't deterministic: publish the exact file.
- 2: 0 of 184 counts change and outputs are identical, as only float parts are striped. Quantized
  stripes hurt precision and were dropped. The gain is the first count's peak: most of it goes when
  image sizes mix, for reasons not measured. Now `up2`'s im2col buffer and `blocks.2` limit the peak.
  A 32 x 32 input fails; the app's minimum side is 512. Session creation 5.4 to 7.1 s.
- 3: the split's outputs are identical to the whole model's. It saves no peak memory: the decoder
  alone peaks about as high as the whole model. A recount, or a count after encoding ahead, needs
  only the decoder. The app version keeps 140 MB between counts (275 MB before tuning, limit 100),
  and a recount is 48% faster, but at most 20 to 24% of counts are recounts, about 15 to 19% on
  average (estimate). Encoding ahead would waste 73 to 75% of its work: the input size depends on the
  exemplar and crop. The one 1028 MB run has no known cause.
- 4: 2, 3, 5 and 6 threads, `session.dynamic_block_base` 2 and 4, no spinning and affinity on
  1 + 2 + 5, 6 rounds. Four threads win without the fastest core: 6.73 s, against 7.88 (3), 11.1 (2),
  10.8 (5 and 6). On screen five threads were about 8% faster by same-round ratio but only 2% by
  median (6.90 to 6.75 s), noisy, and 60% slower whenever the fastest core is missing. No rule
  follows from one phone, so 4 stays. Block base gives nothing, no spinning is slower, affinities are not applied on Android.
  Counts are the same at every setting.
- 5: the box head stays in float in every stripe. 0 of 184 counts change. The worst image is 9%
  faster alone, just below the 10% set as the gain, and 12.6% faster together with 2.
- 6: 4-bit fails `countsApplesLikeTheBenchmark` (38 instead of 34). Accuracy level 1 doesn't rescue
  either, and `disable_prepacking` changes MatMulNBits outputs.
- 7: ONNX Runtime hands Conv to XNNPACK only with fixed sizes, 0 of 23 convolutions here. Fixed
  sizes peak at 1823 to 1840 MB and run 88 to 149% slower.
- 8: the WebGPU provider is in the Maven AAR, needs no custom build. A single Conv hangs the
  Mali-G715 (`VK_ERROR_DEVICE_LOST`), which took down `surfaceflinger` and all apps on the phone.
  Don't run `WebGpuSpikeTest` of `spike/webgpu` again. No GPU time or accuracy exists.
- 9: 22% below the same test's 1090 MB, not an alternating timing. Library 3.4 instead of 33 MB,
  model 109 MB, Apache-2.0. Rel. error 0.1250 instead of 0.1310, within 10% 14 instead of 15 of 20. Three
  silent MNN defects needed working around, one of them only on ARM (`class_embed` bias), so only
  the Pixel 8 Pro is verified. JNI and Kotlin alone are estimated at 1 to 2 weeks, but all 184 images
  on ARM, other phones and reproducible F-Droid builds are unestimated, and they are the larger risk.
- 10: estimate, not measured. On the phone the backbone takes 40% of the time and sets one of the
  two peaks. A backbone half the size might save 15 to 20% of the time and about 100 to 150 MB, if
  the adapter keeps its feature sizes. It needs distillation and retraining on FSC-147 on rented
  GPUs, about 1 to 3 weeks, and a new precision risk on exactly the gate that every other idea here
  passes for free. Not before 1, 2 and 5 are out and a low-RAM phone has been measured.

Where a run goes on the Pixel 8 Pro, by ONNX Runtime's profiler: backbone 40%, adapter 29%,
stripes 10%, heads 10% (`bbox_embed` 9.7%), Gelu and small operators 11%. By operator:
`DynamicQuantizeMatMul` 17%, float MatMul 17%, `ConvInteger` 13%, `GridSample` 11%.

Where a 3.1 s run goes on the PC: backbone about 45%, adapter about 31%, upsampling and heads about
20%. Live tensors peak at about 411 MB in `blocks.2` and about 487 MB in the fine-level adapter
attention, on top of 212 MB (anon) after loading.

## Ruled out

| Idea | Measured | Why not |
|---|---|---|
| Exemplar target 56 instead of 80 px | 21% fewer pixels; MAE 4.98 and 12.63 | Mean relative error rises on both sets |
| Exemplar target 40 instead of 80 px | 35% fewer pixels; MAE 4.87 and 11.46 | Photos within 10% fall from 53.6% to 45.2% |
| Low-rank convolutions and MLPs | 99% of the energy needs rank 250 of 256 | Factored convolutions would be slower |
| Arena strategy `kSameAsRequested` | Peak 1086 instead of 862 MB | Higher peak |
| No memory arena | Peak 1258 instead of 866 MB | Higher peak |
| No constant folding above 1 MB, on top of 1 | 28 MB less total, 10% slower | Mapped weights already cover this tensor |
| LiteRT on CPU or GPU delegate | 4.6 GB on CPU, 84 of 3182 ops on GPU | Spike `c3abd85`, Pixel 8 Pro |
| Split with feature cache | Holds 140 MB, 15 to 19% on average | Above the 100 MB limit, at most 24% of counts gain. `perf/split` |
| Encoding before the user marks | 73 to 75% of the work wasted | Input size depends on exemplar and crop |
| `MatMulNBits` 4-bit | Peak 993 MB, MAE 5.66 and 14.02 | Precision falls, slower. `perf/matmul-nbits` |
| `MatMulNBits` 8-bit | Peak -3%, 10.6 s, rel. error 10.70 and 28.00% | Precision falls, slower |
| Float convolutions | 1147 MB, 11.1 s; on XNNPACK 1823 MB and up | Higher peak, slower. `perf/float-conv` |
| WebGPU on the Mali-G715 | A single Conv loses the device | Crashes the phone's UI. `spike/webgpu` |
| 5 threads | On screen up to -8%, without the fastest core +60% | Too risky from one phone |
