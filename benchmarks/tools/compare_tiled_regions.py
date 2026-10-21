"""Compare fixed numeric detector-window captures with pinned cTDaR geometry labels.

Annotations are read only after inference. No input crops, model policies or text are changed.
The tile union is a diagnostic candidate pool, not a fused production output or held-out metric.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def rows(path):
    """Read a numeric TSV if a profile completed; absence is handled by its typed outcome."""
    with path.open() as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def rectangle(row):
    """Read a source-frame enclosing rectangle."""
    return tuple(float(row[key]) for key in ('left', 'top', 'right', 'bottom'))


def iou(first, second):
    """Compute axis-aligned envelope intersection/union, not polygon or text accuracy."""
    overlap = max(0, min(first[2], second[2]) - max(first[0], second[0])) * max(
        0, min(first[3], second[3]) - max(first[1], second[1]))
    area = (first[2] - first[0]) * (first[3] - first[1]) + (second[2] - second[0]) * (second[3] - second[1])
    return overlap / (area - overlap) if area > overlap else 0


def predictions(directory, frame):
    """Load complete structures and rescale original-frame output back to original JPEG pixels."""
    if (directory / 'outcome.txt').read_text().strip() != 'OK':
        return []
    anchors = rows(directory / 'anchors.tsv')
    # Raster width/height may floor the PDF extent; that clips an edge rather than resizing the JPEG.
    xscale = int(frame['original_width']) / (float(frame['pdf_width']) * float(frame['render_dpi']) / 72)
    yscale = int(frame['original_height']) / (float(frame['pdf_height']) * float(frame['render_dpi']) / 72)
    result = []
    for table in rows(directory / 'tables.tsv'):
        keys = {tuple(int(anchor[key]) for key in ('row', 'column', 'row_span', 'column_span'))
                for anchor in anchors if anchor['crop'] == table['crop'] and anchor['table'] == table['table']}
        box = rectangle(table)
        result.append({'profile': directory.name, 'crop': int(table['crop']), 'table': int(table['table']),
                       'rows': int(table['rows']), 'columns': int(table['columns']), 'anchors': keys,
                       'box': (box[0] * xscale, box[1] * yscale, box[2] * xscale, box[3] * yscale)})
    return result


def reference_tables(path):
    """Read original published cell spans/envelopes without repairing missing/duplicate labels."""
    data = path.read_bytes()
    if b'<!DOCTYPE' in data.upper() or b'<!ENTITY' in data.upper():
        raise ValueError('Unexpected XML declaration')
    result = []
    for table in ET.fromstring(data).findall('table'):
        keys = set(); row_count = column_count = 0
        for cell in table.findall('cell'):
            row = int(cell.attrib['start-row']); column = int(cell.attrib['start-col'])
            end_row = int(cell.attrib.get('end-row', row)); end_column = int(cell.attrib.get('end-col', column))
            if end_row < row or end_column < column:
                raise ValueError('Invalid published span')
            keys.add((row, column, end_row - row + 1, end_column - column + 1))
            row_count = max(row_count, end_row + 1); column_count = max(column_count, end_column + 1)
        points = [tuple(map(float, pair.split(','))) for pair in table.find('Coords').attrib['points'].split()]
        result.append({'rows': row_count, 'columns': column_count, 'anchors': keys,
                       'box': (min(p[0] for p in points), min(p[1] for p in points),
                               max(p[0] for p in points), max(p[1] for p in points))})
    return result


def compare(reference, candidates):
    """Select maximum envelope overlap only for evaluation, preserving all candidate summaries."""
    best = max(candidates, key=lambda candidate: iou(reference['box'], candidate['box']), default=None)
    if best is None or iou(reference['box'], best['box']) <= 0:
        return {'candidates': len(candidates), 'best_iou': 0, 'exact_topology': False,
                'matching_anchors': 0, 'no_positive_overlap': True}
    return {'candidates': len(candidates), 'best_iou': iou(reference['box'], best['box']),
            'profile': best['profile'], 'predicted_rows': best['rows'], 'predicted_columns': best['columns'],
            'predicted_anchors': len(best['anchors']), 'matching_anchors': len(best['anchors'] & reference['anchors']),
            'exact_topology': best['rows'] == reference['rows'] and best['columns'] == reference['columns']
                              and best['anchors'] == reference['anchors']}


def main():
    """Verify all original source pins, paired-JVM capture bytes and complete retained denominators."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture21', type=Path, required=True)
    parser.add_argument('--capture25', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists(): raise ValueError('Output must be new')
    manifest = json.loads(args.manifest.read_text())
    for entry in manifest['inputs']:
        if hashlib.sha256((args.cache / entry['filename']).read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError('Changed source/annotation: ' + entry['filename'])
    capture_files = {p.relative_to(args.capture21) for p in args.capture21.rglob('*') if p.is_file()}
    if capture_files != {p.relative_to(args.capture25) for p in args.capture25.rglob('*') if p.is_file()}:
        raise ValueError('Paired captures have different files')
    for name in capture_files:
        if (args.capture21 / name).read_bytes() != (args.capture25 / name).read_bytes():
            raise ValueError('Paired capture differs: ' + str(name))
    results = []; totals = {name: 0 for name in ('whole', 'upper', 'lower', 'tile_pool')}
    for entry in manifest['inputs']:
        if not entry['filename'].endswith('.jpg'): continue
        name = Path(entry['filename']).stem; source = args.capture21 / name
        frame = rows(source / 'source.tsv')[0]
        if frame['sha256'] != entry['sha256']: raise ValueError('Capture source mismatch')
        references = reference_tables(args.cache / (name + '.xml'))
        if (source / 'outcome.txt').exists():
            results.append({'input': name, 'outcome': (source / 'outcome.txt').read_text().strip(),
                            'reference_tables': len(references)})
            continue
        profiles = {profile: predictions(source / profile, frame) for profile in ('whole', 'upper', 'lower')}
        profiles['tile_pool'] = profiles['upper'] + profiles['lower']
        comparisons = []
        for reference in references:
            comparison = {profile: compare(reference, candidates) for profile, candidates in profiles.items()}
            for profile, record in comparison.items(): totals[profile] += int(record['exact_topology'])
            comparisons.append({'reference_rows': reference['rows'], 'reference_columns': reference['columns'],
                                'reference_anchors': len(reference['anchors']), 'profiles': comparison})
        results.append({'input': name, 'outcome': 'OK', 'frame': {key: float(value) for key, value in frame.items() if key != 'sha256'},
                        'profile_outcomes': {profile: (source / profile / 'outcome.txt').read_text().strip()
                                             for profile in ('whole', 'upper', 'lower')},
                        'candidate_tables': {profile: len(tables) for profile, tables in profiles.items()},
                        'seam_contact_detections': {profile: sum(row['artificial_edge_contact'] == 'true'
                                                   for row in rows(source / profile / 'detections.tsv'))
                                                   for profile in ('upper', 'lower')},
                        'references': comparisons})
    report = {'scope': 'Diagnostic raw ONNX structure only; no OCR/rule composition/fusion or held-out score',
              'inputs': len(results), 'paired_capture_files_identical': len(capture_files),
              'typed_whole_source_limits': sum(row['outcome'] != 'OK' for row in results),
              'exact_topologies_by_highest_envelope_overlap': totals, 'results': results}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'results'}, indent=2))


if __name__ == '__main__':
    main()
