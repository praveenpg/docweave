# Rechecking saved Word semantic evidence

Saved Word replay/comparison now run in Java/JUnit inside docweave-validation.
No Word process, VM, Python, source launcher or runtime-classpath file is needed.
From repository root:

```sh
mvn verify
```

To run just validation and its dependencies with a fresh output directory:

```sh
mvn -pl docweave-validation -am test -Ddocweave.word.output=/tmp/new-word-run
```

Default runs get unique directories under docweave-validation/target/word-evidence.
Any existing output path is refused, including an empty directory. JUnit fails on
property differences or invalid evidence. A partial directory without summary.json
is not a completed replay. Zero differences still means human review pending.

## Immutable inputs and output

[word-evidence-manifest.json](review/word-evidence-manifest.json) pins the exact raw
manifest, first/repeated Word observation, historical candidate and comparison bytes.
Each original packet manifest separately pins its DOCX inputs. Windows BOMs and CRLFs
are retained. Source bytes are validated/captured before output creation; first and
repeated measurements must agree. Source packets are never rewritten. Regenerated
documents must not replace measured inputs under old pins.

The run copies the catalog, supported packets and excluded controls for provenance.
Each supported packet retains original inputs/manifest/observations, archives
historical-candidate.json and saves a fresh candidate.json from current public Java
interfaces plus comparison.json. summary.json records counts, source/report hashes,
validation-source hashes, Java version and ordered actual test-runtime classpath
fingerprints (JARs or class directories). No Word expectations come from candidates.

| Coverage | Cases | Scope |
| --- | ---: | --- |
| Original semantic-style packet | 6 | Anchored outline/list-label values in controlled 3x3 tables |
| Controlled follow-up packet | 10 | Same controlled instrument |
| Direct-heading controls | 4 | Provenance checked; excluded from comparison, covered by Java policy tests |

The Java replay matches all 16 supported cases with zero differences. Observations
come from Word 16.0 build 16.0.20430; they are not independent human rendering approval.
The comparator reads table-style outline ranges, defaults absent ranges to body9,
and reads labels from covering list metadata. Offsets count code points, including
supplementary characters. Invalid/duplicate cases, grids, property types, hashes,
anchors and annotation ranges reject. Arbitrary documents/direct headings need
separate instrumentation. WholeTable numbering and broader multilevel/style-link
counter-sharing remain outside independent Word evidence. No VM is needed for replay.

## Deliberate future observations

For a separately collected file, compare it with current Java output:

```sh
mvn -pl docweave-validation -am test \
  -Ddocweave.word.packet=benchmarks/review/word-style-cases \
  -Ddocweave.word.observations=/local/new-word-observations.json \
  -Ddocweave.word.comparison=/tmp/new-word-comparison.json
```

All three properties are required together; the output must be new. Hashes,
Word version/build/platform, complete anchored cells and property types are checked.
Differences fail JUnit after saving the report; matches still require human review.
This command collects no Word evidence and changes no pinned packet.

## Private CI and migration

Private CI runs Java21 and Java25 Maven verification including this replay and strict Javadocs.
Surefire and Java word-evidence/ownership reports are retained for seven days.
The Python replay/comparator and CollectWordCandidates.java launcher are retired;
runtime-classpath generation is no longer a CI prerequisite. Remaining Python helpers
are listed in the [validation module](../docweave-validation/README.md). Actual upstream
reference generation still executes Python Unstructured.

JUnit reproduces both historical comparison reports exactly, including original
differences, and injects historical candidates for negative tests. Synthetic observations
exist only in tests, never evidence. Tests cover pending status, tampering, repeat
consistency, grids/cases, Unicode offsets, immutability, overwrite refusal and source
preservation. Neither migration nor CI starts Word or publishes artifacts.
