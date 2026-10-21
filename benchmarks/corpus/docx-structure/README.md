# Stored DOCX structure inventory

This inventory is a prerequisite for expanding fidelity checks. It reuses three
hash-pinned Apache POI samples from the conditional-style corpus. Provenance and
repository licence/notice links are retained in manifest.json; original authorship
has not been independently verified. Source binaries and extracted text remain in
a fetch-only local cache. Independent human review is pending.

On 2026-10-16 these inputs contained respectively 2/1/6 body tables and 18/5/60
paragraphs. None contained nested tables or nested paragraphs. These samples add
an external repository source but do not close the real-world template-diversity
gap. The two existing NRS report downloads returned HTTP 403 during this attempt;
their historical reviews were left intact.

Reproduce from repository root after fetching the exact manifest URLs:

```sh
mvn -pl docweave-validation -am test \
  -Ddocweave.inventory.manifest=benchmarks/corpus/docx-structure/manifest.json \
  -Ddocweave.inventory.cache=/local/cache \
  -Ddocweave.inventory.report=/tmp/new-docx-inventory.json
```

The output path must not exist. Hash and optional byte-size checks precede parsing.
Reports contain counts and hashes, never extracted text. Stored XML counts include
revision branches and alternate/fallback content; they are not rendered counts.
They cover word/document.xml and named stored-part files, not relationship
reachability or a complete package validation. The existing substring diagnostic
now rejects nested paragraphs, whose overlapping ownership needs a separate oracle.
It still does not prove order, multiplicity, categories or rendering fidelity.

Next: acquire structurally diverse, independently sourced real-world documents
with clear provenance, then add ownership-aware checks before assessing text boxes.
No Windows VM is required for this inventory.

The inventory is now Java/JUnit; the Python script is retired. The saved Python
inventory remains unchanged. Java field-for-field parity was verified on these
three cached samples. The ownership oracle is available separately in the Java
validation module.
