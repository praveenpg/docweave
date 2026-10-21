# Java owned Word fixture reproduction

The test-only WordFixtureGenerator interface/hidden implementation replaces the two Python
follow-up/heading generators and their five structural tests. Eight JUnit tests verify seed
hashes/provenance, ordering-only pairs, outline controls, numbering variants, deterministic
repeated generation, source immutability, overwrite protection and malformed-control rejection.

`summary.json` records the fourteen-case default JUnit reproduction, Java version and validation
source hashes. `packets.json` records actual historical/generated ZIP hashes with seed provenance.
Every corresponding package part is compared: changed document/styles/numbering XML uses expanded
namespace names/attributes and ordered text/children (including whitespace), ignoring serializer
prefix declarations and XML declarations; all other part contents retain exact bytes. This
comparison is scoped to these owned fixtures, not a general XML canonicalization algorithm.
Generated ZIP/XML bytes can differ from the historical Python serialization. New manifests hash
actual generated ZIP bytes; old measured inputs, manifests and Word observations remain unchanged.

Default artifacts are retained under `docweave-validation/target/word-fixtures/run-*` and uploaded
by private CI. Fresh inputs create no candidate/Word observation files. No production API/parser
behavior, reference pins, independent-review state or Word parity claims change. No VM is needed.
The original standalone Java base-packet helper remains separate. The remaining Python tools
include actual Unstructured reference generation and the saved-output comparison utility.

See [follow-up commands](../word-style-followups/README.md#local-reproduction) and
[heading control commands](../word-outline-controls/README.md#java-reproduction).
