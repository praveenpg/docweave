# Owned nested-table baseline

These two inputs and baseline.json record current flattened extraction, not Word expectations or the proposed preservation schema. manifest.json pins the exact inputs and snapshot. Both files contain siblings and a grandchild, repeated labels, Unicode, a link and a child span; the HTML input additionally contains a caption.

The snapshot demonstrates retained text/link offsets but discarded child table grids and hierarchy. Default-output regression coverage is in NestedTableBaselineTest. Implemented opt-in behavior and its limits are documented in [the contract](../../../docs/nested-table-contract.md).

Reproduce into a new directory after building runtime artifacts/classpath:

```sh
DOCWEAVE_CP=$(cat docweave-all/target/runtime-classpath.txt)
java --class-path "$DOCWEAVE_CP" benchmarks/tools/ProbeNestedTables.java /tmp/docweave-nested-probes-001
```

Do not replace the pinned defaults just because a new preservation mode is added. DOCX ZIP compression bytes may differ across environments; regenerated binary equality is not claimed. New snapshots must remain explicitly distinct from independent rendering evidence.

The broader [model-generated corpus and boundary suite](../../nested-table-validation.md) is separate
from these immutable default snapshots. Neither suite constitutes independent Word rendering evidence.
