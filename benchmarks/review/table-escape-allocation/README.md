# Table HTML escaping allocation comparison

Measured2026-10-16 on Corretto25.0.4+7-LTS Linux amd64 G1 -Xms512m -Xmx512m.
Baseline610b2d0 rebuilt in isolated /tmp/docweave-escape-baseline using the same JDK/Maven cache.
Candidate changes only valid unescaped-character appends in shared/bounded table HTML serializers.
User POM formatting remains excluded from commits.

Six before/after JSON reports use unchanged ProfileNestedTableMemory.java and original pinned inputs
from ../nested-table-memory/inputs, three alternating fresh-JVM trials,10warmups/10calls. Reports pin
runtime/input/tool hashes. summary.json contains median/range of trial means; descriptor payload units
match. Root/warning/child-count invariants pass within each build.

Six isolated text reports use MeasureTableEscapeAllocation.java (source hash in summary), compiled
onto the ordinary application classpath. Source construction excluded;100warmups/100calls per case,
three fresh JVMs per build. Longer warmup stabilizes the mixed escaping branch; all final trials agree.
Cases1/2/3/4: ASCII, BMP, supplementary, mixed supplementary/entities/control/unpaired-surrogate text.
Allocations before->after:196864->196864;401672->401672;590088->393480;1868072->1769768bytes/call.
Output codepoint counts match. Functional equivalence is separately guarded by exact mixed-string,
all-BMP and1000seeded Unicode escaper regressions plus full table/corpus/limit/provenance tests.

These are allocated bytes, not latency/throughput/retained/peak heap. Whole-pipeline preservation
reductions are modest for HTML/Markdown; DOCX variation is within noise and no pipeline benefit is
claimed there. No new JFR/live-heap comparison. See ../../nested-table-memory.md for method/commands.
