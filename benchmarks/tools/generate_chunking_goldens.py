"""Run pinned upstream chunkers on synthetic cases; production DocWeave needs no Python."""
import argparse
import base64
import zlib
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import sys

COMMIT = "347fbbfd3e84e228017f125e5c5dbdc326ef7de2"
PINNED_SOURCE_HASHES = json.loads(Path(__file__).with_name("reference-source-hashes.json").read_text())


def element(text, kind="NarrativeText", metadata=None):
    return {"type": kind, "element_id": f"source-{text}", "text": text,
            "metadata": {} if metadata is None else metadata}


def cases():
    return [
        ("whole-elements", "basic", [element("abc"), element("def"), element("ghij")], {"max_characters": 10}),
        ("soft-limit-equality", "basic", [element("abc"), element("def"), element("g")], {"max_characters": 20, "new_after_n_chars": 3}),
        ("unicode-overlap", "basic", [element("🧵𐐀abcd")], {"max_characters": 3, "overlap": 1}),
        ("word-overlap", "basic", [element("one two three four")], {"max_characters": 8, "overlap": 3}),
        ("normalized-whitespace", "basic", [element(" a\n\tb\u00a0🧵 ")], {}),
        ("code-formatting", "basic", [element("  a\n    b", "CodeSnippet")], {}),
        ("strict-titles", "by_title", [element("A", "Title"), element("body"), element("B", "Title"), element("more")], {"combine_text_under_n_chars": 0}),
        ("combined-titles", "by_title", [element("A", "Title"), element("body"), element("B", "Title"), element("more")], {}),
        ("page-tracking", "by_title", [element("A", "Title", {"page_number": 8}), element("B", "Title", {"page_number": 9}), element("body"), element("previous", metadata={"page_number": 8})], {"multipage_sections": False, "combine_text_under_n_chars": 0}),
        ("table-isolation-overlap", "basic", [element("abcdef"), element("ghijkl"), element("table", "Table"), element("mnopqr")], {"max_characters": 10, "overlap": 2, "overlap_all": True}),
        ("text-table-splitting", "basic", [element("abcdefgh", "Table", {"parent_id": "p"})], {"max_characters": 4}),
        ("table-extension", "basic", [element("table", "Table", {"vendor": {"id": 1}, "parent_id": "p"})], {}),
        ("table-chunk-input", "basic", [element("abc", "TableChunk")], {}),
        ("original-elements", "basic", [element("A", metadata={"page_number": 1, "languages": ["eng"], "parent_id": "heading"}), element("B", metadata={"page_number": 2, "languages": ["eng", "deu"]})], {"include_orig_elements": True}),
        ("original-precision", "basic", [element("point", metadata={"coordinates": {"points": [[2.675, 1.235], [3.145, 5.555]], "system": "PointSpace", "layout_width": 100, "layout_height": 200}, "detection_class_prob": 0.123456789}), element("pixel", metadata={"coordinates": {"points": [[2.65, 1.25], [3.15, 5.55]], "system": "PixelSpace", "layout_width": 100, "layout_height": 200}})], {"include_orig_elements": True}),
        ("metadata-lists", "basic", [element("A", metadata={"link_texts": ["a"], "text_as_html": " <p>a</p> ", "vendor": {"extra": 1}}), element("B", metadata={"link_texts": ["b"], "text_as_html": " <p>b</p> "})], {}),
        ("empty-page-break", "basic", [element("", "PageBreak")], {}),
    ]


def table_cases():
    def table(text, html):
        return element(text, "Table", {"text_as_html": html})
    rows = "".join(f"<tr><td>A{i}</td><td>B{i}</td></tr>" for i in range(6))
    return [
        ("html-compact", "basic", [table("A B", "<table class='cosmetic'><tbody><tr><td>A</td><td>B</td></tr></tbody></table>")], {"max_characters": 100}),
        ("html-row-splits", "basic", [table(" ".join(f"A{i} B{i}" for i in range(6)), "<table>" + rows + "</table>")], {"max_characters": 100}),
        ("html-cell-splits", "basic", [table("abcdefgh" * 20, "<table><tr><td>" + "abcdefgh" * 20 + "</td></tr></table>")], {"max_characters": 60}),
        ("html-row-cell-boundaries", "basic", [table("A B C D E F", "<table><tr>" + "".join(f"<td>{c}</td>" for c in "ABCDEF") + "</tr></table>")], {"max_characters": 60}),
        ("html-span-group", "basic", [table("A B C " + "x" * 100, "<table><tr><td rowspan='2'>A</td><td>B</td></tr><tr><td>C</td></tr><tr><td>" + "x" * 100 + "</td></tr></table>")], {"max_characters": 120}),
        ("html-tiny-fallback", "basic", [table("abcdefgh", "<table><tr><td>abcdefgh</td></tr></table>")], {"max_characters": 4}),
        ("html-headers-disabled", "basic", [table("H " + " ".join(f"A{i} B{i}" for i in range(6)), "<table><tr><th>H</th></tr>" + rows + "</table>")], {"max_characters": 100, "repeat_table_headers": False}),
        ("html-skip", "basic", [table("raw \n text", "<table><tr><td>raw</td></tr></table>")], {"max_characters": 1, "skip_table_chunking": True}),
    ]


