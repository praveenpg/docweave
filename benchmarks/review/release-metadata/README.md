# Project license and repository metadata

Apache License2.0 was adopted on2026-10-17. LICENSE is the unmodified official
Apache text. The parent POM declares that project license, repository URL and HTTPS/SSH
SCM connections. Development version/tag remain0.1.0-SNAPSHOT/HEAD. This is private
release preparation, not a published beta or a complete third-party licensing audit.

The inherited resource configuration retains src/main/resources and copies the root
LICENSE into META-INF/LICENSE. Every current JAR module is a direct child of the parent;
future nested modules must revisit that relative path. The existing Microsoft TATR MIT
notice and all tracked module resources, including ServiceLoader descriptors, are preserved
byte for byte in the built JARs. Licenses for external models/corpora remain separate.

Both actual Java21/25 full offline mvn verify runs pass899 tests, zero failures/errors/skips,
and strict Javadocs. No optional installed-engine properties are selected for these runs;
prior native-engine/model evidence remains separately scoped. Maven's model builder checks
all18 child effective models inherit the exact repository URLs and project license without
appending artifact paths. After the completed Java25 build, every module JAR's license and
tracked resources are checked; hashes are in verification.json. Test/validation artifacts
remain internal with install/deploy skip properties unchanged.

At this checkpoint, developer metadata was still pending; it was not inferred from local Git configuration. Source/Javadoc attachments, signing, Portal verification, release
version/scope and actual publishing remain open. No public action occurs in this checkpoint.
