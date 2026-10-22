# Upstream release and incremental main review

Official release 0.27.33 resolves to commit 97edfcb538b04abc6c0133e5f28f3be7bc111f81.
Its changes were already reviewed at the preceding main checkpoint: the large split-source
provenance safeguard and Python certifi dependency update. It does not include every later
main commit. The previously reviewed decode/image buffer work remains a separate main track.

Two subsequent main commits advance the reviewed checkpoint to
33779f9a218ec6229b37ddd30b1a1cfd5c59a9a7 (20 beyond the historical reference):

- JSON/NDJSON now forward explicit encoding, preserve already-decoded input and delegate
  implicit byte decoding to the shared detector. DocWeave retains its own explicit charset,
  strict decoder/BOM and source ownership contracts; no Python detection parity is claimed.
- PPTX replaces vertical tabs from line-break operators with newlines in titles, text and
  notes. PPTX remains outside implemented formats.

The incremental source diff changes no PDF implementation or dependency manifest. No new
Python execution, golden regeneration or model inference occurred. Saved reference release
0.27.27 and the measured PDF comparison runtime 0.27.28 remain unchanged. The official
freshness test initially detected release/main drift; it must pass after this reviewed update.
Review JSON records official response identities and complete changed-path coverage.

Official sources: [release](https://github.com/Unstructured-IO/unstructured/releases/tag/0.27.33),
[incremental comparison](https://github.com/Unstructured-IO/unstructured/compare/9a376447d236784a044a4929464179a0164f3329...33779f9a218ec6229b37ddd30b1a1cfd5c59a9a7).
