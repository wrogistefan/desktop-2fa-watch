# Public synthetic interoperability corpus v1 (#28 / D1)

Every password/key/account/code in this directory is deliberately public test
material. Never use these accounts, deterministic salts/nonces or this generator
for actual authentication. These are JVM test resources, not app vault data.

Reference: [Desktop-2FA v0.10.0](https://github.com/wrogistefan/desktop-2fa/releases/tag/v0.10.0),
commit [`7d1089d6bee0f3f82678ca8a13988c742840fba0`](https://github.com/wrogistefan/desktop-2fa/tree/7d1089d6bee0f3f82678ca8a13988c742840fba0).
`tools/interop/desktop-pin.json` pins 15 consumed source/configuration files by
SHA-256, Python 3.13.11 and ten direct/transitive dependency versions. The
generator verifies source/dependency pins before importing Desktop. No live
Desktop installation, GUI or user vault is read.

## Artifacts and outcomes

`v1/manifest.json` lists 22 hashed artifacts and 23 load scenarios, including two
large-file recipes. The manifest itself is versioned by Git rather than listing
its own recursive hash. `.gitattributes` preserves exact fixture bytes on Windows.

- `supported.d2fa` is written by the actual pinned `Vault.save`, not a replacement
  Python implementation. Its semantic `supported.json` contains 13 ordered records:
  every SHA1/SHA256/SHA512 × 6/7/8-digit combination, 30/45/60-second periods,
  Unicode labels/password, nullable names, historical identical name/secret
  duplicates and an ASCII-spaced lowercase key. Expected round-trip acceptance
  preserves every field, order and original key text.
- `fixed-time-codes.json` comes from the actual pinned Desktop generator at nine
  fixed timestamps, including period boundaries and a large counter. Codes are
  public synthetic expectations, never runtime diagnostics.
- Historical empty and Unicode-folded keys are retained by Desktop for review
  but cannot generate there; Watch rejects them without rewriting the source.
  Desktop also accepts/generates a key with non-canonical trailing bits which
  Watch rejects. These are explicit unsupported conversions, not normalization
  targets. The pinned Desktop writer produced these three historical files.
- Unknown fields, invalid JSON/UTF-8, unsupported/mistyped versions and invalid
  TOTP data use deliberately malformed plaintext encrypted with the pinned
  Desktop crypto primitives. They are **not** claimed to be output of its
  validating writer. Missing payload version is accepted by Desktop's default
  but rejected by Watch's explicit-version policy.
- Header/magic changes, ciphertext tampering and truncation are deterministic
  byte mutations. Wrong password references the unchanged supported file.
- Two deterministic recipes append zero bytes to supported data up to exactly
  10 MiB and 10 MiB + 1. The former is within the byte limit but fails
  authentication; it is not evidence of a valid maximum-size round trip. The
  latter must be rejected by bounded reads. Recipe hashes are checked in chunks;
  large files are not committed.

The manifest states Desktop/Watch acceptance and round-trip eligibility.
These expectations distinguish deliberate incompatibilities from supported
data. Executing both language loaders and round trips is the separate D2 #29
task; D1 generation/hash verification alone does not establish those results.

## Reproduce and verify

Offline inventory/hash/completeness checks need only the Python standard library:

```powershell
python tools/interop/verify_fixtures.py
```

To reproduce bytes, fetch the immutable Desktop source into a development-only
directory and keep LF source bytes (the generator checks their hashes):

```powershell
git -c core.autocrlf=false clone --no-checkout https://github.com/wrogistefan/desktop-2fa.git build/desktop-source
git -C build/desktop-source -c core.autocrlf=false checkout --detach 7d1089d6bee0f3f82678ca8a13988c742840fba0
python -m venv build/interop-venv
.\build\interop-venv\Scripts\python.exe -m pip install -r tools/interop/requirements.txt
.\build\interop-venv\Scripts\python.exe tools/interop/generate_fixtures.py --desktop-source build/desktop-source --check
```

Use Python 3.13.11 for the venv; source and all dependency versions are checked.
Fixture randomness is patched only inside this test generator around the
unmodified pinned writer/crypto. Distinct deterministic salt/nonce inputs are
derived from each public fixture label; Argon2id costs stay unchanged.

`--check` writes to a temporary directory and compares exact bytes. Default
generation refuses a non-empty destination. Never regenerate committed fixtures
with a changed writer to hide a regression. A deliberate new corpus requires a
new reviewed version/provenance and separately retained old expectations.

## Results and limits

On 2026-11-09, Python 3.13.11 with the pinned ten dependencies generated the
corpus using the hash-verified Desktop source. A second independent temporary
generation matched **23 artifacts including the manifest byte for byte**.
`verify_fixtures.py` verified 22 hashes, all 23 cases and both bounded-size recipes.
No Kotlin runtime change, Gradle/device/round-trip run or security audit is
claimed for D1. Original historical fixtures elsewhere remain unchanged.
