"""Execute only the pinned upstream native pipeline and record every API duration; JVM code verifies/summarizes."""
import hashlib
import importlib.metadata
import io
import json
import os
from pathlib import Path
import sys
import time


def main():
    """Prepare unchanged in-memory documents, then keep every cold/warmup/measured output and duration."""
    started = time.perf_counter_ns()
    manifest_path, cache, output = map(Path, sys.argv[1:])
    manifest = json.loads(manifest_path.read_text())
    os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', UNSTRUCTURED_DISABLE_NOTICE='1')
    if importlib.metadata.version('unstructured') != '0.27.28':
        raise ValueError('Pinned runtime differs')
    import torch
    torch.set_num_threads(manifest['active_processors'])
    from unstructured.partition.pdf import partition_pdf
    inputs = []
    for case in manifest['cases']:
        data = (cache / case['pdf']).read_bytes()
        if len(data) != case['bytes'] or hashlib.sha256(data).hexdigest() != case['sha256']:
            raise ValueError('Frozen performance source differs')
        inputs.append((case, data))
    setup = time.perf_counter_ns() - started
    samples = []
    for iteration in range(1 + manifest['warmup_passes'] + manifest['measured_passes']):
        phase = 'FIRST_PASS' if iteration == 0 else 'WARMUP' if iteration <= manifest['warmup_passes'] else 'MEASURED'
        for case, data in inputs:
            with io.BytesIO(data) as source:
                begin = time.perf_counter_ns()
                elements = partition_pdf(file=source, strategy='fast', infer_table_structure=False,
                                         languages=['eng'], include_page_breaks=False)
                elapsed = time.perf_counter_ns() - begin
            text = '\n'.join(element.text for element in elements if element.category != 'Image')
            identity = hashlib.sha256(text.encode()).hexdigest()
            if identity != case['upstream_public_text_sha256']:
                raise ValueError('Measured upstream public output changed from its frozen baseline')
            samples.append({'id': case['id'], 'phase': phase, 'pass': iteration, 'elapsed_ns': elapsed,
                            'elements': len(elements), 'text_sha256': identity})
    report = {'python': sys.version, 'setup_ns': setup, 'torch_threads': torch.get_num_threads(),
              'cpu_affinity': sorted(os.sched_getaffinity(0)), 'samples': samples}
    with output.open('x') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')


if __name__ == '__main__':
    main()
