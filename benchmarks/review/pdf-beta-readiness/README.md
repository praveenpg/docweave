# Local beta-readiness inventory

This audit accompanies [the draft beta checklist](../../../docs/pdf-beta-checklist.md).
It inspected root/module POMs and three existing cached JARs at production commit227c4a0.
It is not a release build, a license approval, a Central validation result or a platform test.

repository-audit.json records explicit root metadata, declared build plugins, deployment
skip properties, current coordinates, cached JAR hashes/base class versions and the packaged
Microsoft TATR notice. The JAR notice bytes match its tracked resource. All inspected base
classes are major65. No inference follows for uninspected/transitive native artifacts.
The historical full-test totals were recounted from per-class log entries, excluding Maven
module summaries; they do not represent a new run for this documentation checkpoint.

The audit finds no project license file/root POM license declaration, URL, developer or SCM
metadata, source attachment plugin, signing plugin or Central publishing plugin. Existing
Javadoc verification uses javadoc-no-fork; it does not attach the release Javadoc JAR.
The two internal test/validation modules already set maven.deploy.skip=true.

Production sources, release version, dependencies and defaults are unchanged. The draft
makes remaining scope and publication decisions reviewable without treating them as accepted
or performing a public action. Central documentation was checked2026-10-17; authoritative
links are beside the corresponding publication tasks in the checklist.
