# Independent Word semantic-style review packet

Status: **Word object-model observations collected; human review pending**. These six documents are owned generated inputs, not unmodified external examples. `candidate.json` records DocWeave output, not Word expectations. No production policy changed during preparation.

Each document has a 3 × 3 table with unique paragraph anchors. `manifest.json` pins the exact bytes and review questions. Outline values use OOXML's 0–9 convention; 9 means body text. Word's object model uses 1–10, so the collector subtracts one.

| Case | Question | Current candidate policy |
| --- | --- | --- |
| unsized-bands | Are bands active without an explicit size? | Default size 1; alternating outlines 0/1/0 by row |
| edge-flag-without-branch | Does firstRow shift bands without a firstRow branch? | First row excluded; outlines 9/0/1 by row |
| row-column-overlap | Which branch wins at the overlap? | Column outline 5 overrides row outline 4 |
| whole-table-branch | Is wholeTable paragraph formatting applied? | Outline 6 overrides base body outline |
| table-style-numbering | Are table-style numbering properties applied? | Nine list items, separate first-row and body counters |
| direct-override-control | Do explicit body outline and numId 0 suppress the style? | Body outline and no list labels |

The bounded search recorded in `research.json` inspected seven additional unchanged external files: seven tables, eighteen visual conditional definitions, **zero conditional numPr/outlineLvl definitions**. It does not close semantic coverage. An external author's pinned Word tests report different behavior for several font-formatting cases. Those reports motivate these questions, but cannot establish paragraph-property behavior. No raw Word measurements from that source were verified here.

## Collect observations on Windows

Requires installed desktop Microsoft Word and Windows PowerShell. From the repository root:

```powershell
powershell -File benchmarks/tools/CollectWordStyleReview.ps1 -CaseDirectory benchmarks/review/word-style-cases -OutputPath word-observations.json
```

The collector opens documents read-only, checks their hashes before and after, records Word version/build/platform, and reads each paragraph's OutlineLevel and ListString. It closes without saving and restores the application settings it changes. The collector completed in a signed-in Windows desktop session on Word 16.0 build 16.0.20430. The saved `word-observations.json` passed hash/anchor validation; `word-comparison.json` records the comparison. See [collection findings](word-findings.md). Its output is object-model evidence; visual rendering and human approval remain separate.

Compare the collected observations:

```sh
mvn -pl docweave-validation -am test \
  -Ddocweave.word.packet=benchmarks/review/word-style-cases \
  -Ddocweave.word.observations=/local/new-word-observations.json \
  -Ddocweave.word.comparison=/tmp/new-word-comparison.json
```

The Java/JUnit comparison uses current library output and validates case/manifest hashes, application identity and complete anchored cell coverage. Replaying pinned saved observations is part of Maven verification; see [Java replay](../../word-style-validation.md). Historical candidates/reports here remain unchanged. It reports differences and always leaves human review pending, even if every value matches. Synthetic test observations are never evidence.

A reviewer should record their name, date, exact Word version/build and case hashes; inspect each question in Word, including visible list labels and paragraph outline settings; and retain screenshots/PDF plus notes where useful. An outline level may not visibly affect rendering, so a PDF alone cannot establish its value. Explain discrepancies before changing production policy. Retain original collection output alongside any review decision. Do not infer general Word or upstream parity from these six cases.

## Regeneration

Build the project and obtain the runtime dependency classpath as described in the benchmark tooling instructions. With the actual runtime classpath in `DOCWEAVE_CP`, run:

```sh
java --class-path "$DOCWEAVE_CP" benchmarks/tools/CreateWordStyleReviewCases.java benchmarks/review/word-style-cases
```

The Java 25 source launcher creates the documents and parses them through DocWeave. Regeneration may change package hashes; old observations then require recollection. Review the generated manifest and candidate output together. External discovery binaries are not part of this owned packet and remain fetch-only.
