"""Execute pinned Unstructured's rendered crop helper on caller-supplied owned regions; no scoring."""
import hashlib
import importlib.metadata
import json
from pathlib import Path
import sys

from unstructured.documents.coordinates import PixelSpace
from unstructured.documents.elements import CoordinatesMetadata, ElementMetadata, Image
from unstructured.partition.pdf_image.pdf_image_utils import save_elements

root = Path(sys.argv[1])
if importlib.metadata.version('unstructured') != '0.27.28':
    raise ValueError('Runtime pin changed')
source = root / 'image-contract.pdf'
elements = [Image(text='', metadata=ElementMetadata(page_number=page,
    coordinates=CoordinatesMetadata(points=((40, 40), (40, 80), (80, 80), (80, 40)),
                                    system=PixelSpace(width=200, height=120)))) for page in (1, 2, 3)]
output = root / 'upstream-crops'
if output.exists():
    raise ValueError('Fresh output required')
save_elements(elements=elements, starting_page_number=1, element_category_to_save='Image',
              pdf_image_dpi=72, filename=str(source), output_dir_path=str(output))
(root / 'upstream.json').write_text(json.dumps({
    'unstructured_version': importlib.metadata.version('unstructured'),
    'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
    'scope': 'Direct save_elements helper with owned regions; no detector inference or detection accuracy claim',
    'crops': [{'filename': p.name, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
              for p in sorted(output.iterdir())]}, indent=2) + '\n')
