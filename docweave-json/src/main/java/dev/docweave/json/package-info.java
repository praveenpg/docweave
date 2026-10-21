/**
 * Compatibility serialization for element arrays and base64/zlib original-element provenance.
 * The codec preserves supported wire field names and extension metadata, while omitting absent,
 * empty and debug metadata according to the documented compatibility rules.
 * Saved reads enforce independent JsonReadOptions byte/count/parser limits and return complete
 * immutable lists. Custom read policies do not govern writes or fixed provenance limits.
 * Compression bytes are not a stable identity; compare decoded provenance instead.
 */
package dev.docweave.json;
