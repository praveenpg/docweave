"""Compare local OCR output with pinned Tabula CSV expectations, without saving text.

This optional development comparison is not a production dependency. CSV labels do
not provide an independently reviewed span oracle. Geometry, never text, selects
the single reference table. Unmatched tables have incomplete annotation coverage.
"""
import argparse
import csv
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re


def normalize(value):
    """Collapse ASCII whitespace only; preserve case, punctuation and Unicode."""
    return re.sub(r'[ \t\r\n\f\v]+', ' ', value).strip(' \t\r\n\f\v')


class Cells(HTMLParser):
    """Read escaped generated cell text without executing or interpreting markup."""
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.values = []
        self.current = None

    def handle_starttag(self, tag, attrs):
        if tag in ('td', 'th'):
            if self.current is not None:
                raise ValueError('Nested cell')
            self.current = []
        elif tag == 'br' and self.current is not None:
            self.current.append('\n')

    def handle_data(self, data):
        if self.current is not None:
            self.current.append(data)

    def handle_endtag(self, tag):
        if tag in ('td', 'th'):
            if self.current is None:
                raise ValueError('Unmatched cell')
            self.values.append(''.join(self.current))
            self.current = None


def rectangle(coordinates):
    """Use emitted displayed PDFUserSpace geometry without text-based adjustment."""
    if coordinates['system'] != 'PDFUserSpace':
        raise ValueError('Unexpected coordinate system')
    points = coordinates['points']
    return [min(p[0] for p in points), min(p[1] for p in points),
            max(p[0] for p in points), max(p[1] for p in points)]


def iou(a, b):
    """Return rectangular intersection over union in one displayed page frame."""
    intersection = max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(
        0, min(a[3], b[3]) - max(a[1], b[1]))
    union = (a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1]) - intersection
    return intersection / union if union > 0 else 0


def select(tables, roi):
    """Select at most one table by maximum ROI IoU, with stable index tie-breaking."""
    scores = [iou(rectangle(t['coordinates']), roi) for t in tables]
    if not scores or max(scores) < 0.5:
        return None, 0 if not scores else max(scores)
    index = max(range(len(scores)), key=lambda i: scores[i])
    return tables[index], scores[index]


def project(table):
    """Project emitted anchors into evaluation slots; never invent uncovered cells."""
    grid = table['pdf_table_cells']
    parser = Cells()
    parser.feed(table['text_as_html'])
    parser.close()
    if parser.current is not None or len(parser.values) != len(grid['cells']):
        raise ValueError('HTML/coordinate anchor mismatch')
    slots = {}
    for anchor, text in zip(grid['cells'], parser.values):
        row, column = anchor['row_index'], anchor['column_index']
        for r in range(row, row + anchor['row_span']):
            for c in range(column, column + anchor['column_span']):
                if not (0 <= r < grid['row_count'] and 0 <= c < grid['column_count']):
                    raise ValueError('Out-of-grid anchor')
                if (r, c) in slots:
                    raise ValueError('Overlapping anchors')
                slots[r, c] = normalize(text) if (r, c) == (row, column) else ''
    return grid, slots


def compare(reference, table):
    """Count all reference positions, including failures, without missing-empty credit."""
    expected_rows, expected_columns = len(reference), len(reference[0])
    slots = {}
    grid = None
    if table is not None:
        grid, slots = project(table)
    matched = nonempty_matched = empty_matched = nonempty = 0
    mismatch_positions = []
    for r, row in enumerate(reference):
        if len(row) != expected_columns:
            raise ValueError('Nonrectangular CSV expectation')
        for c, value in enumerate(row):
            expected = normalize(value)
            nonempty += bool(expected)
            exact = (r, c) in slots and slots[r, c] == expected
            matched += exact
            nonempty_matched += exact and bool(expected)
            empty_matched += exact and not expected
            if not exact:
                mismatch_positions.append([r, c])
    dimensions = bool(grid and grid['row_count'] == expected_rows
                      and grid['column_count'] == expected_columns)
    total = expected_rows * expected_columns
    return dict(reference_positions=total, nonempty_positions=nonempty,
                empty_positions=total-nonempty, matched_positions=matched,
                matched_nonempty=nonempty_matched, matched_empty=empty_matched,
                dimensions_match=dimensions, full_grid_text_match=dimensions and matched == total,
                emitted_rows=None if grid is None else grid['row_count'],
                emitted_columns=None if grid is None else grid['column_count'],
                mismatch_count=len(mismatch_positions),
                mismatch_positions_sha256=hashlib.sha256(
                    json.dumps(mismatch_positions, separators=(',', ':')).encode()).hexdigest())


def run(manifest, cache, captures, profiles=('page', 'cells')):
    """Verify pinned source/CSV identities before computing text-free diagnostics."""
    if not profiles or len(set(profiles)) != len(profiles) or any(
            not re.fullmatch(r'[a-z][a-z-]*', name) for name in profiles):
        raise ValueError('Expected unique simple profile names')
    for item in manifest['inputs']:
        data = (cache/item['filename']).read_bytes()
        blob = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        if (len(data) != item['bytes'] or hashlib.sha256(data).hexdigest() != item['sha256']
                or blob != item['git_blob_sha1']):
            raise ValueError('Pinned input mismatch: ' + item['filename'])
    cases = []
    for case in manifest['cases']:
        with (cache/case['csv']).open(encoding='utf-8', newline='') as source:
            reference = list(csv.reader(source))
        if len(reference) != case['expected_rows'] or len(reference[0]) != case['expected_columns']:
            raise ValueError('Reference dimensions changed')
        source_frame = json.loads((captures/case['id']/'source.json').read_text())
        for profile in profiles:
            path = captures/case['id']/(profile+'.json')
            result = json.loads(path.read_text())
            table, overlap = select(result['tables'], case['roi_left_top_right_bottom'])
            cases.append(dict(case=case['id'], domain=case['domain'], profile=profile,
                              source_frame=source_frame, outcome=result['outcome'], emitted_tables=len(result['tables']),
                              associated_tables=int(table is not None), association_iou=round(overlap, 6),
                              unmatched_tables=len(result['tables'])-int(table is not None),
                              page_calls=result['page_calls'], page_raster_sha256=result['page_raster_sha256'], cell_calls=result['cell_calls'], layout_calls=result['layout_calls'],
                              layout_tables=result['layout_tables'],
                              local_capture_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                              **compare(reference, table)))
    return dict(runtime=json.loads((captures/'runtime.json').read_text()), cases=cases)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('cache', type=Path)
    parser.add_argument('captures', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--profiles', nargs='+', default=['page', 'cells'])
    args = parser.parse_args()
    report = run(json.loads(args.manifest.read_text()), args.cache, args.captures, args.profiles)
    with args.output.open('x') as target:
        json.dump(report, target, indent=2)
        target.write('\n')
    for case in report['cases']:
        print(case['case'], case['profile'], case['outcome'],
              f"{case['matched_positions']}/{case['reference_positions']}",
              'dimensions_match='+str(case['dimensions_match']))


if __name__ == '__main__':
    main()
