# Continuous integration

Library verification runs on Jenkins using Java 21. Ordinary commits and pull requests do not use GitHub Actions runners.

## Library verification job

`docweave-library-ci` accepts `GIT_REF`, a committed branch, commit or fetched pull-request ref. It defaults to `dev`. Run the job for the exact pull-request head before merging, and retain its build URL with the review. Source polling follows the ref last built; after a feature-branch run, run the default branch to restore development-branch polling. Automatic pull-request discovery and GitHub commit-status reporting are not provided by this job.

`FULL_VERIFY=true` is the default and required for merge verification. It runs:

- The complete Java 21 Maven reactor, tests and strict Javadocs, including saved Word evidence checks.
- Required S3Mock scenarios; its separate server uses Java 25 while library compilation and tests stay on Java 21.
- An unsigned release-profile rehearsal with signing explicitly skipped.
- Release archive contents, licenses and SHA-256/SHA-512 checksums.
- Benchmark tooling unit tests.
- Standalone consumers of optional adapters against the built reactor artifacts.

Jenkins retains JUnit reports, built archives, checksums, saved comparison evidence and consumer reports. This job does not sign, publish or deploy artifacts.

## Optional checks

- `VERIFY_JAVA25=true`: additional compatibility verification on Java 25; compilation remains Java 21 compatible.
- `CHECK_UPSTREAM=true`: official upstream freshness checks. This is opt-in because it requires network access.
- `FULL_VERIFY=false`: partial core-only verification, unsuitable as full merge evidence. It cannot be combined with either optional check.

Release publication and internal snapshot publication remain separate jobs and decisions. Removing the duplicate Verify workflow does not disable GitGuardian security checks.
