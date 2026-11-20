import tempfile
import unittest
from pathlib import Path

import torch
from safetensors import safe_open
from safetensors.torch import save_file

from weight_edit import REPLACEMENT_ROWS, ROW_INDEX, TENSOR
from weight_edit import apply_patch, export_weights, fit_delta, write_patch


class WeightEditTest(unittest.TestCase):
    def test_tensor_edit_separates_cases_without_moving_the_cutoff(self):
        # The first feature contains a yes bias. The second separates these cases.
        original = torch.tensor([1.0, 0.0])
        features = torch.tensor([[2., 1.], [2., -1.], [3., 1.], [3., -1.]])
        targets = torch.tensor([2., -2., 2., -2.])
        delta = fit_delta(features, targets, original, 0.003)
        self.assertTrue(torch.all(features @ original > 0))

        scorer = torch.nn.Linear(2, 1)
        with torch.no_grad():
            scorer.weight.copy_(original[None])
        bias = scorer.bias.detach().clone()
        with tempfile.TemporaryDirectory() as tmp:
            patch = Path(tmp) / "edit.safetensors"
            write_patch(patch, (original + delta)[None], "base test digest")
            with self.assertRaisesRegex(ValueError, "checkpoint"):
                apply_patch(scorer, patch, "different test digest")
            self.assertTrue(torch.equal(scorer.weight[0], original))
            apply_patch(scorer, patch, "base test digest")
        self.assertTrue(torch.equal(scorer.bias, bias))
        self.assertTrue(torch.equal(
            (scorer(features)[:, 0] - bias >= 0), targets > 0
        ))


    def test_export_preserves_all_other_tensors(self):
        original = torch.tensor([[1.0, 2.0]])
        edited = torch.tensor([[2.0, 1.0]])
        backbone = torch.eye(2)
        with tempfile.TemporaryDirectory() as tmp:
            base, patch, out = (Path(tmp) / n for n in ("base.safetensors", "patch.safetensors", "edited.safetensors"))
            save_file({TENSOR: original, "encoder.example.weight": backbone}, base, metadata={"format": "pt"})
            write_patch(patch, edited, "base test digest")
            export_weights(base, out, patch, "base test digest")
            with safe_open(str(out), framework="pt") as saved:
                self.assertTrue(torch.equal(saved.get_tensor(TENSOR), edited))
                self.assertTrue(torch.equal(saved.get_tensor("encoder.example.weight"), backbone))
                self.assertEqual(saved.metadata()["format"], "pt")
                self.assertIn("modification", saved.metadata())
            with safe_open(str(base), framework="pt") as saved:
                self.assertTrue(torch.equal(saved.get_tensor(TENSOR), original))


    def test_two_neuron_patch_programs_a_projection_and_preserves_other_rows(self):
        scorer = torch.nn.Sequential(
            torch.nn.Identity(), torch.nn.Linear(3, 3), torch.nn.GELU(), torch.nn.Linear(3, 1)
        )
        outside = scorer[1].weight[1].detach().clone()
        direction = torch.tensor([0.25, -0.5, 0.75])
        options = torch.tensor([[1., 0.2, 0.3], [0.1, 0.4, 1.]])
        with tempfile.TemporaryDirectory() as tmp:
            patch = Path(tmp) / "rows.safetensors"
            save_file({TENSOR: torch.tensor([[1., 0., -1.]]),
                       ROW_INDEX: torch.tensor([0, 2]),
                       REPLACEMENT_ROWS: torch.stack((direction, -direction))},
                      patch, metadata={"base_checkpoint_sha256": "base test digest"})
            apply_patch(scorer, patch, "base test digest")
        self.assertTrue(torch.equal(scorer[1].weight[1], outside))
        torch.testing.assert_close(scorer(options)[:, 0] - scorer[3].bias, options @ direction)


if __name__ == "__main__":
    unittest.main()
