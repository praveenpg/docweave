package dev.docweave.validation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline pin consistency plus an explicit opt-in GitHub freshness check. Never repins references.
 * Online results are preserved before drift assertions; network failures fail instead of implying freshness.
 * Two independent metadata requests use bounded virtual-thread tasks; ordinary mvn verify stays offline.
 */
class UpstreamStatusTest {
    /** Official metadata endpoint; never document-controlled. */
    private static final String API = "https://api.github.com/repos/Unstructured-IO/unstructured";
    /** Local JSON codec for snapshots and response identities. */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Explicit reviewed checkpoint, separate from saved fixture files. */
    private static final Path STATUS = Path.of("benchmarks/upstream-status.json");

    /** Creates offline pin and explicit online freshness controls. */
    UpstreamStatusTest() {
    }

    /**
     * Keeps every saved fixture/golden on its explicit historical reference release.
     * @throws IOException if a tracked identity file cannot be read
     */
    @Test
    void releasedPinAndSavedReferenceMetadataAgree() throws IOException {
        final var status = JSON.readTree(Files.readString(STATUS));
        final var fixtures = JSON.readTree(Files.readString(Path.of("benchmarks/fixtures/manifest.json")));
        assertEquals(fixtures.get("upstream_commit").asString(), status.get("reference_commit").asString());
        requireReleasePins(status);
        assertTrue(status.get("reference_commit").asString().matches("[a-f0-9]{40}"));
        assertEquals(status.get("ahead_by").asInt(), status.get("changes").size());
        for (final String name : List.of("chunking-goldens.json", "table-chunking-goldens.json",
                     "chunk-options-goldens.json", "docx-native-reference.json", "text-native-reference.json",
                     "markup-native-reference.json", "xml-native-reference.json")) {
            final var reference =
                    JSON.readTree(Files.readString(Path.of("docweave-integration-tests/src/test/resources", name)));
            assertEquals(status.get("reference_commit"), reference.get("reference").get("upstream_commit"), name);
        }
        for (final var change : status.get("changes")) {
            assertFalse(change.get("classification").asString().isBlank());
        }
    }

    /**
     * Records official observations before checking each reviewed release/main identity independently.
     * @throws Exception if bounded metadata requests, provenance recording or freshness checks fail
     */
    @Test
    void explicitOnlineCheckDetectsReleaseAndMainDrift() throws Exception {
        if (!Boolean.getBoolean("docweave.upstream.check")) {
            return;
        }
        final var snapshot = JSON.readTree(Files.readString(STATUS));
        requireReleasePins(snapshot);
        try (final var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var releaseTask = executor.submit(() -> fetch(client, "/releases/latest"));
            final var mainTask = executor.submit(() -> fetch(client, "/commits/main"));
            final var release = releaseTask.get(45, TimeUnit.SECONDS);
            final var main = mainTask.get(45, TimeUnit.SECONDS);
            final String mainSha = main.json().get("sha").asString();
            final String pin = snapshot.get("reference_commit").asString();
            if (!mainSha.matches("[a-f0-9]{40}") || !pin.matches("[a-f0-9]{40}")) {
                throw new IOException("Invalid upstream commit identity");
            }
            final var comparison = fetch(client, "/compare/" + pin + "..." + mainSha);
            final String tag = release.json().get("tag_name").asString();
            if (!tag.matches("[a-zA-Z0-9._-]+")) {
                throw new IOException("Invalid upstream release tag");
            }
            final var releaseCommit = fetch(client, "/commits/" + tag);
            final var paths = new ArrayList<String>();
            comparison.json().get("files").forEach(file -> paths.add(file.get("filename").asString()));
            final var report = Map.<String, Object>of("checked_at", Instant.now().toString(), "reference_commit", pin,
                    "latest_release", tag, "release_commit", releaseCommit.json().get("sha").asString(), "main_commit",
                    mainSha, "ahead_by", comparison.json().get("ahead_by").asInt(), "changed_paths",
                    paths.stream().sorted().toList(), "responses",
                    List.of(release.provenance(), main.provenance(), comparison.provenance(),
                            releaseCommit.provenance()));
            CorpusReview.write(
                    Path.of("docweave-validation/target/upstream-status/report-" + UUID.randomUUID() + ".json"),
                    report);
            assertEquals(snapshot.get("latest_release").get("tag").asString(), tag,
                    "New upstream release: review source drift and regenerate pinned references explicitly");
            assertEquals(snapshot.get("latest_release").get("commit").asString(),
                    releaseCommit.json().get("sha").asString(), "Reviewed released tag identity changed");
            assertEquals(snapshot.get("reviewed_main").asString(), mainSha, "New upstream main commits require triage");
            assertEquals(snapshot.get("ahead_by").asInt(), comparison.json().get("ahead_by").asInt());
            assertEquals(JSON.valueToTree(paths.stream().sorted().toList()), snapshot.get("changed_paths"));
            assertTrue(comparison.json().get("files").size() < 300, "Comparison may truncate file coverage");
            assertEquals(comparison.json().get("total_commits").asInt(), comparison.json().get("commits").size(),
                    "Comparison commit list is incomplete");
        }
    }

