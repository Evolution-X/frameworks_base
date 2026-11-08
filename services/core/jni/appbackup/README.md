Native codecs for Swift Backup interoperability:

* libaegis: https://github.com/jedisct1/libaegis at
  `4febd6ff915747d55f44823e697bfe2ffc37aa1b` (MIT).
  Only AEGIS-256, CPU detection, software AES and public headers are included.
  `common.c` initializes only the included AEGIS-256 implementation.
* Argon2: https://github.com/P-H-C/phc-winner-argon2 tag `20190702`
  (Apache-2.0 option of the upstream dual license).
  The portable implementation is built without worker threads; multiple Argon2
  lanes still produce the standard result.

No Swift Backup executable code or native binaries are included. The wire-format
adapter is separate from these upstream cryptographic implementations.
Line comments were removed from the vendored sources; license notices are retained.

The adapter supports Swift 5 SBA1 containers with header versions 1 and 2,
raw or zstd compression, and default guest AEGIS-256 encryption. Guest metadata
uses Swift's public certificate-derived identity. Google account keys and custom
passwords are not supported. Exports use unencrypted SBA1 payloads and guest
metadata. Swift requires a CE data entry when restoring private app data, so
DE-only exports are rejected. Permissions and Swift's additional settings are
not transferred. Each transfer is limited to 1 TiB of input and unpacked data.

Exports use POSIX extended tar headers for paths that grow beyond ustar limits
after adding Swift's package directory. Imports use ustar headers where possible
and GNU long-name records for longer paths, preserving filenames up to filesystem
limits. This requires installd's matching GNU long-name support for both private
and external data. Restore validation counts file/directory entries independently
of long-name records, including across combined external-data archives.
The app writes the Swift metadata file last and removes a new version folder if
any companion file fails to transfer or fails its ZIP checksum check.
