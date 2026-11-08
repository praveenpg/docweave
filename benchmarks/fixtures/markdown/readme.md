# Lantern Notes

[![Build](https://ci.example.org/lantern/badge.svg)](https://ci.example.org/lantern)
[![Licence](https://img.example.org/licence-apache.svg)][licence]

Lantern Notes is a *small*, **offline-first** note store for field teams. It keeps
notes on the device and synchronises them when a connection is available.

> **Status:** beta. The storage format may still change between minor versions.

## Installation

1. Download the archive for your platform from the [releases page][releases].
2. Unpack it into a directory of your choice:

   ```sh
   tar -xzf lantern-notes-1.4.0.tar.gz -C ~/apps
   ```

3. Add `~/apps/lantern/bin` to your `PATH`.

## Usage

Run `lantern init` once, then add notes:

    lantern add "Check the north gate"
    lantern list --since 7d

Supported storage back ends:

- Local files (default)
- SQLite
  - Write-ahead logging is enabled automatically.
  - Requires SQLite 3.35 or later.
- A remote server you operate

| Command | Purpose | Since |
| :------ | :------ | ----: |
| `init` | Create a notebook | 1.0 |
| `add` | Append a note | 1.0 |
| `sync` | Exchange changes with a peer | 1.2 |

## Contributing

Please read [CONTRIBUTING](CONTRIBUTING.md) before opening a pull request, and
follow the [code of conduct](https://example.org/conduct "Community code of conduct").

---

Lantern Notes is released under the [Apache License 2.0][licence].

[releases]: https://example.org/lantern/releases
[licence]: https://www.apache.org/licenses/LICENSE-2.0
