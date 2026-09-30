<p align="center">
  <img src="assets/icon.png" width="140" alt="how many? app icon">
</p>

<h1 align="center">how many?</h1>

<p align="center">
  An Android app that counts objects in a photo, fully offline and open source.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white" alt="Android 12+">
  <img src="https://img.shields.io/badge/version-0.1.0-blue" alt="Version 0.1.0">
  <img src="https://img.shields.io/badge/internet%20permission-none-success" alt="No internet permission">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="MIT license"></a>
</p>

| Take a Photo | Mark one Object | Get the Count | Verify Results |
|:---:|:---:|:---:|:---:|
| <img src="assets/screenshots/lemons/take.jpg" alt="A photo of crates of lemons"> | <img src="assets/screenshots/lemons/mark.jpg" alt="A box drawn around one lemon"> | <img src="assets/screenshots/lemons/count.jpg" alt="278 lemons counted"> | <img src="assets/screenshots/lemons/detail.jpg" alt="Zoomed in on the marked lemons"> |

<details>
<summary>More examples</summary>
<br>
<table>
  <tr>
    <td><img src="assets/screenshots/coins/take.jpg" alt="A photo of coins"></td>
    <td><img src="assets/screenshots/coins/mark.jpg" alt="A box drawn around one of the coins"></td>
    <td><img src="assets/screenshots/coins/count.jpg" alt="124 coins counted"></td>
    <td><img src="assets/screenshots/coins/detail.jpg" alt="Zoomed in on the marked coins"></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/tower/take.jpg" alt="A photo of windows"></td>
    <td><img src="assets/screenshots/tower/mark.jpg" alt="A box drawn around one of the windows"></td>
    <td><img src="assets/screenshots/tower/count.jpg" alt="1,024 windows counted"></td>
    <td><img src="assets/screenshots/tower/detail.jpg" alt="Zoomed in on the marked windows"></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/birds/take.jpg" alt="A photo of birds"></td>
    <td><img src="assets/screenshots/birds/mark.jpg" alt="A box drawn around one of the birds"></td>
    <td><img src="assets/screenshots/birds/count.jpg" alt="18 birds counted"></td>
    <td><img src="assets/screenshots/birds/detail.jpg" alt="Zoomed in on the marked birds"></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/nuts/take.jpg" alt="A photo of nuts"></td>
    <td><img src="assets/screenshots/nuts/mark.jpg" alt="A box drawn around one of the nuts"></td>
    <td><img src="assets/screenshots/nuts/count.jpg" alt="28 nuts counted"></td>
    <td><img src="assets/screenshots/nuts/detail.jpg" alt="Zoomed in on the marked nuts"></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/dvds/take.jpg" alt="A photo of DVDs"></td>
    <td><img src="assets/screenshots/dvds/mark.jpg" alt="A box drawn around one of the DVDs"></td>
    <td><img src="assets/screenshots/dvds/count.jpg" alt="236 DVDs counted"></td>
    <td><img src="assets/screenshots/dvds/detail.jpg" alt="Zoomed in on the marked DVDs"></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/sidewalk/take.jpg" alt="A photo of paving stones"></td>
    <td><img src="assets/screenshots/sidewalk/mark.jpg" alt="A box drawn around one of the paving stones"></td>
    <td><img src="assets/screenshots/sidewalk/count.jpg" alt="686 paving stones counted"></td>
    <td><img src="assets/screenshots/sidewalk/detail.jpg" alt="Zoomed in on the marked paving stones"></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/planks/take.jpg" alt="A photo of boxes of flooring"></td>
    <td><img src="assets/screenshots/planks/mark.jpg" alt="A box drawn around one of the boxes of flooring"></td>
    <td><img src="assets/screenshots/planks/count.jpg" alt="68 boxes of flooring counted"></td>
    <td><img src="assets/screenshots/planks/detail.jpg" alt="Zoomed in on the marked boxes of flooring"></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/jellyfish/take.jpg" alt="A photo of jellyfish"></td>
    <td><img src="assets/screenshots/jellyfish/mark.jpg" alt="A box drawn around one of the jellyfish"></td>
    <td><img src="assets/screenshots/jellyfish/count.jpg" alt="36 jellyfish counted"></td>
    <td><img src="assets/screenshots/jellyfish/detail.jpg" alt="Zoomed in on the marked jellyfish"></td>
  </tr>
  <tr>
    <td><img src="assets/screenshots/tictac/take.jpg" alt="A photo of Tic Tacs"></td>
    <td><img src="assets/screenshots/tictac/mark.jpg" alt="A box drawn around one of the Tic Tacs"></td>
    <td><img src="assets/screenshots/tictac/count.jpg" alt="110 Tic Tacs counted"></td>
    <td><img src="assets/screenshots/tictac/detail.jpg" alt="Zoomed in on the marked Tic Tacs"></td>
  </tr>
