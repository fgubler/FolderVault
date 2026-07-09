# FVC1 encrypted-file format

FolderVault encrypts each backed-up file independently into an **FVC1** container. One file in,
one `.crypt` file out. The format is a plaintext header followed by the AES-GCM ciphertext of the
whole file.

Implemented by `infrastructure/crypto/Fvc1Cipher.kt`; header parsing/validation by
`domain/crypto/Fvc1Header.kt`.

## Layout

All multi-byte integers are big-endian (`DataOutputStream` / `DataInputStream` defaults).

| Offset | Field        | Type / size            | Notes |
|-------:|--------------|------------------------|-------|
| 0      | magic        | 4 bytes                | ASCII `FVC1` |
| 4      | version      | `uint8`                | `1` or `2` (see below) |
| 5      | kdfId        | `uint8`                | `1` = PBKDF2-HMAC-SHA256 (only value defined) |
| 6      | iterations   | `int32`                | PBKDF2 iteration count actually used (default 310 000) |
| 10     | saltSize     | `uint8`                | 8…64 |
| 11     | salt         | `saltSize` bytes       | per-backup PBKDF2 salt |
| …      | ivSize       | `uint8`                | must be 12 |
| …      | iv           | 12 bytes               | random per file, 96-bit GCM nonce |
| …      | ciphertext   | remaining bytes        | AES-256-GCM output, includes the trailing 16-byte tag |

The header is parsed with strict bounds checks: a bad magic, an unknown version/KDF, or an
out-of-range iterations/salt/iv length fails fast as `INVALID_FILE` rather than allocating a
wrong-size buffer or feeding junk into key derivation.

## Versions

- **Version 1** — original format. The header is written in the clear and is **not** bound to the
  ciphertext.
- **Version 2** (current, written by every new backup) — identical byte layout, but the exact
  header bytes are fed into AES-GCM as **additional authenticated data (AAD)** on both encrypt and
  decrypt. Tampering with any header field (magic, version, kdfId, iterations, salt, iv) now fails
  the GCM tag check instead of being silently trusted (SEC-3).

Decryption stays backward compatible: version-1 files are decrypted **without** AAD, version-2
files **with** it. The distinction is driven purely by the header's `version` byte, so old `.crypt`
files remain restorable after the upgrade.

## Crypto parameters

- **KDF:** PBKDF2-HMAC-SHA256, 310 000 iterations (the count is stored in the header and honored on
  decrypt, so it can be raised later without breaking existing files).
- **Cipher:** AES-256-GCM, 128-bit tag.
- **Key scope:** one key per backup (derived from the backup password + per-backup salt), reused
  across every file in that backup.
- **IV:** fresh 96-bit random value per file.

### Key-lifetime bound (SEC-4)

NIST SP 800-38D §8.3 caps random-IV GCM at **2³² invocations under a single key** before the
birthday-bound risk of an IV collision becomes non-negligible. With one IV per file and one key per
backup, that is ~4.3 billion files per backup — unreachable for realistic archives even counting
every re-upload, so the current design has a comfortable margin.

Any future change that encrypts **multiple segments under the same key with random IVs** (e.g.
chunked/resumable uploads with a per-chunk IV) multiplies the invocation count. Such a change must
re-check this bound and either switch to a deterministic/counter-based IV construction or rotate the
key, so the margin is not silently eroded.
