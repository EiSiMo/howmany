"""Loading counting prototypes: Python files in prototypes/ that the benchmark runs or trains."""

import importlib.util
from collections.abc import Callable, Sequence
from pathlib import Path
from types import ModuleType

from dataset import Box

# quantify(image_path, exemplars, text) -> count, as every prototype defines it.
Quantify = Callable[[Path, Sequence[Box], str], int]


def load_module(path: Path) -> ModuleType:
    """Import a Python file by path, as prototype files have hyphenated names."""
    spec = importlib.util.spec_from_file_location(path.stem.replace("-", "_"), path)
    if spec is None or spec.loader is None:
        raise ValueError(f"Cannot load prototype from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_prototype(path: Path) -> Quantify:
    """The quantify function of the prototype file at path."""
    module = load_module(path)
    quantify = getattr(module, "quantify", None)
    if not callable(quantify):
        raise ValueError(f"{path} does not define quantify(image_path, exemplars, text)")
    return quantify  # type: ignore[no-any-return]
