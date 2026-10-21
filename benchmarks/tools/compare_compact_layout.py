"""Validate real public compact/standard layout captures against pinned sources and prototype geometry."""
import argparse
import hashlib
import json
import math
from pathlib import Path

from compare_detector_structures import structure_metric
from compare_tiled_regions import predictions, rectangle, reference_tables, rows


def interior(box):
    """Apply documented fixed rounding/inset only to predicted rule centerlines, never annotations."""
    result = (math.ceil(box[0]) + 5, math.ceil(box[1]) + 5, math.floor(box[2]) - 5, math.floor(box[3]) - 5)
    if result[2] <= result[0] or result[3] <= result[1]: raise ValueError('Nonpositive predicted interior')
    return result


def verify_geometry(prototype, actual):
    """Require identical complete topology and parent bounds, with exactly the documented cell inset."""
    before = rows(prototype / 'tables.tsv'); after = rows(actual / 'tables.tsv')
    if len(before) != len(after): raise ValueError('Changed table count')
    old_cells = rows(prototype / 'anchors.tsv'); new_cells = rows(actual / 'anchors.tsv')
    for expected, observed in zip(before, after):
        if any(expected[key] != observed[key] for key in ('rows', 'columns')) or rectangle(expected) != rectangle(observed):
            raise ValueError('Changed parent grid geometry')
        expected_cells = [cell for cell in old_cells if cell['crop'] == expected['crop'] and cell['table'] == expected['table']]
        observed_cells = [cell for cell in new_cells if cell['crop'] == observed['crop'] and cell['table'] == observed['table']]
        def keyed(cells, transform):
            return sorted((tuple(int(cell[key]) for key in ('row', 'column', 'row_span', 'column_span')),
                           transform(rectangle(cell))) for cell in cells)
        if keyed(expected_cells, interior) != keyed(observed_cells, tuple): raise ValueError('Changed anchor/inset geometry')


def main():
    """Verify paired bytes, all original pins, baseline replay and prediction-only inset expectations."""
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('capture21', 'capture25', 'cache', 'manifest', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists(): raise FileExistsError(args.output)
    root = Path(__file__).resolve().parents[1] / 'review'
    manifest = json.loads(args.manifest.read_text())
    for entry in manifest['inputs']:
        if hashlib.sha256((args.cache / entry['filename']).read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError('Changed source/annotation')
    files = {p.relative_to(args.capture21) for p in args.capture21.rglob('*') if p.is_file()}
    if files != {p.relative_to(args.capture25) for p in args.capture25.rglob('*') if p.is_file()}:
        raise ValueError('Paired file sets differ')
    for name in files:
        if (args.capture21 / name).read_bytes() != (args.capture25 / name).read_bytes(): raise ValueError('Paired captures differ')
    results = []
    for entry in manifest['inputs']:
        if not entry['filename'].endswith('.jpg'): continue
        name = Path(entry['filename']).stem; capture = args.capture21 / name
        historical = root / 'pdf-whole-page-ruled-composition/java21' / name
        if (capture / 'source.tsv').read_bytes() != (historical / 'source.tsv').read_bytes(): raise ValueError('Changed source frame')
        frame = rows(capture / 'source.tsv')[0]
        if frame['sha256'] != entry['sha256']: raise ValueError('Source identity mismatch')
        references = reference_tables(args.cache / (name + '.xml'))
        outcome = (capture / 'outcome.txt').read_text().strip() if (capture / 'outcome.txt').exists() else 'OK'
        record = {'input': name, 'reference_tables': len(references), 'outcome': outcome, 'profiles': {}}
        for profile in ('standard', 'compact'):
            directory = capture / profile
            if outcome == 'OK':
                if (capture / 'render.sha256').read_bytes() != (historical / 'render.sha256').read_bytes(): raise ValueError('Changed raster')
                prototype = root / 'pdf-raster-rule-audit/java21' / ('baseline' if profile == 'standard' else 'compact') / name
                typed = (directory / 'outcome.txt').read_text().strip()
                if typed != (prototype / 'outcome.txt').read_text().strip(): raise ValueError('Changed prototype outcome')
                if typed == 'OK': verify_geometry(prototype, directory)
                tables = predictions(directory, frame)
            else:
                typed = outcome; tables = []
                if (historical / 'outcome.txt').read_text().strip() != outcome: raise ValueError('Changed source limit')
            record['profiles'][profile] = {'outcome': typed, **structure_metric(references, tables)}
        results.append(record)
    report = {'scope': 'Actual public rule-only interface strategies; no model/OCR/held-out accuracy claim',
              'paired_capture_files_identical': len(files), 'inputs': len(results),
              'reference_tables': sum(row['reference_tables'] for row in results),
              'totals': {profile: {key: sum(row['profiles'][profile][key] for row in results) for key in
                        ('candidate_tables', 'matched_reference_tables_at_iou_0_5', 'exact_topologies_one_to_one_at_iou_0_5')}
                        for profile in ('standard', 'compact')}, 'results': results}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'results'}, indent=2))


if __name__ == '__main__':
    main()
