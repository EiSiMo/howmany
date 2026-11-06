# Performance ideas

Ideas for lowering peak memory and time without losing precision, and what they measured. Tick an
idea once it is decided, and add every new measurement to the table.

Why it matters: the moto g06 (3.9 GB) was killed in the foreground at 1126 and 1360 MB, and the
model-v2 candidate peaks at 1086 MB on the Pixel 8 Pro.

## Checklist

Keeps counts essentially unchanged:
- [ ] 1. Load a pre-optimized `.ort` model with mapped weights and no prepacking
- [ ] 2. More stripes: stages 1 and 2 windows, `up2`, fine-level adapter attention
- [ ] 3. Split encoder and decoder, reuse features, encode before the user marks
- [ ] 4. Tune thread count, `session.dynamic_block_base` and core affinity per phone
- [ ] 5. Compute box offsets only at objectness peaks

Needs the benchmark to confirm:
- [ ] 6. `MatMulNBits` with 4-bit weights instead of dynamically quantized MatMul
- [ ] 7. Float convolutions on XNNPACK instead of `ConvInteger`

Big bets:
- [ ] 8. Encoder on the GPU via ONNX Runtime's WebGPU execution provider
- [ ] 9. A runtime with 16-bit float activations (MNN, ncnn)
- [ ] 10. Smaller backbone with distillation

First step: profile on the Pixel 8 Pro. The time shares below come from a PC.

## Measurements

PC: Ryzen 5 7500F, 4 threads, ONNX Runtime 1.30, 1024 x 1024 random input, one exemplar, the app's
session options. Anon is resident anonymous memory, which excludes file-backed weights. Phone:
Pixel 8 Pro, worst image `5574.jpg` and easy image `2147.jpg`, in seconds. MAE: 100 FSC-147 images
and 84 photos, one exemplar. A dash means not measured yet.

| Variant | PC peak MB (anon) | PC peak MB (total) | PC load s | PC run s | Phone peak MB | Phone run s (worst / easy) | MAE FSC-147 | MAE photos |
|---|---|---|---|---|---|---|---|---|
| Baseline: model-v2 candidate | 819 | 863 | 1.55 | 3.08 | 1086 | 7.5 / 1.7 | 4.55 | 12.13 |
| 1 | 664 | 812 | 0.74 | 3.08 | – | – | – | – |
| 2 | – | – | – | – | – | – | – | – |
| 3, encoder only, as 1 | 586 | 727 | 0.19 | 1.4 | – | – | same | same |
| 3, decoder only, as 1 | 683 | 733 | 0.20 | 1.8 | – | – | same | same |
| 4 | – | – | – | – | – | – | same | same |
| 5 | – | – | – | – | – | – | same | same |
| 6 | – | – | – | – | – | – | – | – |
| 7 | – | – | – | – | – | – | – | – |
| 8 | – | – | – | – | – | – | – | – |
| 9 | – | – | – | – | – | – | – | – |
| 10 | – | – | – | – | – | – | – | – |

Notes:
- 1: outputs differ from the baseline by at most 4.5e-7, so the counts were not rerun.
- 2: estimated 200 to 300 MB off the peak. Live-tensor simulations miss memory inside kernels, so
  only measured resident memory counts here.
- 3: the split's outputs are identical to the whole model's. It saves no peak memory: the decoder
  alone peaks about as high as the whole model. A recount, or a count after encoding ahead, needs
  only the decoder.

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
