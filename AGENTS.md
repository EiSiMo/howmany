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
Kotlin, Jetpack Compose, ONNX Runtime Android, built with Gradle (Kotlin DSL, version catalog in `gradle/libs.versions.toml`); no Android Studio needed. Formatting with Spotless (ktfmt, kotlinlang style). The app bundles GeCo2 as exported by the benchmark (`benchmark/data/geco2-int8.onnx`, not committed): export it before building. `counting/` is the counting module (`ObjectCounter`); the rest is UI.

Run from `android/` (JDK 21, Android SDK in `local.properties`, phone with USB debugging attached):
- Build and install: `./gradlew installDebug`
- Build and install the release build (signed with the debug key, for judging performance): `./gradlew installRelease`
- Unit tests: `./gradlew testDebugUnitTest`
- Device tests (count a benchmark image on the phone, log timings; `adb logcat -s ObjectCounterTest`): `./gradlew connectedDebugAndroidTest`
- Lint: `./gradlew lintDebug`
- Format: `./gradlew spotlessApply`

### Benchmark (`benchmark/`)
Python 3.12+, managed with uv. Measures how far a counting prototype's counts deviate from known counts on a fixed random sample of 100 FSC-147 test images (`manifest.csv`). Images are downloaded on demand into `benchmark/data/` (gitignored, not redistributed).

A prototype is a file `benchmark/prototypes/prototype-N.py` defining `quantify(image_path: Path, exemplars: Sequence[Box], text: str) -> int`. Exemplars are three boxes around instances of the object to count (FSC-147's few-shot setting), standing in for the user marking an exemplar in the app; text is the FSC-147 category name, standing in for the user typing what to count. Prototypes use whichever prompt they support. Results go to `benchmark/results/prototype-N.csv` and `.summary.json`, and are committed.

A trainable prototype also defines `train(image_dir: Path) -> None`, which trains on the FSC-147 train split and saves its weights as `prototype-N.pt` next to it (committed). Training runs on a Modal cloud GPU (`remote.py`); the train images are cached in the Modal volume `quantify-data`. Log in once with `uv run modal setup`.

Our own photos are a second benchmark of real phone photos (`photos.py`). Each photo is labelled like FSC-147: a category (English, plural, the text prompt), three exemplar boxes and a point per object, which gives the true count. Labels are `benchmark/photos/<name>.json` (committed); the photos stay local in `benchmark/data/photos/` (originals live in Google Drive). New photos arrive as Google Drive exports `~/Downloads/quantify-training-*.zip`; "new photos in Downloads" means: ingest all of them (idempotent, already ingested photos are skipped by hash), suggest a category for each new photo from its (German) name and content, then show the status. Other photos in `~/Downloads` are unrelated.

Labelling conventions: count the category, not the exemplars' look (a red cap as exemplar means all caps, a green tomato means all tomatoes), as in FSC-147. Exclude a photo only if careful people would disagree on its true count, never because the model struggles with it.

Run from `benchmark/`:
- Ingest new photos: `uv run photos.py ingest ~/Downloads/quantify-training-*.zip`
- What is left to label: `uv run photos.py status`
- Label photos in the browser: `uv run label.py`
- Run a prototype: `uv run run.py prototypes/prototype-N.py` (`--exemplars 1` for a single tap, `--exemplars 0` for text only; results get an `-N-exemplar` suffix; `--photos` runs on our completely labelled photos, `-photos` suffix)
- Deploy a prototype that runs its model on Modal (see its docstring) before running it: `uv run modal deploy prototypes/prototype-N.py`
- Export GeCo2 to ONNX for prototype 4 and the app (writes `data/geco2-*.onnx`): `uv run modal run export_geco2.py`
- Train a prototype on Modal: `uv run modal run remote.py --prototype prototypes/prototype-N.py`
- Rebuild the manifest: `uv run build_manifest.py --size 100 --seed 0 --split test`
- Tests: `uv run pytest`
- Lint/format: `uv run ruff check . && uv run ruff format .`
- Type check: `uv run mypy`
- Pre-commit hooks (once per clone, from repo root): `uv run --project benchmark pre-commit install`

## Conventions
- Code, identifiers, comments, strings and commit messages in English. User-facing text lives in localization resources, never hardcoded.
- Fail loudly: handle errors or propagate them with context, never swallow them.
- Logging via the ecosystem's standard logging library, with levels. No print debugging. Never log secrets.
- A CLI's report (like `run.py`'s summary or `photos.py status`) is its output: print it to stdout. Logging is for diagnostics.
- Few dependencies, each justified. Commit lockfiles.
- Secrets only in `.env` at project root (gitignored). Keep `.env.example` with keys, no values.
- License: MIT.
- README has only "What it does", "Usage", "License". Docs describe the goal, not the current state.
