package dev.docweave.layout;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;

/**
 * Call-confined model inputs; buffers are internally owned and never exposed by the public service.
 * @param values normalized NCHW FLOAT buffer, positioned at zero
 * @param mask all-valid single-image INT64 mask, positioned at zero
 * @param width actual resized input width
 * @param height actual resized input height
 * @param sourceWidth original source width used for inverse-normalized box coordinates
 * @param sourceHeight original source height used for inverse-normalized box coordinates
 */
record LayoutPixels(FloatBuffer values, LongBuffer mask, int width, int height, int sourceWidth, int sourceHeight) {}
