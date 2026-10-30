package io.docweave.source.s3;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Documented S3 location syntax and its validation. */
class S3LocationTest {
    @Test
    void qualifiedLocationsAreDecodedAndRelativeKeysAreUsedVerbatim() {
        assertEquals(new S3Location("bucket", "reports/annual report.pdf", null),
                S3Location.parse("s3://bucket/reports/annual%20report.pdf", null, Set.of()));
        assertEquals(new S3Location("bucket", "a?b#c%d+e", "3HL4kqtJ"),
                S3Location.parse("s3://bucket/a%3Fb%23c%25d+e?versionId=3HL4kqtJ", null, Set.of()));
        assertEquals(new S3Location("defaults", "reports/annual%20report.pdf?x=y", null),
                S3Location.parse("reports/annual%20report.pdf?x=y", "defaults", Set.of()),
                "relative keys are verbatim");
        assertEquals(new S3Location("bucket", "dir/", null), S3Location.parse("S3://bucket/dir/", null, Set.of()));
    }

    @Test
    void filenameAndReferenceComeFromTheKey() {
        final var location = S3Location.parse("s3://bucket/reports/2026/annual.pdf", null, Set.of());
        assertEquals("annual.pdf", location.filename());
        assertEquals("s3://bucket/reports/2026/annual.pdf", location.reference());
        assertNull(S3Location.parse("s3://bucket/dir/", null, Set.of()).filename());
        assertFalse(location.toString().contains("annual"), "toString redacts the key");
    }

    @Test
    void invalidLocationsAreIllegalArgumentsThatDoNotEchoTheLocation() {
        for (final String invalid : List.of("s3://", "s3:///zqkey", "s3://zqbkt", "s3://zqbkt/", "s3://B@d/zqkey",
                     "s3://user:pw@zqbkt/zqkey", "s3://zqbkt:9000/zqkey", "s3://zqbkt/zqkey?acl",
                     "s3://zqbkt/zqkey?versionId=", "s3://zqbkt/zqkey?versionId=1&x=2", "s3://zqbkt/zqkey#frag",
                     "s3://zqbkt/%zz", "s3://ab/zqkey", "s3://zqbkt/a\u0000b",
                     "s3://zqbkt/"
                             + "k".repeat(1025))) {
            final var failure =
                    assertThrows(IllegalArgumentException.class, () -> S3Location.parse(invalid, "fallback", Set.of()));
            assertFalse(failure.getMessage().contains("zq"), invalid);
        }
        assertThrows(IllegalArgumentException.class,
                () -> S3Location.parse("relative/key", null, Set.of()), "relative keys need a default bucket");
        assertThrows(IllegalArgumentException.class,
                () -> S3Location.parse("s3://other/key", null, Set.of("allowed")), "bucket allow-list");
        assertEquals("allowed", S3Location.parse("s3://allowed/key", null, Set.of("allowed")).bucket());
    }
}
