# Explicit multilingual scan profile

The original nine-raster cTDaR set includes Chinese financial statements. The historical
English-only OCR profile is retained as a baseline; its outcomes combine recognition,
source-language mismatch and model/layout limits. Visual inspection of the original
cTDaR_s102 page establishes Chinese content but is not a transcription/accuracy oracle.

The optional evaluation accepts `docweave.pdf.scans.languages=chi_sim,eng` (ordered,
comma-separated, surrounding whitespace ignored). Existing TesseractOptions identifier
validation applies before reading installed model files. Each explicitly selected model
is hashed; reports retain both ordered identifiers and model hashes. Historical absent-property
behavior remains English-only with its prior report schema. `model_sha256` identifies the
first selected model; `language_model_sha256` identifies every explicitly selected model.
No engine/model download, discovery or language inference is added to the library.

The profile uses all nine original images, whole-source embedding,150DPI, the same two
pinned ONNX graphs, PAGE AUTO, CELL SINGLE_BLOCK and the same optional OSD score15 gate
as the earlier extended evaluation. Only the ordered OCR language list changes. Chinese
uses the official tessdata_fast model; the existing English model is from tessdata.
Exact revisions/bytes/SHA256/Git blob identities are in manifest.json. Both local models
were checked against the respective official Git tree. Model variants/training overlap
are not evidence of held-out recognition accuracy.

Use the existing extended-scan invocation with local executable/tessdata/model/hash/cache
properties and a fresh report path, plus:

```sh
-Ddocweave.pdf.scans.languages=chi_sim,eng \
-Ddocweave.pdf.scans.coverage=ALL_PUBLISHED_SAMPLES \
-Ddocweave.pdf.scans.tableDetection=PROVIDER_LAYOUT \
-Ddocweave.pdf.scans.orientation=true \
-Ddocweave.pdf.scans.dpi=150
```

Structural comparison cannot assess whether recognized Chinese text is correct: the source
XML provides table polygons/spans, not transcriptions. No text-accuracy improvement or
calibrated confidence follows from a language/model switch. Original modern raster
provenance does not establish physical scanning. All sources/models remain fetch-only.

Full actual Java21/25 verify passes870tests each with strict Javadocs and ten installed
engine families. Paired nine-raster result entries are identical. Both profiles emit12
candidate tables; s001 retains its OCR_LIMIT before orientation, and the other eight
orientation estimations abstain. The only changed structural-report field versus English
is s003 ordinary OCR line count (59to57); that is not text-accuracy evidence. English-only
replay has the historical report schema and exactly identical result entries. No default
or corpus source/golden is changed. Raw recognized text is not recorded by this harness.
