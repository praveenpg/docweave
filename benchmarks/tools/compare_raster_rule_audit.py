"""Compare paired source-pinned raster-rule audit captures against evaluation-only cTDaR labels."""
import argparse
import hashlib
import json
from pathlib import Path

from audit_raster_rules import PROFILES
from compare_detector_structures import structure_metric
from compare_tiled_regions import predictions, reference_tables, rows


def sha(path):
    """Fingerprint explicit local files without retaining machine paths."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def evaluate(args):
    """Verify both JVM captures, source pins and common source/raster identities before scoring."""
    if args.output.exists(): raise FileExistsError(args.output)
    manifest = json.loads(args.manifest.read_text())
    for entry in manifest['inputs']:
        if sha(args.cache / entry['filename']) != entry['sha256']: raise ValueError('Changed source/annotation')
    identity = json.loads((args.java21 / 'profiles.json').read_text())
    if identity != json.loads((args.java25 / 'profiles.json').read_text()): raise ValueError('Changed prototype identities')
    file_hashes = {}
    for profile in PROFILES:
        left = args.java21 / profile; right = args.java25 / profile
        files = {path.relative_to(left / 'capture') for path in (left / 'capture').rglob('*') if path.is_file()}
        if files != {path.relative_to(right / 'capture') for path in (right / 'capture').rglob('*') if path.is_file()}:
            raise ValueError('Different paired capture file sets')
        for name in files:
            if sha(left / 'capture' / name) != sha(right / 'capture' / name): raise ValueError('Different paired bytes')
            file_hashes[profile + '/capture/' + str(name)] = sha(left / 'capture' / name)
        if (left / 'capture/controls.tsv').read_bytes() != (right / 'capture/controls.tsv').read_bytes():
            raise ValueError('Different authored control results')
    results = []
    for entry in manifest['inputs']:
        if not entry['filename'].endswith('.jpg'): continue
        name = Path(entry['filename']).stem
        baseline = args.java21 / 'baseline/capture' / name
        frame = rows(baseline / 'source.tsv')[0]
        original = Path(__file__).resolve().parents[1] / 'review/pdf-alternate-detectors/java21' / name
        if (baseline / 'source.tsv').read_bytes() != (original / 'source.tsv').read_bytes():
            raise ValueError('Changed archived source frame')
        if (baseline / 'render.sha256').exists():
            if (baseline / 'render.sha256').read_bytes() != (original / 'render.sha256').read_bytes():
                raise ValueError('Changed archived raster identity')
        if frame['sha256'] != entry['sha256']: raise ValueError('Changed captured source')
        references = reference_tables(args.cache / (name + '.xml'))
        record = {'input': name, 'reference_tables': len(references),
                  'domain': 'modern' if name in {'cTDaR_s101', 'cTDaR_s102', 'cTDaR_s103', 'cTDaR_s104'} else 'historical',
                  'profiles': {}}
        for profile in PROFILES:
            capture = args.java21 / profile / 'capture' / name
            if (capture / 'source.tsv').read_bytes() != (baseline / 'source.tsv').read_bytes():
                raise ValueError('Different source frame between profiles')
            if (capture / 'render.sha256').exists():
                if (capture / 'render.sha256').read_bytes() != (baseline / 'render.sha256').read_bytes():
                    raise ValueError('Different raster between profiles')
            outcome = (capture / 'outcome.txt').read_text().strip()
            metric = structure_metric(references, predictions(capture, frame))
            record['profiles'][profile] = {'outcome': outcome, **metric,
                    'stages': rows(capture / 'stages.tsv')[0] if (capture / 'stages.tsv').exists() else None}
        results.append(record)
    totals = {}
    for domain in ('all', 'modern', 'historical'):
        selected = [row for row in results if domain == 'all' or row['domain'] == domain]
        totals[domain] = {'inputs': len(selected), 'reference_tables': sum(row['reference_tables'] for row in selected)}
        for profile in PROFILES:
            totals[domain][profile] = {key: sum(row['profiles'][profile][key] for row in selected) for key in
                    ('candidate_tables', 'matched_reference_tables_at_iou_0_5', 'exact_topologies_one_to_one_at_iou_0_5')}
            totals[domain][profile]['outcomes'] = {outcome: sum(row['profiles'][profile]['outcome'] == outcome for row in selected)
                    for outcome in ('OK', 'OCR_LIMIT', 'TABLE_LIMIT')}
    report = {'scope': 'Fixed150DPI raw rule prototypes only; no OCR/model composition/held-out accuracy claim',
              'association': 'One-to-one maximum-cardinality axis-aligned envelope IoU>=0.5, geometry only',
              'prototype_identities': identity, 'paired_files_identical_per_jvm': len(file_hashes),
              'totals': totals, 'results': results}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(totals, indent=2))


def main():
    """Require explicit fresh output, paired local audit roots and original source pins."""
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('java21', 'java25', 'manifest', 'cache', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    evaluate(parser.parse_args())


if __name__ == '__main__':
    main()
