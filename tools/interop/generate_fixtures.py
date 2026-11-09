"""Generate PUBLIC SYNTHETIC fixtures only; never use this randomness in an app.

Existing fixtures are immutable. --check regenerates in temporary storage and
compares bytes; it never rewrites the committed corpus to make a test pass.
"""
from __future__ import annotations

import argparse
import base64
import copy
import hashlib
import importlib.metadata
import json
from pathlib import Path
import sys
import tempfile
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
CORPUS = ROOT / "app/src/test/resources/interop/v1"
PASSWORD = "Synthetic-Żółw-🔐-42!"
LIMIT = 10 * 1024 * 1024


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify_source(source: Path) -> dict:
    pin = json.loads((HERE / "desktop-pin.json").read_text(encoding="utf-8"))
    if sys.version.split()[0] != pin["python"]:
        raise SystemExit("Pinned Python runtime mismatch")
    for relative, expected in pin["source_sha256"].items():
        if sha((source / relative).read_bytes()) != expected:
            raise SystemExit(f"Pinned source mismatch: {relative}")
    for name, version in pin["dependencies"].items():
        if importlib.metadata.version(name) != version:
            raise SystemExit(f"Pinned dependency mismatch: {name}")
    return pin


def build(source: Path, output: Path) -> None:
    pin = verify_source(source)
    sys.path.insert(0, str(source / "src"))
    from desktop_2fa.vault.models import TotpEntry, VaultData
    from desktop_2fa.vault.vault import Vault
    from desktop_2fa.crypto.argon2 import derive_key
    from desktop_2fa.crypto.aesgcm import encrypt
    from desktop_2fa.totp.generator import generate

    output.mkdir(parents=True, exist_ok=True)
    files: dict[str, dict] = {}
    cases: list[dict] = []

    def store(name: str, data: bytes, provenance: str) -> None:
        (output / name).write_bytes(data)
        files[name] = {"sha256": sha(data), "bytes": len(data), "provenance": provenance}

    def outcome(case_id: str, file: str, desktop: str, watch: str, *,
                password: str = PASSWORD, round_trip: bool = False, **extra) -> None:
        cases.append(dict(id=case_id, file=file, password=password,
                          desktop_load=desktop, watch_load=watch,
                          round_trip=round_trip, **extra))

    def randomness(label: str):
        # Fixed, distinct fixture salt/nonce. This patch exists only in this tool.
        return patch("os.urandom", side_effect=lambda count:
                     hashlib.sha256(f"PUBLIC SYNTHETIC v1:{label}:{count}".encode()).digest()[:count])

    def writer(name: str, payload: dict) -> None:
        data = VaultData.model_validate(payload)
        with tempfile.TemporaryDirectory(prefix="synthetic-desktop-fixture-") as temp:
            destination = Path(temp) / name
            with randomness(name):
                Vault(data).save(destination, PASSWORD, overwrite=False)
            store(name, destination.read_bytes(), "pinned Desktop Vault.save")

    def raw_payload(name: str, raw: bytes) -> None:
        # Malformed payloads cannot be emitted by the validated Desktop writer.
        # Use its unmodified crypto primitives, with explicitly synthetic inputs.
        with randomness(name):
            salt = hashlib.sha256(("PUBLIC SYNTHETIC salt:" + name).encode()).digest()[:16]
            blob = b"D2FA\x01" + salt + encrypt(derive_key(PASSWORD, salt), raw)
        store(name, blob, "synthetic invalid payload via pinned Desktop crypto; NOT Vault.save")

    entries = []
    for algorithm, length in (("SHA1", 20), ("SHA256", 32), ("SHA512", 64)):
        key = base64.b32encode((b"1234567890" * 7)[:length]).decode("ascii")
        for digits, period in ((6, 30), (7, 45), (8, 60)):
            entries.append(dict(issuer="Syntetyczny Żółw 日本 🔐", account_name=f"synthetic-{algorithm}-{digits}",
                                secret=key, digits=digits, period=period, algorithm=algorithm))
    historical = entries[0]["secret"]
    entries.extend([
        dict(issuer=None, account_name=None, secret=historical),
        dict(issuer="synthetic duplicate", account_name="synthetic same", secret=historical),
        dict(issuer="synthetic duplicate", account_name="synthetic same", secret=historical),
        dict(issuer="synthetic formatted", account_name="synthetic lowercase", secret="gezd gnbv gy3t qojq gezd gnbv gy3t qojq"),
    ])
    payload = VaultData(entries=[TotpEntry(**entry) for entry in entries]).model_dump()
    writer("supported.d2fa", payload)
    store("supported.json", json_bytes(payload), "semantic snapshot of pinned Desktop models")
    outcome("supported", "supported.d2fa", "accept", "accept", round_trip=True)
    fixed_times = [0, 29, 30, 44, 45, 59, 60, 1_111_111_109, 20_000_000_000]
    codes = [{"timestamp": timestamp, "codes": [generate(entry["secret"], timestamp=timestamp,
              digits=entry["digits"], period=entry["period"], algorithm=entry["algorithm"]) for entry in payload["entries"]]}
             for timestamp in fixed_times]
    store("fixed-time-codes.json", json_bytes(codes), "actual pinned Desktop TOTP generator; public synthetic keys")

    for label, secret, can_generate in (("historical-empty", "", False),
                                        ("historical-unicode-fold", "ſA======", False),
                                        ("historical-trailing-bits", "MZ======", True)):
        data = {"version": 1, "entries": [dict(issuer=None, account_name=None, secret=secret)]}
        writer(label + ".d2fa", data)
        outcome(label, label + ".d2fa", "accept", "reject", desktop_generate=can_generate,
                reason="Preserve historical source; Watch does not accept this key. No lossy conversion.")

    negatives = {}
    def negative(label: str, change, desktop: str = "reject") -> None:
        data = copy.deepcopy(payload)
        change(data)
        negatives[label] = (json_bytes(data), desktop)

    negative("unknown-root", lambda p: p.update(future_metadata=True))
    negative("unknown-entry", lambda p: p["entries"][0].update(future_metadata=True))
    negative("payload-version-2", lambda p: p.update(version=2))
    negative("payload-version-string", lambda p: p.update(version="1"))
    negative("payload-version-missing", lambda p: p.pop("version"), desktop="accept")
    negative("invalid-key", lambda p: p["entries"][0].update(secret="!"))
    negative("invalid-period", lambda p: p["entries"][0].update(period=0))
    negative("unsupported-digits", lambda p: p["entries"][0].update(digits=10))
    negative("unsupported-algorithm", lambda p: p["entries"][0].update(algorithm="MD5"))
    negatives.update({"malformed-json": (b"{", "reject"), "invalid-utf8": (b"\xff", "reject")})
    for label, (raw, desktop) in negatives.items():
        raw_payload(label + ".d2fa", raw)
        outcome(label, label + ".d2fa", desktop, "reject")

    good = (output / "supported.d2fa").read_bytes()
    mutations = {"header-version-2": good[:4] + b"\x02" + good[5:],
                 "invalid-magic": b"NOPE" + good[4:], "tampered": good[:-1] + bytes([good[-1] ^ 1]),
                 "truncated-tag": good[:-8], "short-header": good[:10]}
    for label, blob in mutations.items():
        store(label + ".d2fa", blob, "deterministic byte mutation of supported.d2fa")
        outcome(label, label + ".d2fa", "reject", "reject")
    outcome("wrong-password", "supported.d2fa", "reject", "reject", password="Synthetic-Wrong-42!")

    for label, length in (("at-size-limit-invalid-auth", LIMIT), ("over-size-limit", LIMIT + 1)):
        # Deterministic materialization recipe; do not check in ten-megabyte files.
        digest = hashlib.sha256(good)
        remaining = length - len(good)
        while remaining:
            chunk = bytes(min(remaining, 65_536))
            digest.update(chunk)
            remaining -= len(chunk)
        cases.append(dict(id=label, recipe={"prefix": "supported.d2fa", "zero_pad_to": length},
                          sha256=digest.hexdigest(), bytes=length, password=PASSWORD,
                          desktop_load="reject", watch_load="reject", round_trip=False,
                          reason="At-limit case passes byte limit but fails authentication; overflow must be bounded/rejected."))

    manifest = dict(schema_version=1, synthetic_only=True, desktop_release=pin["release"],
                    desktop_commit=pin["commit"], dependencies=pin["dependencies"],
                    max_vault_bytes=LIMIT, files=files, cases=cases)
    (output / "manifest.json").write_bytes(json_bytes(manifest))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--desktop-source", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=CORPUS)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        with tempfile.TemporaryDirectory(prefix="synthetic-fixture-check-") as temp:
            actual = Path(temp)
            build(args.desktop_source.resolve(), actual)
            expected = args.output
            actual_files = {p.name: p.read_bytes() for p in actual.iterdir()}
            expected_files = {name: (expected / name).read_bytes() for name in actual_files}
            if actual_files != expected_files:
                raise SystemExit("Immutable fixture mismatch; inspect the regression, do not overwrite the corpus")
            print(f"Verified {len(actual_files)} immutable synthetic artifacts")
    else:
        if args.output.exists() and any(args.output.iterdir()):
            raise SystemExit("Output is not empty; use --check to verify immutable fixtures")
        build(args.desktop_source.resolve(), args.output)
        print("Generated public synthetic fixtures; no production randomness changed")


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        # Validation exceptions may include input; do not print key/password/code data.
        raise SystemExit(f"Synthetic fixture generation failed: {type(failure).__name__}") from None
