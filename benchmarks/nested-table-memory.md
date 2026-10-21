# Nested-table allocation and live-heap observations

Measured2026-10-15 on Linux amd64, Corretto25.0.4+7-LTS, G1, `-Xms512m -Xmx512m`.
Checkout baseline8109fd0; production extraction is unchanged from3af4622. An unrelated local
formatting-only docweave-all/pom.xml edit was left untouched (canonical XML matches the committed
POM). The [saved packet](review/nested-table-memory/README.md) pins the exact actual runtime JARs,
probe source, six inputs and raw reports. These are owned synthetic probes, not rendering evidence.

## Method

ProfileNestedTableMemory.java creates two shapes per format: one outer cell with128 direct child
tables, and a12-child-level chain. Every table cell has256 deterministic mixed-Unicode code points
including escaped markup characters. Source generation and DocumentSource construction occur outside
measurements. Markdown uses EXTRACT_DOM. Both modes use normal input/table/preservation limits.
The probe checks identical default root elements/IDs/text/HTML/metadata/warnings after removing the
two descriptor keys, and checks preserved-child counts, before measuring. Verification runs in a
separate returning frame so checked results are not retained during heap observations.

Inputs are generated once, hashed and reused byte-for-byte across runs/modes, including DOCX ZIP
packages. Three fresh JVMs each warm every case10 times and measure10 partition calls using the
calling thread's ThreadMXBean allocated-byte counter. The table reports the median of the three
trial means. It excludes source/fixture construction and profiler/JFR overhead, but includes normal
source loading, parsing, root emission and preservation. Modes run sequentially, FLATTEN before
PRESERVE_METADATA, so JIT/cache/order effects remain a limitation. No concurrent workload or
throughput/latency inference is made.

A separate fresh JVM warms10 times per case and takes three before/result-held/result-released
whole-process class histograms. Each histogram requests full GC through the local HotSpot diagnostic
MBean; no management port is opened. Source liveness is fenced through observations. Reported heap
deltas are medians of result-held minus released process totals. They are **not exact object-graph
retained sizes, peak heap, leak proofs, or portable memory limits**. GC/JIT/global cache activity can
contribute; released-minus-before drift ranged0..2,016 bytes in these runs. Deep HTML's three deltas
were157,408/157,408/156,528 bytes; other per-case deltas were identical across the three observations.

A further separate JVM records20 calls per case using JFR ObjectAllocationSample, throttle off. Only
calling-thread samples with stacks are aggregated, and the first qualifying sample is excluded because
its weight can predate recording. Nearest DocWeave frame and allocation-class/stack weights are
**sampled estimates**, not exact allocated bytes. Raw JFR stays local; extracted reports and hashes
are saved. Heap and JFR probes do not contaminate the separate allocation-counter trials.

## Results

All numbers below are bytes; allocation is per call, and heap columns are process observations.

| Input | FLATTEN allocation | PRESERVE allocation | Allocation ratio | FLATTEN held/released delta | PRESERVE held/released delta |
| --- | ---: | ---: | ---: | ---: | ---: |
| wide HTML | 2,707,799 | 5,811,938 | 2.15 | 173,248 | 495,288 |
| wide Markdown DOM | 4,076,874 | 6,470,622 | 1.59 | 173,736 | 495,776 |
| wide DOCX | 20,979,766 | 21,241,982 | 1.01 | 177,792 | 532,600 |
| deep HTML | 397,512 | 1,454,013 | 3.66 | 18,248 | 157,408 |
| deep Markdown DOM | 663,143 | 1,458,792 | 2.20 | 18,736 | 157,896 |
| deep DOCX | 6,854,238 | 6,996,084 | 1.02 | 18,848 | 161,472 |

HTML/Markdown descriptor payload accounting is97,697 units wide and53,735 deep. DOCX is104,993 and
54,617 because its whitelisted semantic snapshot includes merge policy. Units count string keys/values
by Unicode code point plus one per scalar/container, including repeats; they are not heap bytes.
The greater process-held delta is expected to include object/container/string representation overhead.

Fresh-JVM trial means for wide DOCX FLATTEN ranged20,865,962..21,096,147 and PRESERVE
21,217,470..21,528,594; deep DOCX ranged6,849,014..6,972,170 and6,992,042..7,115,708.
Small DOCX allocation differences are close to warmup/JIT variability and should not be generalized.
Raw per-call values/trial ranges and process totals are retained in the packet.

## Findings and next bounded change

The robust signal is HTML preservation's additional allocation: about3.10MB wide and1.06MB deep
(decimal MB), with Markdown DOM adding about2.39MB and0.80MB. Default Markdown DOM already performs
HTML source-table checks; HTML preservation additionally enables those checks. Therefore mode deltas
include validation as well as snapshot construction; they do not isolate descriptor copying alone.

