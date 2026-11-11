"""Box offsets exist only at candidates, so no peak may ever fall outside of them."""

from pathlib import Path
from types import SimpleNamespace

import numpy as np
import onnx
import onnxruntime
import pytest

import export


def test_candidates_hold_every_peak() -> None:
    random = np.random.default_rng(0)
    for rows, columns in ((512, 512), (112, 512), (32, 16)):
        objectness = random.normal(size=(rows, columns)).astype(np.float32)
        objectness[rows // 2 :] = objectness[rows // 2 :].round()  # Ties, as a flat map has.

        peaks, candidates = export._peaks(objectness), export._candidates(objectness)

        assert peaks.any()
        assert not (peaks & ~candidates).any()


def test_a_stripe_edge_is_a_candidate_though_the_next_stripe_is_higher() -> None:
    objectness = np.zeros((32, 16), dtype=np.float32)
    objectness[1, 3], objectness[2, 3] = 1, 2

    assert export._candidates(objectness)[1, 3]
    assert not export._peaks(objectness)[1, 3]


def test_a_flat_map_is_all_candidates() -> None:
    assert export._candidates(np.ones((32, 16), dtype=np.float32)).all()


def test_every_stripe_keeps_its_heads_in_float() -> None:
    last_copy = export.STRIPES - 1

    assert "/class_embed/class_embed.0/MatMul" in export.FLOAT_NODES
    assert f"/class_embed/class_embed.0_{last_copy}/MatMul" in export.FLOAT_NODES
    assert "/bbox_embed/layers.0/MatMul" in export.FLOAT_NODES
    assert f"/bbox_embed/layers.2_{last_copy}/MatMul" in export.FLOAT_NODES


def test_the_position_embedding_is_upsampled_in_float() -> None:
    assert "/backbone/trunk/MatMul" in export.FLOAT_NODES
    assert "/backbone/trunk/MatMul_1" in export.FLOAT_NODES


def test_quantized_and_renamed_float_nodes_are_found() -> None:
    quantized, renamed, *kept = export.FLOAT_NODES
    nodes = [
        SimpleNamespace(name=f"{quantized}_quant", op_type="MatMulInteger"),
        SimpleNamespace(name=f"{renamed}_new", op_type="MatMul"),
        *(SimpleNamespace(name=name, op_type="MatMul") for name in kept),
        SimpleNamespace(name="/backbone/blocks.0/MatMul_quant", op_type="MatMulInteger"),
    ]

    assert export.quantized_float_nodes(nodes) == [quantized, renamed]


def _model_with_foldable_constants(path: Path) -> int:
    """A small model of the real one's inputs and outputs, with constants to fold."""
    zero = onnx.helper.make_tensor("zero", onnx.TensorProto.FLOAT, [], [0.0])
    last = onnx.helper.make_tensor("last", onnx.TensorProto.INT64, [1], [3])
    nodes = [
        onnx.helper.make_node("Add", ["zero", "zero"], ["nothing"]),
        onnx.helper.make_node("ReduceMean", ["image"], ["mean"], axes=[1], keepdims=0),
        onnx.helper.make_node("Add", ["mean", "nothing"], ["objectness"]),
        onnx.helper.make_node("Unsqueeze", ["objectness", "last"], ["offsets"]),
    ]
    graph = onnx.helper.make_graph(
        nodes,
        "foldable",
        [
            onnx.helper.make_tensor_value_info("image", onnx.TensorProto.FLOAT, [1, 3, None, None]),
            onnx.helper.make_tensor_value_info("exemplars", onnx.TensorProto.FLOAT, [1, None, 4]),
        ],
        [
            onnx.helper.make_tensor_value_info("objectness", onnx.TensorProto.FLOAT, None),
            onnx.helper.make_tensor_value_info("offsets", onnx.TensorProto.FLOAT, None),
        ],
        [zero, last],
    )
    model = onnx.helper.make_model(
        graph, opset_imports=[onnx.helper.make_opsetid("", 17)], ir_version=10
    )
    onnx.save(model, path)
    return len(nodes)


def test_optimizing_replaces_the_model_with_one_needing_no_optimization_at_runtime(
    tmp_path: Path,
) -> None:
    path = tmp_path / "model.onnx"
    nodes = _model_with_foldable_constants(path)
    image = np.random.default_rng(0).random((1, 3, 32, 32), dtype=np.float32)
    inputs = {"image": image, "exemplars": np.zeros((1, 1, 4), dtype=np.float32)}
    expected = onnxruntime.InferenceSession(path).run(None, inputs)

    export.optimize(path)

    options = onnxruntime.SessionOptions()
    options.graph_optimization_level = onnxruntime.GraphOptimizationLevel.ORT_DISABLE_ALL
    actual = onnxruntime.InferenceSession(path, options).run(None, inputs)
    assert len(onnx.load(path).graph.node) < nodes
    for value, reference in zip(actual, expected, strict=True):
        assert np.array_equal(value, reference)
    assert [file.name for file in tmp_path.iterdir()] == ["model.onnx"]


def _model_with_a_large_foldable_constant(path: Path) -> None:
    """A small model of the real one's inputs and outputs, adding a tiled pattern to the image."""
    pattern = onnx.numpy_helper.from_array(
        np.random.default_rng(1).random((1, 3, 8, 8), dtype=np.float32), "pattern"
    )
    repeats = onnx.helper.make_tensor("repeats", onnx.TensorProto.INT64, [4], [1, 1, 64, 64])
    last = onnx.helper.make_tensor("last", onnx.TensorProto.INT64, [1], [3])
    nodes = [
        onnx.helper.make_node("Tile", ["pattern", "repeats"], ["tiled"]),
        onnx.helper.make_node("Add", ["image", "tiled"], ["sum"]),
        onnx.helper.make_node("ReduceMean", ["sum"], ["objectness"], axes=[1], keepdims=0),
        onnx.helper.make_node("Unsqueeze", ["objectness", "last"], ["offsets"]),
    ]
    graph = onnx.helper.make_graph(
        nodes,
        "large-constant",
        [
            onnx.helper.make_tensor_value_info("image", onnx.TensorProto.FLOAT, [1, 3, None, None]),
            onnx.helper.make_tensor_value_info("exemplars", onnx.TensorProto.FLOAT, [1, None, 4]),
        ],
        [
            onnx.helper.make_tensor_value_info("objectness", onnx.TensorProto.FLOAT, None),
            onnx.helper.make_tensor_value_info("offsets", onnx.TensorProto.FLOAT, None),
        ],
        [pattern, repeats, last],
    )
    model = onnx.helper.make_model(
        graph, opset_imports=[onnx.helper.make_opsetid("", 17)], ir_version=10
    )
    onnx.save(model, path)


def test_optimizing_fails_if_it_computes_a_large_constant_ahead(tmp_path: Path) -> None:
    path = tmp_path / "model.onnx"
    _model_with_a_large_foldable_constant(path)
    model = path.read_bytes()

    with pytest.raises(RuntimeError, match=r"tiled \(3 MB\)"):
        export.optimize(path)

    assert path.read_bytes() == model
    assert [file.name for file in tmp_path.iterdir()] == ["model.onnx"]
