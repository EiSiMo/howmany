"""Learned density counting: frozen DINOv2 patch features plus a small trained density head.

The image is encoded by a frozen, self-supervised DINOv2 ViT-S/14. A small convolutional head
sees every patch feature together with the image's global (CLS) feature and predicts how many
objects lie in that patch; the count is the sum over all patches. The global feature lets the
head infer which object class is the repeated one, so no exemplars or text prompt are needed.

The head is trained on point annotations from the FSC-147 train split, which is disjoint from
the benchmark's test images:

    uv run modal run remote.py --prototype prototypes/prototype-2.py

Exemplars are ignored: this prototype infers what to count from the image alone.
"""

import json
import logging
import random
from collections.abc import Sequence
from functools import cache
from pathlib import Path

import numpy as np
import torch
from PIL import Image
from torch import nn
from transformers import AutoModel

from dataset import ANNOTATIONS_FILE, SPLITS_FILE, Box

logger = logging.getLogger(__name__)

ENCODER = "facebook/dinov2-small"
FEATURE_DIM = 384
PATCH_SIZE = 14
GRID = 32
INPUT_SIZE = GRID * PATCH_SIZE
HEAD_PATH = Path(__file__).with_suffix(".pt")
DATA_DIR = Path(__file__).parent.parent / "data"
IMAGENET_MEAN = torch.tensor([0.485, 0.456, 0.406])[:, None, None]
IMAGENET_STD = torch.tensor([0.229, 0.224, 0.225])[:, None, None]
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")


class DensityHead(nn.Module):
    """Maps a grid of patch features plus the global feature to per-patch object counts."""

    def __init__(self) -> None:
        super().__init__()
        self.layers = nn.Sequential(
            nn.Conv2d(2 * FEATURE_DIM, 256, 1),
            nn.GELU(),
            nn.Conv2d(256, 128, 3, padding=1),
            nn.GELU(),
            nn.Conv2d(128, 64, 3, padding=1),
            nn.GELU(),
            nn.Conv2d(64, 1, 1),
            nn.Softplus(),
        )
        # Start near a plausible density (~0.02 objects per patch) so Softplus is not saturated.
        final = self.layers[-2]
        assert isinstance(final, nn.Conv2d) and final.bias is not None
        nn.init.zeros_(final.weight)
        nn.init.constant_(final.bias, -4.0)

    def forward(self, patches: torch.Tensor, cls: torch.Tensor) -> torch.Tensor:
        context = cls[:, :, None, None].expand(-1, -1, patches.shape[2], patches.shape[3])
        return self.layers(torch.cat((patches, context), dim=1)).squeeze(1)  # type: ignore[no-any-return]


@cache
def _encoder() -> nn.Module:
    return AutoModel.from_pretrained(ENCODER).to(DEVICE).eval()  # type: ignore[no-any-return]


def _to_tensor(image: Image.Image) -> torch.Tensor:
    resized = image.resize((INPUT_SIZE, INPUT_SIZE), Image.Resampling.BICUBIC)
    pixels = torch.from_numpy(np.asarray(resized, dtype=np.float32) / 255).permute(2, 0, 1)
    return (pixels - IMAGENET_MEAN) / IMAGENET_STD


@cache
def _head() -> DensityHead:
    if not HEAD_PATH.exists():
        raise FileNotFoundError(f"{HEAD_PATH} missing, train it with remote.py")
    head = DensityHead()
    head.load_state_dict(torch.load(HEAD_PATH, map_location="cpu", weights_only=True))
    return head.eval()


@torch.inference_mode()
def encode(images: list[Image.Image]) -> tuple[torch.Tensor, torch.Tensor]:
    """Return patch features (B, C, GRID, GRID) and global features (B, C), both on the CPU."""
    pixels = torch.stack([_to_tensor(image) for image in images]).to(DEVICE)
    hidden = _encoder()(pixel_values=pixels).last_hidden_state.cpu()
    patches = hidden[:, 1:].transpose(1, 2).reshape(len(images), FEATURE_DIM, GRID, GRID)
    return patches, hidden[:, 0]


