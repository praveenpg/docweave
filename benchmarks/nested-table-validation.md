# Owned nested-table corpus and boundary validation

This suite checks stored structure and documented resource policies for HTML, Markdown EXTRACT_DOM and DOCX. It is generated from owned models, not third-party documents or independent browser/Word observations. It complements the [pinned default fixtures](review/nested-tables/README.md) and [preservation contract](../docs/nested-table-contract.md).

## Reproducible corpus

`NestedTableCorpusTest` generates 24 models using java.util.Random seeds `0xD0C000 + 0..23`. Each model has up to three child levels, one/two rows and one/two cells per nonempty table, one/two-column spans, repeated supplementary-Unicode link labels, escaped markup characters, sibling/grandchild tables and empty tables. HTML, Markdown DOM and DOCX each consume the same model: 72 extraction cases. The test failure context identifies the seed and format.

The model independently defines source preorder, parent identities, cell-origin paths, child ordinals, owned row/cell/span geometry and semantic token order. Expected hierarchy is never read back from a candidate parser result. Whitespace is normalized for cross-format semantic-token assertions because DOCX uses paragraph/grid separators and HTML uses inline/block normalization; exact default-versus-preserved element comparisons still check every ID, text, HTML, metadata and warning in each format. Parent/child intervals use Unicode code points. Repeated labels do not identify children by substring search.

Every case checks root and child link counts, strictly ordered local offsets, exact labels/targets, child interval content and independently counted prefix-token positions, flat snapshot geometry and immutable element JSON round trips. The suite includes empty children but does not synthesize entirely empty roots; existing format-specific tests cover their documented emission policies. Sources are regenerated in memory, so no ZIP-byte reproducibility or new binary hash manifest is claimed.

## Boundary cases

Across all three formats:

- Exactly256 empty siblings fit the default count policy;257 fail TABLE_LIMIT.
- A16-level child chain fits default depth;17 fails TABLE_LIMIT.
- A12-level chain fits its independently summed exact descriptor payload budget; one unit less fails TABLE_LIMIT. Payload counts string keys/values by Unicode code point, one unit per scalar/container and repeated copies, excluding the fixed root envelope.
- A16-level chain with an8,000-code-point deterministic escaped-Unicode link fragment exceeds the default1Mi payload budget. It succeeds under an explicit16Mi budget, and removing the two descriptor keys reproduces the default root exactly. The fragment uses seed0xE5CA9E and mixed random characters to keep DOCX archive protection independent of this metadata-budget test. This is an acceptance check, not a heap measurement.

`DocxNestedTableTest` additionally combines horizontal and vertical merge continuation groups, repeated child labels, a grandchild and a text-box anchor. It verifies source/owner path prefixes, root intervals, grandchild-local anchor offsets, the separately emitted text box's root anchor and exact default output/warnings. Prior tests continue to cover numbering, selected revisions, captions, references, supplemental parts, JSON/chunk provenance, interruption and concurrent document-local state.

## Run

From the repository root, after normal dependency setup:

```sh
mvn -B -ntp -Dmaven.repo.local=/tmp/docweave-m2 test \
  -Dtest=NestedTableCorpusTest,DocxNestedTableTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -ntp -Dmaven.repo.local=/tmp/docweave-m2 verify
```

The normal Maven/private CI verification runs this suite automatically. See module target/surefire-reports for failures; the integration module executes with the repository root as its working directory. The saved Word comparator remains a separate check and does not treat generated models as Word evidence.

These bounded cases do not prove arbitrary-input correctness, constant memory, streaming extraction, hard deadlines, browser layout or universal Word/upstream parity. Native models, rendered strings and snapshot copies still materialize. They found no production defect in this run. The next useful investigation is a reproducible allocation/retention profile comparing FLATTEN and PRESERVE_METADATA on shallow-wide and deep inputs before selecting any optimization. No Windows VM is required for these checks or that profile.
