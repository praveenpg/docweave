"""Execute only the installed pinned Unstructured native path; JVM code owns all scoring."""
import hashlib
import importlib.metadata
import importlib.util
import json
from pathlib import Path
import sys


def main():
    """Verify source pins and a preinstalled NLP model, then capture every fixed source or its failure."""
    manifest_path, cache_path, output_path = map(Path, sys.argv[1:])
    manifest = json.loads(manifest_path.read_text())
    if importlib.metadata.version('unstructured') != '0.27.28':
        raise RuntimeError('Pinned Unstructured version is unavailable')
    if importlib.util.find_spec('en_core_web_sm') is None:
        raise RuntimeError('Required NLP model is not preinstalled; automatic installation is forbidden')
    output_path.mkdir()
    packages = {dist.metadata['Name']: dist.version for dist in importlib.metadata.distributions()}
    (output_path / 'runtime.json').write_text(json.dumps({'python': sys.version, 'packages': packages}, indent=2) + '\n')
    from unstructured.partition.pdf import partition_pdf
    for case in manifest['cases']:
        source = cache_path / case['pdf']
        original = next(row for row in case['inputs'] if row['filename'] == case['pdf'])
        if source.stat().st_size > 16 * 1024 * 1024 or source.stat().st_size != original['bytes']:
            raise RuntimeError('Source byte size differs')
        if hashlib.sha256(source.read_bytes()).hexdigest() != original['sha256']:
            raise RuntimeError('Source hash differs')
        try:
            elements = partition_pdf(filename=str(source), strategy='fast', infer_table_structure=False,
                                     languages=['eng'], include_page_breaks=False)
            result = {'outcome': 'SUCCESS', 'elements': [element.to_dict() for element in elements]}
        except Exception as failure:
            result = {'outcome': type(failure).__name__, 'elements': []}
        with (output_path / (case['id'] + '.json')).open('x') as stream:
            json.dump(result, stream, ensure_ascii=False)
        print(case['id'], result['outcome'], len(result['elements']), flush=True)


if __name__ == '__main__':
    main()
