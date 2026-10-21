# Narrow native multiline evidence

The explicit RULED_AND_MULTILINE_ALIGNED_TEXT policy adds one first-column continuation per
numeric body row, with complete-block, repeated-spacing and full-union barrier checks.
Historical modes remain unchanged. See docs/pdf-support.md for exact acceptance boundaries.

Owned PDF controls check exact text/HTML, single source ownership, optional union cell bounds,
concurrent reuse, combined-cell field limits, copy preservation, fallback for shifted/uneven/missing/
numeric/overwide continuations, an intervening run and a stroke below the final anchor baseline.

Optional PdfMultilineCorpusTest reuses byte/hash/blob pins from ../pdf-table-corpus/manifest.json.
Native Schools and AnimalSounds output must equal historical aligned-mode output. This is an
external regression comparison, not an independent positive multiline oracle or semantic accuracy
measurement. No third-party PDF, full text, automatic download or external engine is included.
Independent positive multiline corpus and reviewed layout labels remain pending.

upstream-status.json records the official release/main observation. The historical0.27.27
reference remains six commits behind0.27.28; optional freshness gating intentionally reports
this known drift. No historical golden rewrite or Unstructured PDF execution is implied.

Verification reports and SHA-256 identities are saved after Java21/25 verification. Hosted CI
uses owned controls; optional local external corpus properties are not enabled there.
