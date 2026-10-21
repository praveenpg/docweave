"""Build isolated, source-pinned raster-rule prototypes and run the annotation-free audit.

Production source/classes are untouched. Each process places one explicitly compiled temporary
variant before the existing classpath. This is experimental tooling, never a production adapter.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

SOURCE_SHA = '04475b2ef5b7803a89b7db097e31e6e3de200e623b7bb1a5155b1c3835b83825'
SOURCE_FILE = Path(__file__).resolve().parents[1] / 'review/pdf-raster-rule-audit/baseline-source.java.txt'
DARK = 'return ((rgb >>> 16) & 255) <= 80 && ((rgb >>> 8) & 255) <= 80 && (rgb & 255) <= 80;'
CHROMATIC = '''final int red = (rgb >>> 16) & 255;
        final int green = (rgb >>> 8) & 255;
        final int blue = rgb & 255;
        final int low = Math.min(red, Math.min(green, blue));
        final int high = Math.max(red, Math.max(green, blue));
        return high <= 80 || (low <= 160 && high <= 224 && high - low >= 32);'''
PROFILES = {'baseline': (64, 32, 24), 'chromatic': (64, 32, 24), 'chromatic_compact': (32, 16, 12), 'compact': (32, 16, 12)}


def prototype(source, profile):
    """Apply fixed semantic edits only to the exact previously reviewed production source bytes."""
    if hashlib.sha256(source.encode()).hexdigest() != SOURCE_SHA: raise ValueError('Production source changed; review before re-pinning')
    if profile not in PROFILES: raise ValueError('Unknown prototype')
    source = source.replace('private static List<PdfPageGraphics.Segment> trimCaps(', 'static List<PdfPageGraphics.Segment> trimCaps(')
    source = source.replace('private static boolean hasRuleRemnants(', 'static boolean hasRuleRemnants(')
    if profile in ('chromatic', 'chromatic_compact'): source = source.replace(DARK, CHROMATIC)
    if profile in ('compact', 'chromatic_compact'):
        source = source.replace('private static final int MIN_RUN = 64;', 'private static final int MIN_RUN = 32;')
    if profile in ('compact', 'chromatic_compact'):
        source = source.replace('>= 32;', '>= 16;').replace('>= 24;', '>= 12;')
    return source


def main():
    """Require explicit local runtimes/classpath/cache; compile and run each profile sequentially."""
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('jdk', 'classpath', 'captures', 'pins', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    source = SOURCE_FILE.read_text()
    classpath = args.classpath.read_text().strip()
    args.output.mkdir()
    identities = {}
    for profile, (_, column, row) in PROFILES.items():
        directory = args.output / profile; directory.mkdir()
        variant = directory / 'RasterPdfTableDetector.java'; variant.write_text(prototype(source, profile))
        classes = directory / 'classes'
        subprocess.run([str(args.jdk / 'bin/javac'), '--release', '21', '-cp', classpath, '-d', str(classes),
                        str(variant), str(root / 'benchmarks/tools/CaptureRasterRuleAudit.java')], check=True)
        subprocess.run([str(args.jdk / 'bin/javadoc'), '-quiet', '-Xdoclint:all', '-Werror', '-classpath',
                        str(classes) + ':' + classpath, '-d', str(directory / 'docs'),
                        str(root / 'benchmarks/tools/CaptureRasterRuleAudit.java')], check=True)
        with (directory / 'run.log').open('w') as log:
            subprocess.run([str(args.jdk / 'bin/java'), '-Djava.awt.headless=true', '-cp', str(classes) + ':' + classpath,
                            'dev.docweave.partition.CaptureRasterRuleAudit', str(args.captures), str(args.pins),
                            str(directory / 'capture'), str(column), str(row), profile], check=True, stdout=log, stderr=subprocess.STDOUT)
        identities[profile] = {'source_sha256': hashlib.sha256(variant.read_bytes()).hexdigest(),
                               'minimum_run': PROFILES[profile][0], 'minimum_column': column, 'minimum_row': row}
    (args.output / 'profiles.json').write_text(json.dumps({'baseline_source_sha256': SOURCE_SHA, 'profiles': identities}, indent=2) + '\n')


if __name__ == '__main__':
    main()
