# Temporary model conversion recipe

This prepares a caller-supplied graph, not a Java runtime/Python test dependency.
Use a temporary virtual environment with the pinned versions in manifest.json;
CPU torch/torchvision came from https://download.pytorch.org/whl/cpu and the remaining
packages from PyPI. Python3.14 required building onnx1.19.1 locally. A Python ONNX
Runtime package was unnecessary; the graph was executed directly by Java.

Fetch only the named config.json, preprocessor_config.json and model.safetensors from
the pinned official HF revision. Verify the published LFS safetensors byte count and
SHA256 before loading. Keep model sources, exported graphs and raw document inputs
outside the repository. Disable remote/backbone fetching and use the fixed local class:

```python
import os
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
from pathlib import Path
import torch
import onnx
from transformers import TableTransformerConfig, TableTransformerForObjectDetection

root = Path(os.environ["DOCWEAVE_MODEL_CACHE"])
config = TableTransformerConfig.from_pretrained(root, local_files_only=True)
config.use_pretrained_backbone = False
config._attn_implementation = "eager"
model, loading = TableTransformerForObjectDetection.from_pretrained(
    root, config=config, local_files_only=True, use_safetensors=True,
    output_loading_info=True)
assert not loading["missing_keys"]
assert not loading["mismatched_keys"] and not loading["error_msgs"]
# v1.1-all had no unexpected keys; the earlier model omitted only three
# training-only num_batches_tracked counters in frozen batch normalization.
assert all(key.endswith("num_batches_tracked") for key in loading["unexpected_keys"])
model.eval()
torch.set_num_threads(4)

class Geometry(torch.nn.Module):
    def __init__(self, source):
        super().__init__()
        self.model = source
    def forward(self, pixel_values, pixel_mask):
        output = self.model(pixel_values=pixel_values, pixel_mask=pixel_mask,
                             return_dict=True)
        return output.logits, output.pred_boxes

with torch.inference_mode():
    torch.onnx.export(
        Geometry(model).eval(),
        (torch.zeros(1, 3, 800, 1000), torch.ones(1, 800, 1000, dtype=torch.int64)),
        root / "structure.onnx", input_names=["pixel_values", "pixel_mask"],
        output_names=["logits", "pred_boxes"], opset_version=17, dynamo=False,
        dynamic_axes={"pixel_values": {2: "height", 3: "width"},
                      "pixel_mask": {1: "height", 2: "width"}})
onnx.checker.check_model(str(root / "structure.onnx"), full_check=True)
```

For byte-identical restoration of the existing graph pins, retain the wrapper attribute name
`self.model` shown above. The former abbreviated example used `self.source`: that changes
exported initializer/node identifiers and therefore graph bytes even when weights and operations
are unchanged. An environment reconstruction verified that `self.model` reproduces the original detector
and v1.1-all graph hashes with the pinned package versions. Always verify the actual result.

Record the graph SHA256, full package versions, loading diagnostics and source hashes.
Exporter metadata/paths may change bytes; never assume a re-export has the same graph
hash without checking it. Treat legacy-export tracing warnings as requiring numerical
validation, not evidence of correctness. Existing parity compared both output tensors
on identical normalized input tensors at two non-square sizes on actual Java21/25,
then separately checked preprocessing pixels across all20 original images. Exporter
controls deliberately distort the source image for numerical shape coverage; they are
not structural accuracy inputs.

The newer published processor's longest_edge-only dictionary is rejected by this pinned
Transformers version. Its explicit equivalent tested override was max_height800 and
max_width800, bilinear resampling and published RGB mean/std. This is a stated profile,
not a claim of unmodified HF configuration or parity with Microsoft's separate1000-pixel
structure pipeline. Java resize used a separable normalized bilinear kernel, pixel-center
sampling, widened downsampling support and22-bit rounded coefficients, rounding/clipping
8-bit channels after each pass.20-image RGB parity is recorded separately.

Temporary source probes and their fingerprints are listed in manifest.json under /tmp.
They establish feasibility; a managed optional Java adapter with its own owned Java tests,
model/input/output limits, lifecycle/cancellation and complete topology is still required.
