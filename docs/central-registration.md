# Maven Central registration

The project uses the `io.docweave` namespace. Its Central Portal status was recorded as
**Verified** on 2026-10-18; this records the registration result, not a fresh authenticated
Portal check. Beta releases are available on Maven Central; see the versioned
[release notes](releases/README.md) for publication status.

Public-key retrieval succeeded after the initial setup checks, and the retrieved key
verified all65 signatures in the beta.1 candidate. The earlier HTTP404 responses were
setup observations, not current publication blockers. Candidate details are retained in
[the signing review](../benchmarks/review/signed-beta/README.md).

## Public signing key

Primary fingerprint:

```text
2AB2C6EC15BFAD082E407EB4858774B14CB559E9
```

To distribute the public key:

```sh
gpg --keyserver hkps://keyserver.ubuntu.com \
    --send-keys 2AB2C6EC15BFAD082E407EB4858774B14CB559E9
```

After propagation, retrieve the key by its full fingerprint into an isolated verification
workspace, confirm the primary fingerprint, and verify a candidate signature with that
retrieved key. A successful send command alone does not establish independent retrieval.
Keep private keys, passphrases and Portal credentials outside Git and logs.
See [Sonatype public-key guidance](https://central.sonatype.org/publish/requirements/gpg/).

## Namespace registration procedure

These steps document initial setup; the existing namespace does not need to be registered again.

1. Sign in to [Central Portal](https://central.sonatype.com/).
2. Open the account menu, choose **View Namespaces**, then **Add Namespace**.
3. Enter `io.docweave`. Copy the assigned verification value from the namespace request.
4. In the DNS provider for `docweave.io`, add a TXT record at the root domain. Providers
   commonly call this host `@` or leave it blank; use the provider's root-domain convention.
   Set its value to the exact Portal-provided verification value. Preserve existing TXT
   records such as SPF and unrelated domain-verification entries.
5. Confirm the TXT value is publicly returned for `docweave.io`, for example with
   `dig +short TXT docweave.io`. Do not use `io.docweave`, `www.docweave.io` or a guessed
   `_maven`/verification subdomain as the DNS name.
6. After the record is visible, select **Verify Namespace** and confirm **Verified** in Portal.
   Public DNS presence alone does not establish Portal authorization.

The optional automatically verified `io.github.praveenpg` namespace does not verify
`io.docweave`. Maven coordinates and Java packages can differ; registration does not
require changing the existing `io.docweave` packages.
See [Sonatype namespace registration](https://central.sonatype.org/register/namespace/).

## Publishing credentials and release preparation

Store publishing tokens in a local credential store, Maven settings or CI secrets, outside
Git. Local builds and candidate preparation do not require a publishing token.

Review release scope, source attribution and the exact signed artifact set before upload.
A Central-layout ZIP, a validated deployment and a published release are distinct stages;
namespace verification does not publish artifacts. See [publishing](publishing.md) and the
[release checklist](pdf-beta-checklist.md).

Local beta.8 separately migrates Java packages to `io.docweave`; this is an API change, not a
namespace-verification requirement. See [package migration](package-migration.md).
