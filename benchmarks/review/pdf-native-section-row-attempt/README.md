# Native leading section-row investigation

A bounded attempt examined a missing label-only section row above a provider table in
physical page 6 of the complete NVIDIA source. The existing numeric-only policy deliberately
requires populated numeric cells and cannot infer such a section label.

One isolated permissive candidate accepted a single colon-terminated label with empty
remaining cells, retaining the existing source band, topology and ownership restrictions.
YOLOX exact complete-row presence rose from 67 to 68 of 110; TATR stayed 36 of 110.
Strict ordinal matching stayed zero for both profiles. All eleven physical pages, both
frozen capture populations and all three complete statement references were retained.

Colon punctuation alone is insufficient evidence of table membership. A focused safeguard
check required an aligned first body label (within two displayed units), a single source
run per first body cell, and complete numeric values in the remaining body columns.
This retained 67/110 YOLOX and 36/110 TATR: the measured gain disappeared. The candidate
was parked, and production code, defaults and public options remain unchanged. No further
threshold, grammar, model or crop search was performed.

The reports and numeric comparisons retain every reference occurrence and unmatched index.
The permissive report names the selected numeric policy; an isolated helper override also
accepted section labels. The guarded report names an experimental enum that is not shipped.
The unapplied patch archives only the guarded candidate and diagnostic option. Source PDFs,
HTML, model captures and raw source transcriptions are not redistributed.

Reproduction uses the same pinned source/captures and frozen scorer workspace as
[the numeric-row evidence](../pdf-native-leading-row/README.md). The Java comparator and
scorer are unchanged. Existing 1,301-test verification applies to the unchanged production
baseline; the six existing leading-row tests also passed during the guarded attempt.
This negative experiment is not an extraction improvement or a held-out acceptance result.

Return after other open gates: investigate whether provider/source topology can establish
section membership without relying on punctuation alone. Do not broaden the numeric policy.
