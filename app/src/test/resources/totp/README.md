# Synthetic TOTP expected values, version 1

All keys/codes are public synthetic data based on RFC 6238's 20/32/64-byte ASCII
test keys. The test cases use no live clock, sleep, user account or network.

`TotpRfcVectorsTest` contains all 18 eight-digit values published in
[RFC 6238 Appendix B](https://www.rfc-editor.org/rfc/rfc6238.html#appendix-B).
Its 6/7-digit expectations are the corresponding decimal suffixes, preserving
leading zeroes. The full table has 54 algorithm/time/digit combinations.

`extra-v1.json` contains 162 additional rows: SHA1/SHA256/SHA512 × 18 time/period
pairs × 6/7/8 digits. Its columns are explicit in the JSON header. Cases cover
time zero, just before/at/after 30/45/60-second boundaries, counters across 2^32,
positive periods up to Int.MAX_VALUE and timestamps up to Long.MAX_VALUE.
SHA-256 of the committed LF-encoded JSON:
`c0a74f1bdb3104c10f92e7e0373cabceb2b30431a8fc108327cf52f3856f1b50`.

These expected values were generated offline with CPython 3.13 and the independent
standard-library `hmac`/`hashlib` implementation in `generate_vectors.py`. The
generator first checks all 18 published RFC values, uses exact integer division
and an eight-byte big-endian counter, and refuses to overwrite an existing file.
To inspect a fresh candidate from the repository root:

```powershell
python app/src/test/resources/totp/generate_vectors.py extra-v1-candidate.json
```

Review the candidate and any changed provenance/hash together. Do not regenerate
expected values with the function being tested to hide a regression. Gradle tests
only read the committed JSON; Python is not required to run the JVM/CI suite.
This oracle is distinct from the full pinned Desktop-library interoperability
runner planned in #29. Countdown and unsupported-digit regressions accompany
the numeric vectors; 10-digit generation remains out of scope (#7).
