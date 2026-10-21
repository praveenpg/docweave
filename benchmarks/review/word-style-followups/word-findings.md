# Controlled Word findings

Automated collection is complete on Word 16.0 build 16.0.20430, Windows NT 10.0.26300.0. Independent human review remains pending. No production parser policy changed.

All ten inputs were collected twice. `word-first-observations.json` retains the first measurements; `word-observations.json` is the clean-exit repeat. The six original cases were also repeated, saved as `../word-style-cases/word-repeat-observations.json`. Cell values, source hashes and anchors were identical across each packet's two runs. The comparator verified manifest and source hashes and complete cell coverage. All40 Python tooling tests pass, including saved measurement provenance and repeat consistency.

| Controlled comparison | Word observation |
| --- | --- |
| Original versus reordered conditional numPr/outlineLvl | Identical outline levels and labels in both numbering and direct-body pairs |
| Plain direct body | All nine paragraphs expose body outline9 |
| Conditional outline without numbering | First row exposes outline4; remaining rows body9 |
| Same style plus explicit direct body outline | Same effective outlines as the preceding case |
| Shared abstract definition, separate numIds | Labels continue1. through9. |
| Distinct otherwise identical abstract definitions | First-row labels1.–3.; body starts again1.–6. |
| Explicit startOverride on body numId | First-row labels1.–3.; body starts again1.–6. |
| Direct paragraph numbering referencing shared definitions | Labels continue1. through9., as with table-style numbering |

The numbering and outline ordering probes do not support property ordering as the explanation for the initial discrepancies on this build. The numbering controls narrow the difference to shared definitions and explicit restarts, rather than table-style assignment alone. They do not establish a universal counter-sharing rule for every numbering definition, nesting level or document.

Four additional [direct-heading controls](../word-outline-controls/README.md) show direct outline0 and3 winning in both plain and conditionally styled tables. Body outline9 behaves differently from these heading values on this Word build. Do not generalize this to all direct properties or silently remove direct-format priority from the parser.

## Collector cleanup correction

An earlier invocation wrote complete observations but then raised a COM by-reference argument error during cleanup. The collector now passes SaveChanges by reference to Document.Close and Application.Quit. All20 distinct inputs were recollected using the corrected collector in three isolated PowerShell processes; every collector exited successfully, and the interactive scheduled task returned0. Measurements from the original six and ten follow-ups matched the earlier output exactly.

The Word process IDs present before the clean rerun were also present afterward, with no additional Word process left by the corrected run. Existing processes were not terminated. Both temporary scheduled tasks were removed; source documents/results remain on the VM. No firewall settings were changed remotely. The VM is no longer required for this validation round and can remain off.

## Next local work

Review evidence against source definitions and document an explicit compatibility policy before production changes. Implement focused regression cases for any selected policy, retaining diagnostics and version-specific limits. Human inspection of visible labels and outline settings is still separate from automated object-model evidence. No additional VM collection is currently planned; future features may require new independent checks.
