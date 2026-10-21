# Empty HTML span allocation comparison

Owned pinned wide/deep inputs from ../nested-table-memory/inputs. Measured2026-10-16,
Corretto25.0.4+7-LTS Linux amd64, G1 -Xms512m -Xmx512m. Three fresh allocation JVMs per build,
10warmups and10calls/case, alternating before/after trials. Baseline bd1af13 was built in an
isolated /tmp worktree with the same restored Maven cache/JDK. Candidate changes only missing/empty
HTML span handling; its unrelated local POM formatting edit was excluded from source commits.

before-1/2/3.json and after-1/2/3.json retain exact calling-thread allocation observations and
runtime/input/tool hashes. summary.json reports median/range of trial means and percent reduction.
The probe checks root/warning compatibility between modes in each build and expected child counts.
Descriptor payload units match across builds. Saved inputs and probe source are unchanged.

These are synthetic allocated bytes per partition call, not throughput/latency/peak/retained heap or
portable performance guarantees. Small DOCX/default-mode differences are runtime variation, not
attributed to the HTML optimization. No new live-heap or JFR measurement is claimed in this packet.
See ../../nested-table-memory.md for commands and prior profiling limits; use new output directories.
