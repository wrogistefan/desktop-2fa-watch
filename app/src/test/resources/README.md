# Synthetic Desktop validation fixture

`desktop-v0.8.3.bin` and its matching JSON manifest record are copied unchanged
from `tests/fixtures/vaults/` in Desktop-2FA **0.10.0**, repository commit
[`7d1089d6bee0f3f82678ca8a13988c742840fba0`](https://github.com/wrogistefan/desktop-2fa/tree/7d1089d6bee0f3f82678ca8a13988c742840fba0/tests/fixtures/vaults).
The encrypted fixture was originally produced by the historical Python writer
at `cd8c3aeff9f192edf650a8b0f007a62da75224a2` (v0.8.3), with v1 KDF parameters.

All entries and the manifest password are public synthetic test data. The SHA-256
is `6153e89f0de647a22425d3a805fa8da662fcc8bc937b18340117a3178f40492f`.
Do not regenerate this binary with the Watch writer to make a failing test pass.

This single existing fixture checks that #14 validation still accepts supported
Python-written data, Unicode labels/passwords and nullable names, and preserves
fields when saved. It is not the full current Python↔Kotlin interoperability
corpus/runner planned in #28/#29. Test execution is entirely offline.
