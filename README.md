# quantify

## What it does
quantify is a fully open source Android app that counts objects in a photo, such as a stack of pipes, a group of people or a tray of pills. All processing happens locally on the device; no image ever leaves the phone.

## Usage
Take or pick a photo, drag a box around one of the objects you want to count, and tap Count. quantify marks every object that looks like it. Drag the edges to count only part of the photo, and tap to remove a wrong point or add a missed one.

### Building
You need JDK 21, the Android SDK, [uv](https://docs.astral.sh/uv/) and a [Modal](https://modal.com) account for the one-time model export. With a phone attached via USB debugging:

```sh
cd model
uv run modal setup              # once
uv run modal run export.py      # exports the counting model to model/data/
cd ../android
./gradlew installRelease
```

`model/` also holds the benchmark that measures how far the counts are off: `uv run benchmark.py`.

## License
MIT, see [LICENSE](LICENSE). The counting model is [GeCo2](https://github.com/jerpelhan/GECO2) (MIT), built on [SAM 2](https://github.com/facebookresearch/sam2) (Apache 2.0).
