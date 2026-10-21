# Explicit single-column OCR policy

`TesseractOptions.PageSegmentation.SINGLE_COLUMN` selects the engine's PSM4 assumption:
one text column with variable sizes. This is caller-supplied document knowledge, not
inferred layout, automatic retry or guaranteed improved recognition. AUTO remains PSM3;
TABLE_CELL choices, resource limits and provider admission/lifecycle remain independent.
No record shape or dependency changes. The public enum gains a constant, so exhaustive
switches need to handle it when recompiling. The addition is unreleased after beta.6.

## Frozen original-source results

All four existing scan originals run uniformly under both fixed language models and
AUTO/SINGLE_COLUMN, with source300/600DPI and ENGINE_DEFAULT thresholding. No preprocessing,
source replacement, text repair, label/association change or per-case mode selection occurs.
All16 cases succeed on each actual JDK21/25. Complete numeric outputs agree across JVMs
except intentionally random PDF container identity. All eight AUTO results remain unchanged
from the prior source-resolution baseline, including prediction hashes and geometry metrics.

| Fixed source/model | AUTO | SINGLE_COLUMN |
| --- | --- | --- |
|1835 Fraktur, declared-order character edits |51/1059 |48/1059 |
|1835 Fraktur, exact associated / missing lines |4 /1 |5 /0 |
|1879 German, exact associated lines |42/50 |42/50 |
|1778 Fraktur, XML-sequence character edits |371 |389 |
|1780 Fraktur, extra predicted lines |9 |1 |
|1780 Fraktur, XML-sequence character edits |82 |24 |

The1780 source retains9/10 associated and3 exact lines under both policies. The1778 source
gains one exact line3->4 but worsens its character diagnostic. Only1835 supplies explicit
line-order labels; other XML-sequence edit counts are not logical-page CER. All model/mode
outcomes and misses/extras are retained in the full reports. This small development study
does not establish general scan accuracy or justify changing the default.

## Verification and reproduction

Both full builds pass1,021 tests with strict public Javadocs. Owned JVM subprocess controls
exercise every page mode concurrently with independent TABLE_CELL requests and verify reuse,
temporary cleanup, admission, cancellation and typed failures. The actual installed engine
recognizes the same owned two-line/six-word raster under all four modes on both JDKs, with
original pixels and bounded geometry retained. Final installed-control receipts enumerate
the tested modes dynamically, including SINGLE_COLUMN4. Changed private options/test/verifier
Javadocs pass both JDKs, including documentation of all touched test helpers.

Run the existing historical corpus test with this manifest, explicit original/model/executable
cache properties, source-resolution mode and a fresh report. Leave the experimental median
property unset. Run `CompareSingleColumnReports.java` with the existing source-resolution
baseline, both fresh reports and a fresh verification path. It requires all16 paired keys,
unchanged binary/model/reference/source/frame identities and exact equality of the eight
AUTO baselines, ignoring only random container PDF identifiers. Raw originals remain outside Git.
