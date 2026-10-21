# Additional unreleased upstream XLS review

The strict opt-in Java freshness check detected new main commit
`f98602237cb152e9c33fd824fb8ec96037806ad4` after the earlier release review. Its exact source
diff was reviewed before updating the snapshot. The latest released tag remains0.27.28 at
`1b02b4d189c260ed9b1aca29583203e233d853cb`; main is one commit beyond it and seven beyond
the unchanged historical0.27.27 reference.

The [new commit](https://github.com/Unstructured-IO/unstructured/commit/f98602237cb152e9c33fd824fb8ec96037806ad4)
handles `struct.error` while probing legacy XLS encryption when a Workbook stream ends in
short padding. It adds a padded XLS fixture/test, changelog entry and unreleased version bump.
Its XLSX parser still rejects detected encrypted workbooks. No PDF, OCR, HTML or DOCX parser
code changes in this commit. XLS/XLSX are outside DocWeave's currently implemented formats.

`review.json` retains the immutable commit-response hash and complete five-file change list.
`observed-drift.json` is the original Java check's failure receipt, with response hashes and
the observed main/release/path identities. The snapshot is updated after triage; historical
fixture manifests and all seven golden datasets remain unchanged. No new Python reference
execution, output parity or PDF accuracy improvement is claimed by this source review.
