import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** JDK-only check of locally built release archive contents; never signs, installs or publishes. */
public final class CheckReleaseArchives {
    /** Prevents construction of the standalone verification tool. */
    private CheckReleaseArchives() {
    }

    /**
     * Verifies every direct JAR module's source/documentation attachments and attribution resources.
     * Run after a clean reactor build with the release-artifacts profile; targets are not downloaded.
     * @param args optional repository root followed by --checksums to write local digest manifests
     * @throws Exception if a POM, archive, source or required notice is absent or inconsistent
     */
    public static void main(final String[] args) throws Exception {
        if (args.length > 2 || (args.length == 2 && !args[1].equals("--checksums"))) {
            throw new IllegalArgumentException("Expected [repository root] [--checksums]");
        }
        final var root = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        final var parent = pom(root.resolve("pom.xml"));
        final var version = child(parent, "version");
        final var modules = parent.getElementsByTagNameNS("*", "module");
        final var artifacts = new ArrayList<Path>();
        artifacts.add(root.resolve("pom.xml"));
        int checked = 0;
        int skipped = 0;
        for (int index = 0; index < modules.getLength(); index++) {
            final var module = root.resolve(modules.item(index).getTextContent().strip()).normalize();
            if (!root.equals(module.getParent())) {
                throw new IOException("Only direct child modules are supported: " + module);
            }
            final var model = pom(module.resolve("pom.xml"));
            if ("pom".equals(child(model, "packaging"))) {
                continue;
            }
            final var artifact = child(model, "artifactId");
            final var ownVersion = child(model, "version");
            final var stem = artifact + "-" + (ownVersion.isEmpty() ? version : ownVersion);
            final var properties = model.getElementsByTagNameNS("*", "maven.deploy.skip");
            final boolean internal =
                    properties.getLength() == 1 && "true".equals(properties.item(0).getTextContent().strip());
            for (final var classifier : new String[] {"", "-sources", "-javadoc"}) {
                final var archive = module.resolve("target").resolve(stem + classifier + ".jar");
                if (internal) {
                    if (!classifier.isEmpty() && Files.exists(archive)) {
                        throw new IOException("Internal module has a release attachment: " + archive);
                    }
                    continue;
                }
                verify(root, module, archive, classifier);
                artifacts.add(archive);
            }
            if (internal) {
                skipped++;
            } else {
                artifacts.add(module.resolve("pom.xml"));
                checked++;
            }
        }
        if (checked == 0) {
            throw new IOException("No library archives were checked");
        }
        if (args.length == 2) {
            checksums(root, artifacts, "SHA-256", "sha256");
            checksums(root, artifacts, "SHA-512", "sha512");
        }
        System.out.println("Verified binary/source/documentation archives for " + checked + " library modules; "
                           + skipped + " internal modules have no attachments.");
    }