def option_cases():
    mixed = [element("Before."), element("Cell", "Table"), element("After.")]
    return [
        ("separator-code-double-newline", "basic", [element("aa\n\nbb\nccdd", "CodeSnippet")], {"max_characters": 6, "text_splitting_separators": ["\n\n"]}),
        ("separator-empty-list", "basic", [element("🧵abcde")], {"max_characters": 3, "text_splitting_separators": []}),
        ("separator-missing-fallback", "basic", [element("🧵abcde")], {"max_characters": 3, "text_splitting_separators": ["missing"]}),
        ("separator-space-overlap", "basic", [element("one two three four")], {"max_characters": 8, "overlap": 3, "text_splitting_separators": [" "]}),
        ("separator-visible-retained", "basic", [element("aa;bb;cc")], {"max_characters": 4, "text_splitting_separators": [";"]}),
        ("separator-regex-is-literal", "basic", [element("aa.*bb.*cc")], {"max_characters": 5, "text_splitting_separators": [".*"]}),
        ("isolation-disabled-basic", "basic", mixed, {"isolate_table": False}),
        ("isolation-disabled-provenance", "basic", mixed, {"isolate_table": False, "include_orig_elements": True}),
        ("isolation-disabled-multiple-tables", "basic", [element("First", "Table"), element("Second", "TableChunk")], {"isolate_table": False}),
        ("isolation-disabled-singleton", "basic", [element("abcdefgh", "Table")], {"isolate_table": False, "max_characters": 4}),
        ("isolation-disabled-title-combine", "by_title", [element("Cell", "Table"), element("Heading", "Title"), element("Body.")], {"isolate_table": False}),
        ("isolation-disabled-strict-title", "by_title", [element("Cell", "Table"), element("Heading", "Title"), element("Body.")], {"isolate_table": False, "combine_text_under_n_chars": 0}),
        ("isolation-disabled-page", "by_title", [element("Cell", "Table", {"page_number": 1}), element("Body.", metadata={"page_number": 2})], {"isolate_table": False, "multipage_sections": False, "combine_text_under_n_chars": 0}),
        ("isolation-mixed-html-metadata", "basic", [element("Before."), element("Cell", "Table", {"text_as_html": "<table><tr><td>Cell</td></tr></table>"})], {"isolate_table": False}),
        ("isolation-overlap-barrier", "basic", [element("abcdef"), element("ghijkl"), element("tab", "Table"), element("xyz"), element("longertext")], {"isolate_table": False, "max_characters": 12, "overlap": 2, "overlap_all": True}),
        ("isolation-combined-overlap-barrier", "by_title", [element("t", "Table"), element("abcdef"), element("x")], {"isolate_table": False, "max_characters": 30, "new_after_n_chars": 0, "combine_text_under_n_chars": 2, "overlap": 2, "overlap_all": True}),
    ]


# Reviewed Java policy expectations, not substituted reference output. Preserve both sides.
OPTION_DIFFERENCES = {
    "separator-visible-retained": ("visible-delimiter-retained", ["aa;", "bb;", "cc"]),
    "separator-regex-is-literal": ("literal-separators", ["aa.*", "bb.*", "cc"]),
    "isolation-mixed-html-metadata": ("mixed-table-metadata", ["Before.\n\nCell"]),
    "isolation-overlap-barrier": ("table-overlap-barrier", ["abcdef", "ef\n\nghijkl", "tab\n\nxyz", "longertext"]),
    "isolation-combined-overlap-barrier": ("table-overlap-barrier", ["t\n\nabcdef", "x"]),
}