@torch.inference_mode()
def quantify(image_path: Path, exemplars: Sequence[Box], text: str) -> int:
    patches, cls = encode([Image.open(image_path).convert("RGB")])
    return round(float(_head()(patches, cls).sum()))


def _target_grid(points: list[list[float]], width: int, height: int) -> np.ndarray:
    grid = np.zeros((GRID, GRID), dtype=np.float32)
    for x, y in points:
        column = min(GRID - 1, max(0, int(x / width * GRID)))
        row = min(GRID - 1, max(0, int(y / height * GRID)))
        grid[row, column] += 1
    return grid


def _encode_dataset(image_dir: Path, cache_path: Path) -> dict[str, torch.Tensor]:
    """Encode all train-split images once and cache features and target grids on disk."""
    if cache_path.exists():
        return torch.load(cache_path, weights_only=True)  # type: ignore[no-any-return]
    annotations = json.loads((DATA_DIR / ANNOTATIONS_FILE).read_text())
    train_names = set(json.loads((DATA_DIR / SPLITS_FILE).read_text())["train"])
    names = sorted(p.name for p in image_dir.glob("*.jpg") if p.name in train_names)
    logger.info("Encoding %d training images", len(names))
    patches, clss, targets = [], [], []
    for start in range(0, len(names), 16):
        chunk = names[start : start + 16]
        images = [Image.open(image_dir / n).convert("RGB") for n in chunk]
        p, c = encode(images)
        patches.append(p.half())
        clss.append(c)
        for name, image in zip(chunk, images, strict=True):
            targets.append(_target_grid(annotations[name]["points"], *image.size))
        logger.info("Encoded %d/%d", start + len(chunk), len(names))
    dataset = {
        "patches": torch.cat(patches),
        "cls": torch.cat(clss),
        "targets": torch.from_numpy(np.stack(targets)),
    }
    torch.save(dataset, cache_path)
    return dataset


def train(image_dir: Path, epochs: int = 30, batch_size: int = 32, holdout: int = 150) -> None:
    dataset = _encode_dataset(image_dir, DATA_DIR / "prototype-2-features.pt")
    dataset = {key: value.to(DEVICE) for key, value in dataset.items()}
    size = len(dataset["targets"])
    torch.manual_seed(0)
    random.seed(0)
    permutation = torch.randperm(size).to(DEVICE)
    held_out, fitted = permutation[:holdout], permutation[holdout:]

    head = DensityHead().to(DEVICE)
    optimizer = torch.optim.AdamW(head.parameters(), lr=3e-4, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, epochs)
    for epoch in range(epochs):
        head.train()
        order = fitted[torch.randperm(len(fitted), device=DEVICE)]
        total = 0.0
        for start in range(0, len(order), batch_size):
            index = order[start : start + batch_size]
            p = dataset["patches"][index].float()
            c, t = dataset["cls"][index], dataset["targets"][index]
            if random.random() < 0.5:
                p, t = p.flip(-1), t.flip(-1)
            predicted = head(p, c)
            true_count = t.sum((1, 2))
            count_error = (predicted.sum((1, 2)) - true_count).abs() / (true_count + 1)
            loss = ((predicted - t) ** 2).sum((1, 2)).mean() + count_error.mean()
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()
            total += float(loss) * len(index)
        scheduler.step()
        head.eval()
        with torch.no_grad():
            p = dataset["patches"][held_out].float()
            predicted_counts = head(p, dataset["cls"][held_out]).sum((1, 2))
            true_counts = dataset["targets"][held_out].sum((1, 2))
            mae = float((predicted_counts - true_counts).abs().mean())
        logger.info("Epoch %d: loss %.4f, held-out MAE %.2f", epoch + 1, total / len(order), mae)
    torch.save({key: value.cpu() for key, value in head.state_dict().items()}, HEAD_PATH)
    logger.info("Saved head to %s", HEAD_PATH)
