# Reading-order contract and contiguous side streams (PDF area 2, P1)

**Outcome class: measured quality improvement under the stated contract, opt-in.**
- New explicit `SideStreams.SMALLER_TYPE_BLOCKS_CONTIGUOUS`; defaults unchanged.
- Agreement with pinned Unstructured `fast` order decreases on the same papers. This is reported as a compatibility
  trade-off, not hidden.

Development `8567546` (dev), Java 21.

## 1. Contract first

[docs/pdf-reading-order-contract.md](../../../docs/pdf-reading-order-contract.md) v1 was written before any candidate:
- R1–R3: pages, bands, column-major columns;
- **R4: a side stream is one contiguous unit**;
- **R5: a left side stream precedes the main-flow elements that begin at or below its top in its band.**

Pinned upstream order, tagged structure and publisher JATS are treated as evidence, not as the answer.

## 2. Baseline against the contract

The baseline reproduces the recorded upstream line-order counts exactly (`line-order-counts.json`):
- `ROW_FRAGMENTS` gives recent 0, next 0, holdout 17 and eLife 43 reversed pairs.
- All 60 residual reversals are `ORDERABLE` element-order disagreements (whole-element lower bound 0).

Adjudication (`EvaluateReadingOrderContract`, boxes only):

| Paper (page 1) | Residual reversed pairs | Output violates contract | Reference differs from contract | Margin-column runs (R4 wants 1) |
|---|---|---|---|---|
| recent | 0 | 0 | 0 | **8** |
| next | 0 | 0 | 0 | **8** |
| holdout | 17 | 0 | 17 | **7** |
| eLife | 43 | 27 | 16 | **8** |

Findings:
- **The real defect is R4.** Every existing policy interleaves the first-page metadata margin column with the abstract
  or body, because each side block is placed at its own first line. The upstream-agreement metric cannot see this:
  recent and next agree with upstream perfectly while violating R4.
- **The upstream metric partly contradicts the contract.** 33 of the 60 residual pairs are cases where pinned
  Unstructured differs from the contract. Earlier P1 candidates that were rejected for "reversing correct pairs" were
  judged by that metric alone.
- **eLife's margin column is not detected as a side stream** under any policy (0 tagged elements). Its 27 violating
  pairs are a detection gap, not a placement question.
- **Sensitivity:** one holdout pair depends on whether a blank image icon may define the side stream's top. The
  committed tool counts nonblank elements only, which is the rule stated in its Javadoc.

## 3. One candidate, one focused validation

The candidate was frozen before measurement:
- **Change:** emit every block of a detected side stream at the stream's first block, under the unchanged barrier
  rule. Same detection, grouping, main flow, texts and bounds.
- **Acceptance:** holdout runs drop to 1; no element is added, dropped or changed; recent and next are not worse on the
  contract; the full Java 21 suite passes.

| Paper | Runs: blocks / row fragments / **contiguous** | Contract-violating pairs | Upstream reversed: row fragments → contiguous |
|---|---|---|---|
| recent | 8 / 8 / **1** | 0 | 0 → 139 (all reference differs) |
| next | 7 / 8 / **2** | 0 | 0 → 103 (all reference differs) |
| holdout | 7 / 7 / **1** | 0 | 17 → 117 (all reference differs) |
| eLife | 8 / 8 / 8 | 27 (unchanged) | 43 → 43 |

- **next's second run:** "generated or analysed during the current study." wraps to x 245, into the main column, so
  existing detection does not classify it as a side line. The unchanged barrier rule then divides the stream there.
  This is a detection membership limitation.
- **Invariants** (`CheckContiguousSideStreamInvariants`):
  - 7 scientific originals, 147 pages, `COLUMN_PARAGRAPHS`/`PARAGRAPHS`/`LINES`: 0 failures; 12 outputs moved side
    blocks.
  - 7 business, statement, PeerJ and Scientific Reports controls, 74 pages: 0 failures, 0 moves.
- **Tests:** owned unit tests `PdfContiguousSideStreamsTest` (4); `PdfSideStreamBlocksTest` (7) unchanged.

**Verdict:** the frozen acceptance is met. This is a contract improvement and an Unstructured-compatibility regression
on the same papers; the option is documented for users who want contiguous units, for example for chunking.

## Remaining and next action

- **eLife margin-column detection (R3/R4):** not detected as a side stream or a column; needs a separate detection
  hypothesis judged by the contract.
- **Wrapped side lines** reaching into the main column split a stream (next).
- **Contract adjudication of holdout and eLife against JATS**, beyond the indicative check: a frozen mapping is needed.
- **No independent acceptance:** all four papers are development data.

## Reproduce

Compile `benchmarks/tools` (excluding the unrelated `CaptureOllamaRetrieval.java`, which references a removed class)
against `docweave-validation/target/test-classes` and the test classpath, then:

```sh
java -cp TOOLS:CP io.docweave.validation.CompareUpstreamLineOrder benchmarks/review/pdf-scientific-full-body/upstream-manifest.json \
  SOURCES CAPTURES 1 COLUMN_PARAGRAPHS+SIDE_STREAMS_ROW_FRAGMENTS,COLUMN_PARAGRAPHS+SIDE_STREAMS_CONTIGUOUS - - NEW.json
java -cp TOOLS:CP io.docweave.validation.ClassifyResidualLineOrder SOURCES CAPTURES MANIFESTS PROFILE NEW-residual.json
java -cp TOOLS:CP io.docweave.validation.EvaluateReadingOrderContract SOURCES NEW-residual.json \
  recent=190:380,next=190:380,elife=160:380,holdout=190:380 PROFILES NEW-contract.json
```
