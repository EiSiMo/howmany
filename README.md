# how many?

## What it does
how many? is a fully open source Android app that counts objects in a photo, such as a stack of pipes, a group of people or a tray of pills. All processing happens locally on the device; no image ever leaves the phone.

## Usage
Take or pick a photo, drag a box around one of the objects you want to count, and tap Count. how many? marks every object that looks like it. Drag the edges to count only part of the photo, and tap to remove a wrong point or add a missed one.

### Building
You need JDK 21 and the Android SDK. The build downloads the counting model. With a phone attached via USB debugging:

```sh
cd android
./gradlew installRelease
```

`model/` exports the counting model (`uv run modal run export.py`, needs [uv](https://docs.astral.sh/uv/) and a [Modal](https://modal.com) account) and holds the benchmark that measures how far the counts are off: `uv run benchmark.py`.

## License
MIT, see [LICENSE](LICENSE). The counting model is [GeCo2](https://github.com/jerpelhan/GECO2) (MIT), built on [SAM 2](https://github.com/facebookresearch/sam2) (Apache 2.0).
