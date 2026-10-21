# Java corpus diagnostics migration

Recorded 2026-10-16. Java inventory document fields exactly match the historical
Python inventories for both official forms and three Apache POI samples. The forms
also match all shared legacy review metrics under both default/stored-part policies.
No external binaries or extracted paragraphs were committed; inputs remain cached.
NRS historical reports were not rerun and remain unchanged.

Reports pin manifest/input/Java-source hashes and review runtime fingerprints.
The legacy reviewer can consume actual pinned upstream reference JSON; JUnit tests
read the two native DOCX reference cases directly. Synthetic schema/contract tests
are marked TEST ONLY and are not upstream execution or evidence. Python reference
generation remains responsible for executing the real upstream implementation.

Inventory includes stored revision/fallback structures and named package-part files,
not rendered content. Legacy substring checks reject nested paragraphs and do not
prove order, multiplicity, ownership or layout. Ownership-v1 remains the separate,
stricter baseline. Commands and remaining migration scope are documented in the
Java validation module README. Historical input/report bytes remain unchanged.
