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
The Android app stack is not chosen yet. Feasibility is first measured with the benchmark.

### Benchmark (`benchmark/`)
Python 3.12+, managed with uv. Measures how far a counting prototype's counts deviate from known counts on a fixed random sample of 100 FSC-147 test images (`manifest.csv`). Images are downloaded on demand into `benchmark/data/` (gitignored, not redistributed).

A prototype is a file `benchmark/prototypes/prototype-N.py` defining `quantify(image_path: Path, exemplars: Sequence[Box]) -> int`. Exemplars are three example boxes of the object to count (FSC-147's few-shot setting), standing in for the user marking an example in the app; prototypes may ignore them. Results go to `benchmark/results/prototype-N.csv` and `.summary.json`, and are committed.

A trainable prototype also defines `train(image_dir: Path) -> None`, which trains on the FSC-147 train split and saves its weights as `prototype-N.pt` next to it (committed). Training runs on a Modal cloud GPU (`remote.py`); the train images are cached in the Modal volume `quantify-data`. Log in once with `uv run modal setup`.

Run from `benchmark/`:
- Run a prototype: `uv run run.py prototypes/prototype-N.py` (`--exemplars 1` for a single-tap setting, results get a `-1-exemplar` suffix)
- Deploy a prototype that runs its model on Modal (see its docstring) before running it: `uv run modal deploy prototypes/prototype-N.py`
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
- Few dependencies, each justified. Commit lockfiles.
- Secrets only in `.env` at project root (gitignored). Keep `.env.example` with keys, no values.
- License: MIT.
- README has only "What it does", "Usage", "License". Docs describe the goal, not the current state.
