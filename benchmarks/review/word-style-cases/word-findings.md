# Word collection findings

Status: automated collection and local comparison complete; independent human review pending.

The six pinned owned documents were opened read-only in Word 16.0, build 16.0.20430, on Windows NT 10.0.26300.0. The collector ran through an interactive scheduled task in the signed-in desktop session, then quit Word. The completed task was removed. Documents and reports remain in the VM user's `docweave-word-review-20261015` directory for inspection.

`word-observations.json` is the original collected output. `word-comparison.json` was produced by the repository comparator, which checked the manifest SHA-256, each input SHA-256, six complete 3 × 3 cell sets, unique text anchors and value ranges. All 33 Python tooling tests pass. Production Java code was unchanged; its last verified baseline remains 436 passing Java tests and strict Javadocs.

Outline values below are zero-based; 9 means body text. Slash-separated triples describe rows with one value repeated across each row unless otherwise specified.

| Case | Word observation | Difference from saved candidate |
| --- | --- | --- |
| unsized-bands | Outline 9 / 9 / 9 | Nine outline values; candidate applies bands without a size |
| edge-flag-without-branch | Outline 0 / 1 / 0 | Nine outline values; candidate excludes the first row and shifts bands |
| row-column-overlap | Top row 4/4/4; second and third rows 5/9/9 | One outline value at the upper-left overlap |
| whole-table-branch | Outline 9 / 9 / 9 | Nine outline values; candidate applies wholeTable outline 6 |
| table-style-numbering | Outline 4 / 9 / 9; labels 1. through 9. in row-major order | Six labels; candidate starts a separate body counter at 1. |
| direct-override-control | Outline 4 / 9 / 9; no labels | Three first-row outline values; candidate keeps explicit body outline |

There are 37 property differences: 31 outline values and six list labels. Every case has at least one difference. The numbering case confirms that Word exposes table-style numbering for this input, while its sequence differs from the candidate. The direct override suppresses list labels but does not suppress the first-row outline in these observations.

## Review limits and next checks

The direct-override source XML was inspected locally: all nine paragraphs contain explicit `numId=0` and `outlineLvl=9`. The unexpected first-row outline therefore requires investigation rather than treating this control as validated. Inspect the paragraph outline settings in Word and compare a fresh plain-document control before changing direct-format precedence.

The generated conditional paragraph XML also places outlineLvl before numPr when both appear, whereas the generator's base and direct paragraph properties place numPr first. Review schema ordering and Word normalization using separate newly named fixtures; do not regenerate or alter the six pinned inputs. This is a fixture-quality question, not an established explanation for the results.

For numbering, both numIds reference one abstract definition. Probe shared versus distinct abstract definitions, explicit restart settings and direct numbering controls before changing counter ownership globally. For banding and overlap, vary one setting at a time and retain Word version, source hashes and observations.

No production policy or expected semantic fixture was changed. These are observations from one Word build, not human approval or universal Word/upstream parity. Human review should inspect outline settings and visible labels; PDF appearance alone cannot establish outline semantics. The VM may remain shut down until follow-up collection is scheduled.
