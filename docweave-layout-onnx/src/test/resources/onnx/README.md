These tiny ONNX opset17/IR9 graphs are project-owned synthetic fixtures, not trained weights.
New YOLOX fixtures are generated in Java by YoloXTableLayoutTest, without Python or
new protobuf dependencies. They contain a fixed FLOAT16128x16 initializer and an
Identity node, with independently selected source-network boxes and competing
non-table classes. Minimal protobuf fields follow the public ONNX1.19.1 schema:
https://raw.githubusercontent.com/onnx/onnx/v1.19.1/onnx/onnx.proto
They validate native session/crop/decoder plumbing, not learned-model accuracy.
`fixed-table.onnx` declares FLOAT pixel_values[1,3,h,w], INT64 pixel_mask[1,h,w],
constant FLOAT logits[1,7,7] and pred_boxes[1,7,4]. Winner logits20/others-20,
labels0,2,2,2,1,1,5; rectangles in a300x180 frame:
(20,10,180,80), (20,10,180,30), (20,30,180,55), (20,55,180,80),
(20,10,100,80), (100,10,180,80), (20,10,180,30).
Boxes are normalized cx,cy,width,height. This represents a3x2 table with a first-row colspan2.
`finite-loop.onnx` adds a finite one-billion-iteration ONNX Loop with FLOAT scalar state+1,
BOOL condition passthrough; its final state multiplied by zero adds to constant logits.
It exercises native cooperative cancellation, not inference quality or speed.
Generated with ONNX1.19.1 helper.make_model/make_graph/make_tensor/make_node and checker.check_model.

Independent preprocessing vectors in TableImageTransformTest use Pillow12.3.0 RGB Image.resize
with Resampling.BILINEAR, max-width/height800 floor dimensions and source pixel formula
R=(37*x+11*y)&255, G=(13*x+43*y)&255, B=(71*x+5*y)&255.
Expected hashes cover interleaved row-major RGB bytes before ImageNet normalization.
No Python runtime or model download is required by these Java tests.

`fixed-detections.onnx` is an owned three-label detector with five constant queries
in a600x400 frame. Labels0,0,0,1,2; boxes(0,0,300,180), (1,1,301,181),
(300,0,600,180), (0,200,100,350), (0,0,600,400), normalized cx/cy/width/height.
Winner logits20/others-20. This provides two ordinary tables, one near-duplicate,
one unsupported rotated table and one background query. Same opset17/IR9/inputs.
It tests native multi-stage plumbing and exact crop-origin ownership, not learned detection.
