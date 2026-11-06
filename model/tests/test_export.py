"""Box offsets exist only at candidates, so no peak may ever fall outside of them."""

import numpy as np

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