    /**
     * A reviewed release can advance while original golden identities remain pinned; malformed/collapsed tracks fail.
     * @throws IOException if the owned identity JSON cannot be parsed
     */
    @Test
    void newerReviewedReleaseDoesNotRepinSavedReferences() throws IOException {
        final String reference = "a".repeat(40);
        final String latest = "b".repeat(40);
        final String snapshot = """
                {"reference_commit":"%s",
                 "reference_release":{"tag":"0.27.27","commit":"%s"},
                 "latest_release":{"tag":"0.27.28","commit":"%s"}}
                """.formatted(reference, reference, latest);
        requireReleasePins(JSON.readTree(snapshot));
        assertThrows(AssertionError.class,
                () -> requireReleasePins(JSON.readTree(snapshot.replaceFirst(reference, latest))));
        assertThrows(
                AssertionError.class, () -> requireReleasePins(JSON.readTree(snapshot.replace(latest, "invalid"))));
        assertThrows(AssertionError.class,
                () -> requireReleasePins(JSON.readTree(snapshot.replace("reference_release", "untracked_release"))));
    }

    /**
     * Validates distinct explicit release tracks without equating current source review with executed references.
     * @param status owned checkpoint containing the historical reference and latest reviewed release
     */
    private static void requireReleasePins(final JsonNode status) {
        assertNotNull(status.get("reference_commit"));
        for (final String track : List.of("reference_release", "latest_release")) {
            final var release = status.get(track);
            assertNotNull(release, track);
            assertNotNull(release.get("tag"), track);
            assertNotNull(release.get("commit"), track);
            assertTrue(release.get("tag").asString().matches("[a-zA-Z0-9._-]+"), track);
            assertTrue(release.get("commit").asString().matches("[a-f0-9]{40}"), track);
        }
        assertEquals(status.get("reference_commit"), status.get("reference_release").get("commit"),
                "Saved fixture reference must match its historical release, independently of current source review");
    }

    /**
     * Fetches one official bounded metadata response without accepting failed requests as current evidence.
     * @param client caller-owned bounded HTTP client
     * @param suffix fixed repository-relative endpoint selected by this checker
     * @return parsed response plus raw byte identity and HTTP provenance
     * @throws IOException if HTTP status, response parsing or transport fails
     * @throws InterruptedException if the caller cancels transport
     */
    private static Response fetch(final HttpClient client, final String suffix)
            throws IOException, InterruptedException {
        final String url = API + suffix;
        final var request = HttpRequest.newBuilder(URI.create(url))
                                    .timeout(Duration.ofSeconds(30))
                                    .header("Accept", "application/vnd.github+json")
                                    .header("X-GitHub-Api-Version", "2022-11-28")
                                    .header("User-Agent", "DocWeave-upstream-validation")
                                    .GET()
                                    .build();
        final var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Upstream metadata request failed: HTTP " + response.statusCode());
        }
        return new Response(
                JSON.readTree(response.body()), Map.of("url", url, "sha256", DocxOwnership.sha256(response.body()),
                                                        "etag", response.headers().firstValue("ETag").orElse("")));
    }

    /**
     * One official response and its immutable source identity metadata.
     * @param json parsed metadata body
     * @param provenance exact request URL, response hash and optional ETag
     */
    private record Response(JsonNode json, Map<String, String> provenance) {}
}
