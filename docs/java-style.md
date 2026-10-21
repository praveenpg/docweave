# Java style

Reference: [praveenpg/unleash-starter](https://github.com/praveenpg/unleash-starter/tree/59f86b704c136e55b3485fe2af028e6659258595), reviewed at commit59f86b7. The project adopts its recurring layout, final parameters/locals and functional constructs where they improve clarity.

The reference has no shared formatter configuration and contains some inconsistent spacing. These conventions capture its recurring layout and immutable-variable preference, with explicit choices for repeatability:

- Four spaces; same-line opening braces; braces for conditional and loop bodies; separate lines for method bodies and annotations.
- Blank lines between members and logical sections; continuation indentation of eight spaces. A120-column target is a project normalization, not a claim that every reference file obeys it. Preserve string literals even when longer.
- Imports grouped as application/third-party, then java/javax, then static imports; alphabetical order within groups. Existing wildcard imports are preserved, as the reference also uses them.
- Prefer final for parameters and local bindings that are not reassigned, including final var where inference is clear. Keep reassigned counters, accumulators and resource-state variables mutable. Record components are already immutable and cannot take final; compact constructors may normalize components. Avoid forcing lambda parameter annotations/modifiers into otherwise inferred signatures.
- Prefer streams, method references and Optional for clear ordered transformations, filtering and selection. Preserve first-match, null, ordering and exception behavior. Keep stateful parsing, merge/counter algorithms, interruption checkpoints and bounded scans explicit when a functional rewrite would obscure their contract. Do not introduce parallel streams into document-local state.
- Retain Java21-compatible records, pattern switches and virtual threads, interface-based public services/builders, hidden implementations and extensive Javadocs. The reference's Java/Spring requirements do not dictate this project's runtime/API architecture.

## Formatting

Use clang-format23.1.1 with the checked-in .clang-format, followed by the reference import grouping:

```sh
python3 benchmarks/tools/format_java.py
python3 benchmarks/tools/format_java.py --check
```

The tool covers production, tests, examples and Java source launchers, excluding build outputs. It handles layout/imports only; final modifiers, braces and functional choices remain code-review conventions. It refuses import blocks containing unexpected embedded content instead of silently dropping comments. .editorconfig supplies editor indentation/newline defaults. Clang-format is a development tool, not a runtime Maven dependency.

The initial migration expanded compact control bodies and added safe final bindings, then formatted all169 Java files. Parsed type-tree fingerprints before/after the layout pass matched; imports were subsequently regrouped. A separately reviewed functional change expresses positional-outline first-match lookup as an ordered stream, preserving null handling. Full tests/Javadocs and saved Word comparison provide behavior checks beyond formatting.