    /**
     * Writes a local review manifest for validated library archives and their repository POMs.
     * These digests are not signatures or a Central upload bundle; signed deployment POMs may differ.
     * @param root repository root
     * @param artifacts validated archive and POM paths, excluding internal harnesses
     * @param algorithm JDK digest algorithm
     * @param suffix output filename extension
     * @throws Exception if an artifact cannot be read or a manifest cannot be written
     */
    private static void checksums(
            final Path root, final List<Path> artifacts, final String algorithm, final String suffix) throws Exception {
        final var lines = new ArrayList<String>();
        for (final var artifact : artifacts.stream().sorted().toList()) {
            final var digest = MessageDigest.getInstance(algorithm);
            try (final var input = Files.newInputStream(artifact)) {
                final var buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            final var relative = root.relativize(artifact).toString().replace('\\', '/');
            lines.add(HexFormat.of().formatHex(digest.digest()) + "  " + relative);
        }
        final var target = root.resolve("target");
        Files.createDirectories(target);
        Files.write(target.resolve("release-checksums." + suffix), lines);
    }

    /**
     * Checks one archive, keeping every read bounded by its trusted repository counterpart's length.
     * @param root repository root
     * @param module direct child module
     * @param archive freshly built archive
     * @param classifier binary, sources or javadoc classifier suffix
     * @throws IOException if required entries or source bytes disagree
     */
    private static void verify(final Path root, final Path module, final Path archive, final String classifier)
            throws IOException {
        final boolean docs = classifier.equals("-javadoc");
        final boolean source = classifier.equals("-sources");
        final var java = module.resolve("src/main/java");
        // The class-free convenience artifact packages a guide; it has no API for the Javadoc tool.
        final boolean guide = !Files.isDirectory(java);
        final var prefix = docs && !guide ? "resources/" : "";
        try (final var zip = new ZipFile(archive.toFile())) {
            match(zip, prefix + "META-INF/LICENSE", root.resolve("LICENSE"));
            match(zip, prefix + "META-INF/THIRD_PARTY_NOTICES.md", root.resolve("THIRD_PARTY_NOTICES.md"));
            final var metadata = module.resolve("src/main/resources/META-INF");
            if (Files.isDirectory(metadata)) {
                try (final var notices = Files.list(metadata)) {
                    for (final var notice : notices.filter(Files::isRegularFile).toList()) {
                        final var name = notice.getFileName().toString();
                        if (name.contains("LICENSE") || name.contains("NOTICE")) {
                            match(zip, prefix + "META-INF/" + name, notice);
                        }
                    }
                }
            }
            if (docs) {
                if (guide) {
                    match(zip, "META-INF/README.md", metadata.resolve("README.md"));
                } else if (zip.getEntry("index.html") == null) {
                    throw new IOException("Missing API documentation index: " + archive);
                }
            }
            if (source && Files.isDirectory(java)) {
                try (final var sources = Files.walk(java)) {
                    for (final var file : sources.filter(Files::isRegularFile)
                                    .filter(path -> path.toString().endsWith(".java"))
                                    .toList()) {
                        match(zip, java.relativize(file).toString().replace('\\', '/'), file);
                    }
                }
            }
            final var entries = zip.entries();
            while (entries.hasMoreElements()) {
                final var name = entries.nextElement().getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".onnx") || name.endsWith(".traineddata") || name.endsWith(".pdf")
                        || name.endsWith(".docx")) {
                    throw new IOException("Unexpected model/corpus payload in " + archive + ": " + name);
                }
            }
        }
    }

    /**
     * Requires exact notice/source bytes without expanding an arbitrarily oversized ZIP entry.
     * @param zip owned archive
     * @param name required entry
     * @param file trusted local source
     * @throws IOException if an entry is missing or differs
     */
    private static void match(final ZipFile zip, final String name, final Path file) throws IOException {
        final var expected = Files.readAllBytes(file);
        final var entry = zip.getEntry(name);
        if (entry == null) {
            throw new IOException("Missing " + name + " in " + zip.getName());
        }
        try (final var input = zip.getInputStream(entry)) {
            if (!Arrays.equals(expected, input.readNBytes(Math.addExact(expected.length, 1)))) {
                throw new IOException("Different " + name + " in " + zip.getName());
            }
        }
    }

    /**
     * Parses a local POM with external entities and DTD access disabled.
     * @param path trusted local POM path
     * @return namespace-aware document element
     * @throws Exception if XML cannot be read securely
     */
    private static Element pom(final Path path) throws Exception {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (final var input = Files.newInputStream(path)) {
            return factory.newDocumentBuilder().parse(input).getDocumentElement();
        }
    }

    /**
     * Reads a direct POM child without accidentally taking its parent's or dependency's coordinates.
     * @param element current POM element
     * @param name local child name
     * @return stripped text, or empty when absent
     */
    private static String child(final Element element, final String name) {
        for (var node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && name.equals(child.getLocalName())) {
                return child.getTextContent().strip();
            }
        }
        return "";
    }
}
