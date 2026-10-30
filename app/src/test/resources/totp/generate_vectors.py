"""Offline maintenance generator for immutable synthetic TOTP expected values."""

import argparse
import hashlib
import hmac
import json
from pathlib import Path


KEYS = {
    "SHA1": b"12345678901234567890",
    "SHA256": b"12345678901234567890123456789012",
    "SHA512": b"1234567890123456789012345678901234567890123456789012345678901234",
}
RFC = [
    (59, "94287082", "46119246", "90693936"),
    (1111111109, "07081804", "68084774", "25091201"),
    (1111111111, "14050471", "67062674", "99943326"),
    (1234567890, "89005924", "91819424", "93441116"),
    (2000000000, "69279037", "90698825", "38618901"),
    (20000000000, "65353130", "77737706", "47863826"),
]
TIMES = [
    (0, 30), (29, 30), (30, 30), (31, 30),
    (44, 45), (45, 45), (46, 45),
    (59, 60), (60, 60), (61, 60),
    (2**32 - 1, 1), (2**32, 1), (2**32 + 1, 1),
    (2**63 - 1, 1),
    (2**31 - 2, 2**31 - 1), (2**31 - 1, 2**31 - 1),
    (2**31, 2**31 - 1), (2**63 - 1, 2**31 - 1),
]


def expected(algorithm: str, seconds: int, period: int, digits: int) -> str:
    counter = (seconds // period).to_bytes(8, "big")
    digest = hmac.digest(KEYS[algorithm], counter, getattr(hashlib, algorithm.lower()))
    offset = digest[-1] & 15
    truncated = int.from_bytes(digest[offset:offset + 4], "big") & 0x7FFFFFFF
    return str(truncated % (10**digits)).zfill(digits)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="New output path; existing fixtures are never overwritten")
    args = parser.parse_args()
    for seconds, *codes in RFC:
        for algorithm, code in zip(KEYS, codes):
            if expected(algorithm, seconds, 30, 8) != code:
                raise RuntimeError("Oracle disagrees with a published RFC 6238 Appendix B vector")
    rows = [
        [algorithm, seconds, period, digits, expected(algorithm, seconds, period, digits)]
        for algorithm in KEYS
        for seconds, period in TIMES
        for digits in (6, 7, 8)
    ]
    # Compact one-row-per-case output makes a complete fixture reviewable in a small PR.
    with args.output.open("x", encoding="utf-8", newline="\n") as destination:
        destination.write('{\n  "version": 1,\n')
        destination.write('  "columns": ["algorithm", "time_seconds", "period", "digits", "code"],\n')
        destination.write('  "cases": [\n')
        destination.write(",\n".join("    " + json.dumps(row) for row in rows))
        destination.write("\n  ]\n}\n")


if __name__ == "__main__":
    main()
