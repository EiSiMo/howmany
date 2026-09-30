# AGENTS.md

## Goal
A fully open source, fully local Android app that counts objects in a photo, such as a stack of pipes, a group of people or a tray of pills.

## Principles
- Maintainability, modularity and best practices come first. Code is not cheap.
- Deep modules: simple interfaces hiding substantial functionality. Avoid many shallow modules. Design interfaces before implementations.
- Use one consistent domain vocabulary across code, tests and docs.
- Choose languages, tools and libraries by current industry standard: widely adopted, maintained, well documented.
- Challenge me when my requests or technical decisions are suboptimal. Propose the better option before implementing.
- Before non-trivial features, ask questions until requirements are unambiguous.

## Workflow
- Small, verifiable steps. Never outrun the feedback loop.
- TDD: failing test, make it pass, refactor. Test behavior via module interfaces, not internals.
- Test what matters, not every line. Few, meaningful tests keep development fast.
- Once the stack is chosen, add a "Stack & Commands" section to this file (languages, frameworks, exact test/lint/build commands) and keep it current.
- Once the stack is chosen, set up pre-commit hooks for formatter, linter, type checks and tests. Never bypass them.
- Commit and push autonomously after each meaningful green step. Use Conventional Commits.

## Stack & Commands
### App (`android/`)
Kotlin, Jetpack Compose, ONNX Runtime Android, built with Gradle (Kotlin DSL, version catalog in `gradle/libs.versions.toml`); no Android Studio needed. Formatting with Spotless (ktfmt, kotlinlang style). The app bundles the GeCo2 model exported by `model/export.py` (`geco2-int8.onnx`, not committed). Builds download it from the `model-v<N>` GitHub release, checked against a SHA-256 in `app/build.gradle.kts`, so anyone (like F-Droid) can build without the export; a local export in `model/data/` takes precedence. After a new export, publish it as the next release (`gh release create model-v<N> model/data/geco2-int8.onnx`) and update URL and checksum. `counting/` is the counting module (`ObjectCounter`, mirroring `model/counter.py`); the rest is UI. Release builds are signed with keys from `.env` (`.env.example`), unsigned without them: the app signing key signs what users install, on every channel (F-Droid ships it via reproducible builds, Play holds a copy); the upload key only signs uploads to Play. Crashes (Java, native, not responding, killed for lack of memory in the foreground) are reported by the users themselves: on the next start the app offers to mail us a report (`Diagnostics`: device, how the app ended, stack trace, and its log, which it keeps in a file as Android hardly lets apps read their past log), and the about page shares one anytime. R8 obfuscates release stack traces: attach `app/build/outputs/mapping/release/mapping.txt` to every app release on GitHub (Play gets it with the bundle) and read reports with `retrace mapping.txt report.txt` from the SDK's cmdline-tools. The about page lists the licenses of all bundled works: AboutLibraries collects the Gradle dependencies; works bundled otherwise (model, font) need an entry in `android/config/libraries/` and `android/config/licenses/`.

Run from `android/` (JDK 21, Android SDK in `local.properties`, phone with USB debugging attached):
- Build and install: `./gradlew installDebug`
- Build and install the release build (for judging performance): `./gradlew installRelease`
- Release APK for GitHub and F-Droid, signed with the app signing key: `./gradlew assembleRelease`
- Release bundle for Play, signed with the upload key: `./gradlew bundlePlay`
- Unit tests: `./gradlew testDebugUnitTest`
- Device tests (count two benchmark images on the phone and log timings, check the app stays offline and that a report holds its log; `adb logcat -s ObjectCounterTest`): `./gradlew connectedDebugAndroidTest`
- Crash on purpose to check the crash report (debug build, app open; on the next start it offers a report): Java `adb shell am crash run.moritz.howmany`, native `adb shell run-as run.moritz.howmany kill -SEGV $(adb shell pidof run.moritz.howmany)`, not responding `adb shell run-as run.moritz.howmany kill -STOP $(adb shell pidof run.moritz.howmany)`, then tap the app until Android offers to close it. Closing the app from the recents must not offer one.
- Lint: `./gradlew lintDebug`
- Format: `./gradlew spotlessApply`

### Model (`model/`)
Python 3.12+, managed with uv. Everything about the counting model: exporting it for the app, a Python reference counter, and a benchmark of how far its counts are off.

- `export.py` exports GeCo2 (few-shot counting, MIT license) to ONNX and quantizes it to int8. GeCo2 needs its own environment, so this runs on Modal (log in once with `uv run modal setup`) and writes `data/geco2-*.onnx`.
- `counter.py` counts with the exported model exactly as the app does (`count(image_path, exemplars) -> int`). Keep the two in sync.
- `benchmark.py` runs the counter on a fixed random sample of 100 FSC-147 test images (`manifest.csv`, images downloaded on demand into `data/`, gitignored, not redistributed), or with `--photos` on our own photos. Exemplars are boxes around instances of the object to count; by default the counter gets one, as the user marks one in the app (`--exemplars 3` for FSC-147's few-shot setting). Results go to `results/<fsc147|photos>-<N>-exemplar.csv` and `.summary.json`, and are committed, except those on our own photos (gitignored).

Our own photos are a second benchmark of real phone photos (`photos.py`). Each photo is labelled like FSC-147: a category (English, plural), three exemplar boxes and a point per object, which gives the true count. Labels are `model/photos/<name>.json` (committed); the photos stay local in `model/data/photos/` (originals live in Google Drive). New photos arrive as Google Drive exports `~/Downloads/howmany-training-*.zip`; "new photos in Downloads" means: ingest all of them (idempotent, already ingested photos are skipped by hash), suggest a category for each new photo from its (German) name and content, then show the status. Other photos in `~/Downloads` are unrelated.

Labelling conventions: count the category, not the exemplars' look (a red cap as exemplar means all caps, a green tomato means all tomatoes), as in FSC-147. Exclude a photo only if careful people would disagree on its true count, never because the model struggles with it.

Run from `model/`:
- Export the model for the app: `uv run modal run export.py`
- Run the benchmark: `uv run benchmark.py` (`--exemplars 3` for three exemplars, `--photos` for our completely labelled photos)
- Ingest new photos: `uv run photos.py ingest ~/Downloads/howmany-training-*.zip`
- What is left to label: `uv run photos.py status`
- Label photos in the browser: `uv run label.py`
- Rebuild the manifest: `uv run build_manifest.py --size 100 --seed 0 --split test`
- Tests: `uv run pytest`
- Lint/format: `uv run ruff check . && uv run ruff format .`
- Type check: `uv run mypy`
- Pre-commit hooks (once per clone, from repo root): `uv run --project model pre-commit install`

## Conventions
- Code, identifiers, comments, strings and commit messages in English. User-facing text lives in localization resources, never hardcoded.
- Every language the app knows translates every string (`TranslationsTest`, run by the pre-commit hook). A string that rightly reads the same as in English goes into its `sameAsDefault`.
- Changing what a string means gives it a new name, so `TranslationsTest` asks for new translations. Fixes that keep the meaning keep the name.
- Fail loudly: handle errors or propagate them with context, never swallow them.
- Logging via the ecosystem's standard logging library, with levels. No print debugging. Never log secrets.
- A CLI's report (like `run.py`'s summary or `photos.py status`) is its output: print it to stdout. Logging is for diagnostics.
- Few dependencies, each justified. Commit lockfiles.
- Secrets only in `.env` at project root (gitignored). Once there are any, keep `.env.example` with keys, no values.
- License: MIT.
