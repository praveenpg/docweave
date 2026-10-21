# Unstructured0.27.29 source review

The official freshness check detected release0.27.29 while preparing the interim PDF
comparison. It correctly failed against the older latest-release pin before review.
The released commit f98602237cb152e9c33fd824fb8ec96037806ad4 is exactly the previously
reviewed main checkpoint. Official0.27.28-to0.27.29 comparison contains one commit:
legacy XLS encryption probing tolerates trailing incomplete padding record headers, plus
its owned regression fixture/test, version and changelog. No PDF or dependency manifest changes.

The exact released pdf.py SHA256 matches the measured0.27.28 installed source:
0dec47c9dac28769a58f76576ccdc4795c81b4554e3d74a7329ed7e27238287e.
The execution remains pinned0.27.28; source equality does not establish full-runtime0.27.29
output parity. No dependency upgrade, model installation or historical golden rewrite occurs.
The reviewed latest-release checkpoint advances; the0.27.27 reference and prior receipts remain.

review.json pins official response/source hashes; observed-drift.json preserves the pre-review
observation. Official sources:
- https://github.com/Unstructured-IO/unstructured/releases/tag/0.27.29
- https://github.com/Unstructured-IO/unstructured/compare/1b02b4d189c260ed9b1aca29583203e233d853cb...f98602237cb152e9c33fd824fb8ec96037806ad4

After review, all three upstream identity/freshness controls pass on actual Java21 and25.
The committed online receipts independently retain both runtime observations. The initial
network-restricted invocation failed to open a socket; a subsequent online check correctly
detected the new release. Historical goldens were not regenerated to make that guard pass.
