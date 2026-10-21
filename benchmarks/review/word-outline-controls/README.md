# Direct-heading Word controls

Status: automated collection complete; independent human review pending.

Four owned 3x3 inputs replace only the direct body-outline value in pinned plain/styled controls with outline0 or3 and rename anchors. Generator: the test-only Java WordFixtureGenerator interface/hidden implementation. Manifest pins source seed hashes, output hashes and requested direct values. Reproduction uses a new directory; overwriting is refused.

Word16.0 build16.0.20430 exposed the requested direct heading value in all nine cells of every control. Thus direct headings won over the conditional outline4, while the preceding body-outline9 control exposed conditional outline4 in its first row. This observation is specific to these inputs/build and does not approve a production policy.

word-observations.json is the clean-exit collection. Its manifest hash, four input hashes, full cell coordinates and text anchors were validated. The existing candidate comparator cannot expose non-body direct table headings, so no candidate.json or misleading candidate comparison is supplied for this packet. The manifest values describe the input, not independently approved expectations.

The collector's Close/Quit reference-argument fix was validated in the signed-in Windows desktop. All three packet collectors exited successfully. See ../word-style-followups/word-findings.md for repeat checks, cleanup and next local work. The VM can remain off; no further collection is currently planned.


## Java reproduction

From the repository root with Java 25, use a fresh directory:

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Ddocweave.fixtures.kind=outline-controls \
  -Ddocweave.fixtures.source=benchmarks/review/word-style-followups \
  -Ddocweave.fixtures.output=/tmp/word-heading-controls
```

All three properties are required together. Generation verifies source pins, requires the controlled
3x3 geometry/nine direct outlines and creates no candidate or Word observations. Regenerated XML/ZIP
bytes may differ from historical inputs; Java tests compare XML meaning and unchanged part bytes.
Saved measured inputs/observations remain immutable. No Windows VM is needed for reproduction.
