package io.docweave.layout;

import java.nio.FloatBuffer;

/**
 * Call-confined fixed-shape YOLOX input and its original-frame scale, without a borrowed raster reference.
 * @param values unnormalized RGB CHW1024x768 pixels with top-left114 padding
 * @param sourceWidth original raster width
 * @param sourceHeight original raster height
 * @param ratio nominal aspect-preserving source-to-network resize ratio
 */
record YoloXPixels(FloatBuffer values, int sourceWidth, int sourceHeight, double ratio) {}
