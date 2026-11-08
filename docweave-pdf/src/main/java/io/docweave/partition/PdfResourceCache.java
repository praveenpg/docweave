package io.docweave.partition;

import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.DefaultResourceCache;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * PDFBox resource cache for every document the library opens: fonts, color spaces, forms, patterns and other
 * resources stay cached as in PDFBox's default cache, but image XObjects are never stored.
 *
 * <p>A worker's document lives for the whole partition call and is reused for many pages. PDFBox's default cache keeps
 * every image XObject it has resolved, and each image keeps its decoded raster, both through soft references that the
 * JVM clears only under memory pressure. In the #56 / docweave-studio #258 measurements (beta.14, 200-page synthetic
 * scan, 4 GiB heap) that kept about one decoded raster per page seen by each worker reachable: a heap dump after 52
 * pages attributed about 873 MiB of the 1,120 MiB live heap (78%) to the three worker caches. Without the cache entry,
 * an image object and its decoded raster become unreachable when the page that used it is done; images inside forms,
 * patterns and Type 3 glyphs resolve through the same cache and are treated the same way. An image used more than once
 * is decoded each time it is resolved.</p>
 *
 * <p>The PDF loader installs this cache only in place of a cache whose class is exactly PDFBox's
 * {@code DefaultResourceCache}. A cache of another class that an application configured through PDFBox's
 * {@code ResourceCacheFactory}, or no cache, is left unchanged; a factory that returns a plain
 * {@code DefaultResourceCache} cannot be told apart from PDFBox's default and is replaced.</p>
 */
final class PdfResourceCache extends DefaultResourceCache {
    /** Creates a cache with PDFBox's default stable-resource handling. */
    PdfResourceCache() {
    }

    /**
     * Caches non-image XObjects only.
     * @param indirect resource reference
     * @param xobject resolved XObject
     */
    @Override
    public void put(final COSObject indirect, final PDXObject xobject) {
        if (!(xobject instanceof PDImageXObject)) {
            super.put(indirect, xobject);
        }
    }
}