JFR preservation runs show HTML inline StringBuilder growth and supplementary-character temporary
char arrays (Character.toChars via appendCodePoint), rendered-text String copies, bounded HTML
serialization and missing-span parsing. Nearest-frame sampled weights for HtmlNestedTables.span
were8,830,968 bytes in wide Markdown and1,570,712 in deep HTML over20 recorded calls. Throwable
backtrace arrays from NumberFormatException appear in allocation stacks. Source inspection confirms
that absent colspan/rowspan attributes are passed as empty strings to Integer.parseInt in the HTML
walker and preservation span helpers. The fixtures intentionally have no span attributes. This is a
specific low-risk next target: short-circuit missing/empty attributes, preserving all other existing
integer parsing and invalid-span semantics. Measure again with the same pinned inputs before claiming
a saving. Larger normalization reuse or Unicode append changes should be evaluated separately.

DOCX samples are dominated by body-view/XML traversal, native table HTML escaping and package/parser
work. These reports do not justify changing DOCX ownership/numbering or reducing metadata snapshots.
Increased held-output cost is inherent to retaining child grids/text/annotations, but these observations
are insufficient to conclude no further retention optimization exists.

No production code changed in this profiling step. Existing document-wide budgets and immutable
ownership remain in place. A512MiB successful run is not a bounded-heap guarantee.

## Reproduce

After a normal reactor build/runtime-classpath setup, from the repository root:

```sh
DOCWEAVE_CP=$(cat docweave-all/target/runtime-classpath.txt)
java -Xms512m -Xmx512m -XX:+UseG1GC --class-path "$DOCWEAVE_CP" \
  benchmarks/tools/ProfileNestedTableMemory.java allocation \
  benchmarks/review/nested-table-memory/inputs /tmp/nested-allocation-new-1
java -Xms512m -Xmx512m -XX:+UseG1GC --class-path "$DOCWEAVE_CP" \
  benchmarks/tools/ProfileNestedTableMemory.java heap \
  benchmarks/review/nested-table-memory/inputs /tmp/nested-heap-new
java -Xms512m -Xmx512m -XX:+UseG1GC --class-path "$DOCWEAVE_CP" \
  benchmarks/tools/ProfileNestedTableMemory.java stacks \
  benchmarks/review/nested-table-memory/inputs /tmp/nested-stacks-new
```

Repeat allocation with two more fresh output directories/JVMs for the three-trial comparison.
Every output directory must be new; a report is written only after every case completes. Input names,
counts and hashes are checked before partitioning. Reports pin runtime/tool/input hashes and JVM flags.
Use `generate NEW_INPUT_DIRECTORY` to create another owned packet; DOCX binary timestamps may differ,
so use the saved inputs to reproduce these exact source bytes. Do not overwrite historical reports or
compare samples as deterministic values. These optional profiling commands are not CI timing gates;
normal Java/Javadoc/Python/saved-Word checks remain separate. The Windows VM is unnecessary.

## Follow-up: skip parsing absent/empty HTML spans — 2026-10-16

The walker now omits absent/empty span attributes directly, and preservation helpers return the
implicit unit span/no serialized attribute directly. Every nonempty value still follows the previous
Integer.parseInt behavior, including whitespace, signs, Unicode digits, nonpositive values and overflow.
A new module regression checks root and child snapshots for these classes. Existing grids, limits,
source offsets, diagnostics and public APIs are unchanged.

[Fresh before/after reports](review/empty-html-span-allocation/README.md) use the same pinned inputs,
probe source, JDK/flags, ten warmups/calls and three fresh JVMs per build, alternating before/after.
Baseline bd1af13 was rebuilt in an isolated worktree, rather than comparing only yesterday's figures.
The table reports median allocated bytes per call across trial means:

| Preservation case | Before | After | Reduction |
| --- | ---: | ---: | ---: |
| wide HTML | 5,811,669 | 5,346,494 | 8.00% |
| wide Markdown DOM | 6,465,052 | 6,019,612 | 6.89% |
| deep HTML | 1,454,179 | 1,388,144 | 4.54% |
| deep Markdown DOM | 1,458,704 | 1,393,672 | 4.46% |

Default HTML/Markdown deltas were small (0.01–0.39%); DOCX controls varied up to0.85% and are not
attributed to this change. Descriptor payload units match. This is an allocation result for these
fixtures, not a speed, peak-heap or exact-retained-size claim. No new live-heap/JFR comparison was
needed for this narrowly scoped allocation change. The next investigation should isolate normalized
inline text/supplementary-character append allocations before changing traversal or ownership.

## Follow-up: copy original UTF-16 inline slices — 2026-10-16

The isolated MeasureHtmlInlineAllocation.java probe feeds a preconstructed TextNode directly to the
hidden HTML inline renderer (ordinary common classloader), excluding loading/DOM parsing/emission.
Twenty warmups/twenty measured calls, three fresh JVMs per build, same Corretto25/G1/512MiB flags:

| Input unit repeated4096times | Before bytes/render | After bytes/render |
| --- | ---: | ---: |
| ASCII words | 196,944 | 196,944 |
| Café/漢 words | 401,752 | 401,752 |
| 😀/𐐀 words | 590,168 | 393,560 |

Every trial gave these values. The supplementary case saves196,608bytes (33.3%) for8,192
supplementary characters on this runtime. This supports the earlier JFR Character.toChars/temporary
char-array finding. It is not a portable object-size, throughput or whole-document memory guarantee.

