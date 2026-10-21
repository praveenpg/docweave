/**
 * Immutable semantic elements and JSON-compatible extension metadata.
 * Element order represents document reading order; metadata can carry stable hierarchy references.
 * Constructed elements own deep immutable snapshots, so callers can safely share them across threads.
 */
package dev.docweave.core;
