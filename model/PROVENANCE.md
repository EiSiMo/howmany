# Model provenance

What the app bundles, where it came from, and under which license. The app
counts with the authors' model as it is; nothing is trained here.

## What is bundled

The app ships one pre-exported file, `assets/geco2-int8.onnx`, as the GitHub
release asset `model-v1`:

| File | Bytes | SHA-256 |
| --- | --- | --- |
| `geco2-int8.onnx` | 77,111,674 | `d4ca4eb15fd01c58ef993c100eee41883ceb6c766c4b71d472238d4174f9a3ec` |
| `geco2-fp32.onnx` (source of the int8 one, not shipped) | 299,458,082 | `82ef5b6687b3fcae8c27a032555cdab2b0f0a6a232b1c33b7c8d2de03b162d16` |

## Upstream

- **GeCo2** (code and weights) — <https://github.com/jerpelhan/GECO2> at commit
  `b7086c1db5d9bf2a1718b77eb76715c5f5b963cb`, MIT license. The dense counter is
  the work of Jer Pelhan, Alan Lukežič and Matej Kristan (AAAI 2026).
- **Weights** — `GECO2FSCD.pth`, the project's official FSC-147-trained
  checkpoint, hosted in the project's assets dataset at
  <https://huggingface.co/datasets/jerpelhan/geco2-assets> revision
  `ed3c8ff3753e731fd7074862c0ea49d908785335`. The project distributes these
  weights as part of its MIT-licensed release; the assets dataset carries no
  separate license file.
- **Backbone** — SAM 2 Hiera-B+ (`sam2_hiera_base_plus.pt`, Apache-2.0) by Meta,
  whose image encoder GeCo2 builds on. It is loaded from
  <https://dl.fbaipublicfiles.com/segment_anything_2/072824/sam2_hiera_base_plus.pt>.
- **Training data** — FSC-147 (<https://github.com/cvlab-stonybrook/LearningToCountEverything>),
  used by the authors to train the weights. The app neither redistributes the
  dataset nor its images; only the trained weights are bundled.

## How it was produced

`model/export.py` exports and quantizes the model; it runs on Modal on the CPU
and needs no GPU. It downloads the upstream code and weights at the pinned
revision, builds the dense network, checks it against GeCo2 on a benchmark
image, exports it to ONNX, quantizes the weights dynamically to int8, and
checks the export at several input sizes and exemplar counts. Environment:
Python 3.10, torch 2.7.1, torchvision 0.22.1, onnx 1.18.0, onnxruntime 1.22.0.

## How the app gets it

`android/app/build.gradle.kts` downloads the release asset and verifies it
against the SHA-256 above before bundling it. A local export in `model/data/`
takes precedence over the download; `model/counter.py` and the app's
`counting/` module run the same export.
