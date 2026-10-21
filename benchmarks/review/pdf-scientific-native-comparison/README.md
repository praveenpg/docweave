# Version-pinned native scientific paragraph comparison

All four original scientific PDFs and28 independently selected publisher paragraphs are
unchanged from the preceding evidence. The protocol fixes111 order pairs per profile,
the complete original documents and all six existing DocWeave profiles. Missing, duplicated
or failed paragraphs remain in every denominator. No source strings, captions or model
weights are committed. All four papers are development evidence, not representative accuracy.

Unstructured0.27.28 runs its native `fast` path with English, table inference disabled and
page breaks disabled. No OCR/learned-layout/model, commercial service, provider tuning or
dependency/model download occurs. Existing installed spaCy assets are required before
execution. The actual installed pdf.py SHA-256 matches the previously reviewed0.27.28
source pin: `0dec47c9dac28769a58f76576ccdc4795c81b4554e3d74a7329ed7e27238287e`.

Python executes Unstructured only. The unchanged JVM reference/scoring helpers verify
source identities and score its captured native output. Scoring on Java21/25 agrees for
all28 DocWeave/upstream rows. Existing24 DocWeave rows are reused, without re-extraction
or score changes. All four upstream extractions succeed.

| Original source | References | DocWeave default lines | DocWeave source soft-hyphens | Unstructured fast |
| --- | ---: | ---: | ---: | ---: |
| PLOS0324609 | 9 | 1 | 6 | 0 |
| PLOS0330904 | 12 | 2 | 10 | 2 |
| eLife90049v1 | 4 | 0 | 0 | 0 |
| PLOS0360958 | 3 | 0 | 0 | 0 |

These are exact full-paragraph text-span matches after ASCII whitespace normalization,
not document text recall, visual segmentation or broad reading-order accuracy. For the
source-soft-hyphen DocWeave profile, preserved pairs are15/36,45/66,0/6,0/3; upstream fast
has0/36,1/66,0/6,0/3. The same-publisher newest source remains a failure for both paths.
The fragment profile has the same exact/pair scores as the source-soft-hyphen profile.
Other DocWeave profiles and all misses/duplicates/details remain visible in the full reports.

## Reproduction and limits

Use `benchmarks/tools/capture_unstructured_reading.py` with the frozen manifest, verified
original cache and a NEW capture directory in the pinned runtime. Set writable temporary
cache paths and offline model flags; no automatic installation is allowed. Raw element
captures/runtime inventory stay outside Git. Their hashes and installed versions are
recorded in the accompanying runtime receipt.

Compile `ScoreScientificNativeComparison.java` against the validation test classpath and
run it with manifest, original cache, raw upstream captures, complete previous DocWeave
report and NEW output. Run `../pdf-scientific-reading/VerifyReports.java` for complete
coverage/JVM equality. Owned scorer controls verify full extraction-failure denominators and
unchanged old rows, and reject missing/duplicated cases, failed captures containing successful
text and changed source bytes. These controls and all standalone Java21 source-compilation
checks pass under both JDKs, along with strict private scorer Javadocs. No production
algorithm or default changes. This does not update the older20-image table comparison,
measure recent-source table fidelity, prove semantic source equivalence or establish an
overall library ranking. Historical/degraded scans and calibration remain open.
