package dev.docweave.layout;

import dev.docweave.partition.PdfTableLayoutProvider;

/**
 * One thresholded source-pixel model object; score is a class softmax value, not extraction accuracy.
 * @param label fixed published class identifier from0 through5
 * @param score finite model class score
 * @param box clipped positive source-pixel rectangle
 */
record LayoutObject(int label, double score, PdfTableLayoutProvider.Box box) {}
