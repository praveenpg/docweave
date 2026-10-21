# Controlled Word semantic follow-ups

Status: **Word collection and repeat validation complete; human review pending**. These ten owned documents isolate questions raised by the [first collection](../word-style-cases/word-findings.md). Measured output is saved separately from candidate output. See [findings](word-findings.md). `candidate.json` contains actual DocWeave output from the existing implementation.

Every document has a 3 × 3 table with unique anchors. `manifest.json` pins the inputs, original seed hashes and questions. The original six inputs and observations are unchanged.

| Cases | Isolated question |
| --- | --- |
| numbering-original-order / numbering-numpr-first | Does conditional numPr/outlineLvl child order affect Word numbering or outline? |
| override-original-order / override-numpr-first | Does that ordering affect the unexpected explicit body-outline result? |
| plain-direct-body | Does an unstyled table preserve explicit body outline? |
| conditional-outline-only / conditional-direct-body | Does adding direct body outline suppress conditional outline without numbering involved? |
| numbering-distinct-abstract | Does separating abstract definitions separate the first-row and body sequences? |
| numbering-explicit-restart | Does a startOverride on body numId one restart its sequence? |
| direct-numbering-shared | Does numbering applied directly to paragraphs behave like table-style numbering referencing the same definitions? |

All variants except the intentionally retained original-order baselines place numPr before outlineLvl when both exist. This is a controlled ordering probe, not a claim that all generated OOXML has undergone complete schema validation. The outline-only pair retains the original wholeTable and firstRow branches in both inputs to isolate the addition of direct outline. The plain-table control removes the style reference but retains unused definitions.

The comparator's candidate outline extraction is restricted to table-style semantic ranges, with absent ranges treated as body text. This packet therefore uses body-level direct outline controls; it does not add direct heading controls that would be misrepresented by that comparator. A future direct-heading probe needs candidate instrumentation that exposes effective per-cell headings.

## Local reproduction

Use Java 25 from the repository root and a **new** directory:

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Ddocweave.fixtures.kind=followups \
  -Ddocweave.fixtures.source=benchmarks/review/word-style-cases \
  -Ddocweave.fixtures.output=/tmp/word-followup-reproduction
```

The internal WordFixtureGenerator interface/hidden implementation verifies seed hashes before
creating any output. Default Java tests reproduce all ten follow-ups and four heading controls
under `docweave-validation/target/word-fixtures` and compare expanded XML names, attributes,
ordered text/children and every unchanged package-part byte. Java serialization and ZIP compression
can differ from historical Python output, so regeneration is semantic, not ZIP/XML byte identity.
Each new manifest hashes the actual new ZIP bytes and retains seed provenance and review-pending status.
Original measured inputs retain their exact SHA256 pins and must never be replaced by regenerated
inputs under old Word observations. Generation creates no candidates or Word observations.
Tests also check deterministic repeat generation, overwrite refusal, ordering-only pairs, direct
body/heading values, numbering definitions/restarts, and seed guards. The retired Python fixture
helpers and their structural tests are replaced by Java/JUnit. Current candidate replay continues
to use pinned originals; no Windows VM is needed for local reproduction.

## Next Windows collection

Run the existing collector in the signed-in Windows desktop session, as for the first packet:

```powershell
powershell -File benchmarks/tools/CollectWordStyleReview.ps1 -CaseDirectory benchmarks/review/word-style-followups -OutputPath word-followup-observations.json
```

Then compare locally:

```sh
mvn -pl docweave-validation -am test \
  -Ddocweave.word.packet=benchmarks/review/word-style-followups \
  -Ddocweave.word.observations=/local/new-word-followup-observations.json \
  -Ddocweave.word.comparison=/tmp/new-word-followup-comparison.json
```

SSH transfer requires only the scoped SSH firewall rule, not a disabled firewall. No VM access was attempted during local preparation. The VM can remain shut down until collection is arranged. Matching values still require human review; do not promote these candidates into approved expectations or change production policies from the first collection alone.
