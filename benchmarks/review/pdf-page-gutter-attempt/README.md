# Page-supported OCR gutter candidate — not promoted

This is a bounded follow-up to the
[degraded line-geometry diagnosis](../pdf-degraded-line-geometry/README.md).
The candidate and its full population are retained; production was restored and contains
no `PAGE_GUTTERS` option. No accuracy score, reference, model or default was changed.

## Frozen candidate

`proposal.txt` was written before candidate OCR execution. The candidate adds an explicit
`PAGE_GUTTERS` choice. It sweeps recognition-order word-gap intervals in the central half
of the raster: gap at least the page median word height, at least two words on each side,
at least four distinct source tops spanning four median heights. It chooses the interval
with greatest distinct-line support, then width, then the first leftmost exact tie. With
support it splits only across that midpoint; without support it retains `WIDE_GAPS`.
The deadline and output-line limits stay enforced. No pixels or recognized words change.

Five owned JVM tests cover narrow gutters/wide intra-column gaps, insufficient support,
duplicate physical baselines, one-token numeric tab stops, detailed word views and line
limits. Seven previous wide-gap tests pass. Strict private Javadocs pass. The complete
candidate Java21 reactor verification also passes (1,353 tests, zero failures/errors/skips).
Those successes did not establish transfer quality: the original historical source fails.

## One source run and one transfer run

The two derivative manifests preserve all four sources and both pinned models from the
previous wide-gap study, source-resolution AUTO/ENGINE_DEFAULT, OMP_THREAD_LIMIT=1.
Exactly eight candidate OCR calls were made: four degraded observations, then four
historical transfer observations. No new engine/model download or threshold sweep.

| Source/model | Associated lines: wide gaps → candidate | Unreferenced predictions | Joint reference-line character errors |
| --- | --- | --- | --- |
| 1778 German | 26→34 /38 | 17→8 | 391→337 |
| 1778 Fraktur | 27→31 /38 | 12→8 | 333→342 |
| 1780 German | 9→9 /10 | 9→9 | 51→51 |
| 1780 Fraktur | 9→9 /10 | 9→9 | unchanged |
| 1835 German | 21→21 /22 | 0→0 | 103→103 |
| 1835 Fraktur | 21→21 /22 | 0→0 | 50→50 |
| 1879 German | 50→43 /50 | 1→17 | 7→538 |
| 1879 Fraktur | 50→43 /50 | 1→17 | 53→579 |

The source-model-frame guards pass on both complete comparisons. A separate JVM checker
also requires all eight recognized-text hashes, source/model identities and OCR frames to
remain unchanged. The reports contain all gains and regressions. XML-order edit counts
are unchanged; undeclared XML order is not whole-page logical CER. In particular, more
associated Fraktur lines on1778 does not imply better line-character alignment: exact
associated lines decrease4→3 and joint errors increase333→342.

## Disposition

Do not promote the candidate. Repeated gaps alone also occur inside single-column prose;
the1879 regression disproves this candidate's transfer assumption. The current owned
single-numeric-tab-stop negative was insufficient to represent that source. A future
investigation needs an independently fixed single-column paragraph control and evidence
against words crossing a proposed gutter, not another threshold tuned on these outputs.
No such refinement or second parameter attempt was made in this pass.

Production `TesseractOptions`/`TesseractTsv` were restored byte-for-byte to the preceding
commit, candidate-only source/test classes removed, and the Java21 reactor repackaged
successfully with tests skipped to replace the temporary candidate JAR/classes. Full
candidate test success is historical evidence, not a claim that the discarded feature is
available. The last accepted production verification remains the existing1,348-test run.

`candidate.patch` retains the exact proposed implementation and owned tests, including
new files. `historical.json`/`degraded.json` retain complete observations; comparison files
use the existing JVM evaluator and prior integrated wide-gap captures. The manifests,
proposal, text checker and verification receipt are additive. No release was performed.