def verify_source(source):
    """Reject drift in selected pinned implementation files before importing upstream code."""
    expected = PINNED_SOURCE_HASHES
    actual = {path: hashlib.sha256((source / path).read_bytes()).hexdigest() for path in expected}
    if actual != expected:
        changed = [path for path in expected if actual[path] != expected[path]]
        raise ValueError(f"Pinned upstream source hash mismatch: {changed}")
    return actual


def verify_runtime():
    """Require the supported Python minor range before reference imports/execution."""
    if not ((3, 11) <= sys.version_info[:2] <= (3, 13)):
        raise RuntimeError("Reference generation requires upstream-supported Python 3.11–3.13")


def normalize(output):
    for item in output:
        item["element_id"] = "<generated>"
        metadata = item["metadata"]
        if "table_id" in metadata:
            metadata["table_id"] = "<table>"
        if "orig_elements" in metadata:
            metadata["orig_elements"] = json.loads(zlib.decompress(base64.b64decode(metadata["orig_elements"])))
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--options-only", action="store_true", help="Compare separator and table isolation options")
    mode.add_argument("--tables-only", action="store_true", help="Generate structural-table comparisons instead of the baseline chunking cases")
    parser.add_argument("--upstream", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    source = args.upstream.resolve()
    hashes = verify_source(source)
    verify_runtime()
    os.environ["DO_NOT_TRACK"] = "1"
    sys.path.insert(0, str(source))
    from unstructured.chunking.basic import chunk_elements, _BasicChunkingOptions, _chunk_elements
    from unstructured.chunking.title import chunk_by_title, _ByTitleChunkingOptions, _chunk_by_title
    from unstructured.documents.elements import ElementMetadata, TableChunk, TYPE_TO_TEXT_ELEMENT_MAP
    element_classes = {**TYPE_TO_TEXT_ELEMENT_MAP, "TableChunk": TableChunk}

    generated = []
    selected = option_cases() if args.options_only else table_cases() if args.tables_only else cases()
    for name, strategy, inputs, supplied in selected:
        options = {"include_orig_elements": False, **supplied}
        originals = [element_classes[item["type"]](text=item["text"], element_id=item["element_id"],
                        metadata=ElementMetadata.from_dict(item["metadata"])) for item in inputs]
        function = chunk_elements if strategy == "basic" else chunk_by_title
        entrypoint = "public chunk_elements" if strategy == "basic" else "public chunk_by_title"
        if "text_splitting_separators" in options:
            # Public pinned wrappers omit this keyword; exercise its real internal option path.
            opts_class = _BasicChunkingOptions if strategy == "basic" else _ByTitleChunkingOptions
            internal = _chunk_elements if strategy == "basic" else _chunk_by_title
            chunks = internal(originals, opts_class.new(**options))
            entrypoint = "internal _BasicChunkingOptions/_chunk_elements" if strategy == "basic" else "internal _ByTitleChunkingOptions/_chunk_by_title"
        else:
            chunks = function(originals, **options)
        output = [chunk.to_dict() for chunk in chunks]
        reference_payloads = [chunk["metadata"]["orig_elements"] for chunk in output if "orig_elements" in chunk["metadata"]]
        expected = normalize(output)
        case = {"name": name, "strategy": strategy, "input": inputs, "options": options,
                "reference_original_payloads": reference_payloads, "expected": expected}
        if name in {"html-row-splits", "html-row-cell-boundaries", "html-span-group", "html-headers-disabled"}:
            case["comparison"] = "strict-html-budget"
        if args.options_only:
            case["reference_entrypoint"] = entrypoint
        if name in OPTION_DIFFERENCES:
            comparison, java_texts = OPTION_DIFFERENCES[name]
            case["comparison"] = comparison
            case["java_expected"] = [{"type": "CompositeElement", "element_id": "<generated>",
                                      "text": text, "metadata": {"is_continuation": True}
                                      if i > 0 and comparison in {"visible-delimiter-retained", "literal-separators"}
                                      else {}} for i, text in enumerate(java_texts)]
        generated.append(case)

    result = {"reference": {"upstream_commit": COMMIT, "python": platform.python_version(),
              "scope": "Element-only chunking on upstream-supported Python 3.11–3.13. No parser/OCR parity claimed.",
              "source_verification": "Selected pinned implementation hashes verified before import",
              "source_sha256": hashes,
              "dependencies": {distribution.metadata["Name"]: distribution.version for distribution in importlib.metadata.distributions()}},
              "cases": generated}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(generated)} upstream golden cases in {args.output}")


if __name__ == "__main__":
    main()