HtmlInlineText now appends the source String's original one/two-code-unit slice after its existing
space/limit checks. Code-point counters, whitespace state, interruption checkpoints and offsets are
unchanged. This also preserves unpaired surrogate code units as the prior internal renderer did;
public source validation and parser repair remain unchanged. Two new tests exercise1,000 seeded
Unicode/combining/unpaired-surrogate token sequences and exact supplementary point limits plus
interruption flags. Existing links, nesting, provenance, defaults and owned corpus checks pass.

[Full-pipeline fresh reports](review/inline-unicode-allocation/README.md) use baseline5084954 in a
separate rebuilt worktree, the same pinned inputs/profiler, and three alternating before/after JVM
trials (ten warmups/calls). Median allocated bytes per call across trial means:

| Preservation case | Before | After | Reduction |
| --- | ---: | ---: | ---: |
| wide HTML | 5,346,300 | 5,155,717 | 3.56% |
| wide Markdown DOM | 6,020,911 | 5,816,759 | 3.39% |
| deep HTML | 1,388,496 | 1,332,286 | 4.05% |
| deep Markdown DOM | 1,393,568 | 1,336,816 | 4.07% |

Default HTML/Markdown allocation reductions range0.94–4.58%, consistent with their different inline
rendering/checking work. DOCX controls vary up to0.43% and are not attributed to the HTML change.
Descriptor payload units match. No new live-heap/JFR comparison or timing claim is made. This is a
separate change from the earlier empty-span guard; neither measurement should be combined into a
corpus-wide saving estimate. The next bounded investigation is supplementary-character allocation
in table HTML escaping, which should be isolated before extending this technique there.

Compile/run the isolated probe from the repository root after building the normal runtime classpath:

```sh
DOCWEAVE_CP=$(cat docweave-all/target/runtime-classpath.txt)
mkdir -p /tmp/docweave-inline-probe-new
javac --release 25 -cp "$DOCWEAVE_CP" -d /tmp/docweave-inline-probe-new \
  benchmarks/tools/MeasureHtmlInlineAllocation.java
java -Xms512m -Xmx512m -XX:+UseG1GC \
  -cp "/tmp/docweave-inline-probe-new:$DOCWEAVE_CP" dev.docweave.partition.MeasureHtmlInlineAllocation
```

Compile the probe onto the application classpath as shown: source-launching a separate runtime
package cannot access the hidden renderer. This development-only probe does not expand public APIs.

## Follow-up: table HTML escaping source slices — 2026-10-16

Shared HtmlTextEscaper and bounded nested-table HTML serialization now copy original UTF-16 slices
only for valid characters emitted unchanged. Existing entity mapping, invalid control/surrogate
numeric escaping, codepoint budget checks and interruption remain intact. An exact mixed regression
covers adjacent supplementary pairs and lone code units; the all-BMP/1,000seeded Unicode jsoup oracle
and full table/default/corpus/provenance checks remain green.

MeasureTableEscapeAllocation.java isolates shared escaping (compile/run like MeasureHtmlInlineAllocation,
using the class name dev.docweave.partition.MeasureTableEscapeAllocation). Source construction is
excluded. With100warmups/100calls and three fresh JVMs, all trials agree:

| Case | Before bytes/escape | After bytes/escape |
| --- | ---: | ---: |
| ASCII | 196,864 | 196,864 |
| BMP | 401,672 | 401,672 |
| Supplementary-only | 590,088 | 393,480 |
| Supplementary + entities/control/lone surrogate | 1,868,072 | 1,769,768 |

Savings are33.3% and5.3% for the last two cases on this runtime. Shorter preliminary warmup produced
mixed-case variation; the final protocol uses the same longer warmup for both builds. Output lengths
match, and separate functional tests check exact contents. This does not measure bounded serialization
in isolation, nor establish a throughput or general memory guarantee.

[Full-pipeline reports](review/table-escape-allocation/README.md) use a fresh rebuilt610b2d0 baseline,
unchanged pinned inputs/profiler/JVM flags, and three alternating before/after trials with10warmups/calls.
Median allocated bytes per call across trial means:

| Preservation case | Before | After | Reduction |
| --- | ---: | ---: | ---: |
| wide HTML | 5,151,664 | 5,111,329 | 0.78% |
| wide Markdown DOM | 5,820,455 | 5,784,670 | 0.61% |
| deep HTML | 1,331,640 | 1,309,248 | 1.68% |
| deep Markdown DOM | 1,336,712 | 1,314,400 | 1.67% |

Descriptor payload units match. Default HTML/Markdown and DOCX differences are small and within
runtime variability; no whole-pipeline DOCX gain is claimed. This step adds no timing/live-heap/JFR
measurement. Remaining DOCX allocation is dominated by model/traversal work in the earlier samples.
The bounded allocation investigation is complete for now. The next priority is broader independent
DOCX corpus/fidelity coverage beyond the current template family, with source/license/provenance and
existing default-policy comparisons preserved. Independent human rendering review remains separate.