</table>
</details>

A stack of pipes, a crowd of people, a tray of pills: take a photo, draw a box around
one of the things you want to count, and how many? finds and marks every object that
looks like it. Everything runs on your phone. The app has no internet permission, so
no photo ever leaves the device.

<p align="center">
  <a href="#install">Install</a> ·
  <a href="#usage">Usage</a> ·
  <a href="#accuracy">Accuracy</a> ·
  <a href="#building">Building</a> ·
  <a href="#contributing">Contributing</a> ·
  <a href="#acknowledgements">Acknowledgements</a> ·
  <a href="#license">License</a>
</p>

## Install

<p align="center">
  <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="80" alt="Get it on Google Play">
  <img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="80" alt="Get it on F-Droid">
  <br>
  <sub>Coming soon to Google Play and F-Droid.</sub>
</p>

Until then, you can [build it yourself](#building).

## Usage

1. Take a photo or pick one from your gallery.
2. Drag a box around one of the objects you want to count.
3. Tap Count. how many? marks every object that looks like it.
4. Check the result: drag the edges to count only part of the photo, tap a point to
   remove a wrong one, or tap an object to add one that was missed.

Counting takes a few seconds and works best when the objects are clearly visible and
not too small in the photo.

## Accuracy

On 100 test images from [FSC-147](https://github.com/cvlab-stonybrook/LearningToCountEverything),
with one marked object as in the app, two out of three counts are within 10% of the true
count. That's why the last step is checking the result: a few taps usually fix the count.

## Building

You need JDK 21 and the Android SDK. The build downloads the counting model from this
repository's releases. With a phone attached via USB debugging:

```sh
cd android
./gradlew installRelease
```

[`model/`](model) exports the counting model (`uv run modal run export.py`, needs
[uv](https://docs.astral.sh/uv/) and a [Modal](https://modal.com) account) and holds the
benchmark: `uv run benchmark.py`. See [`AGENTS.md`](AGENTS.md) for all commands.

## Contributing

Contributions are welcome, from bug reports and feature requests to pull requests.
Photos where how many? counts badly are especially helpful: [open an issue](https://github.com/EiSiMo/howmany/issues)
and describe what you tried to count.

## Acknowledgements

how many? stands on the shoulders of others' work:

- [GeCo2](https://github.com/jerpelhan/GECO2) by Jer Pelhan, Alan Lukežič and Matej Kristan
  is the few-shot counting model that does the actual counting (MIT).
- [SAM 2](https://github.com/facebookresearch/sam2) by Meta, whose image encoder GeCo2
  builds on (Apache 2.0).
- [FSC-147](https://github.com/cvlab-stonybrook/LearningToCountEverything) by Viresh Ranjan
  et al., the dataset GeCo2 learned to count on and our benchmark.
- [ONNX Runtime](https://onnxruntime.ai) by Microsoft runs the model on the phone (MIT).
- [Space Grotesk](https://github.com/floriankarsten/space-grotesk) by Florian Karsten is
  the typeface of the count (OFL).

## License

[MIT](LICENSE). The app's about page lists the licenses of everything it bundles.
