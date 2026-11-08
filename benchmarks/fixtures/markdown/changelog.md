# Changelog

All notable changes to this project are documented here. The format follows
the *Keep a Changelog* convention and the project uses semantic versioning.

## [Unreleased]

### Added

- Export notes as Markdown with `lantern export --format md`.
- Optional end-to-end encryption for synchronised notebooks
  (see [the encryption guide](docs/encryption.md)).

### Fixed

- Sync no longer duplicates notes whose titles differ only in case.
- Timestamps in the `list` output now use the device time zone.

## [1.4.0] - 2026-09-30

### Changed

- **Breaking:** the configuration file moved from `~/.lantern` to
  `~/.config/lantern/config.toml`.
- Raised the minimum SQLite version to 3.35.

### Removed

- The deprecated `lantern legacy-import` command.

## [1.3.2] - 2026-08-14

### Security

- Reject notebook archives containing absolute paths ([advisory][adv-7]).

[Unreleased]: https://example.org/lantern/compare/v1.4.0...HEAD
[1.4.0]: https://example.org/lantern/compare/v1.3.2...v1.4.0
[1.3.2]: https://example.org/lantern/compare/v1.3.1...v1.3.2
[adv-7]: https://example.org/lantern/security/advisories/7
