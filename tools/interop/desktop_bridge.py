"""Pinned Desktop v1 round trips, migrated Watch v2 rejection and retained v1 reading."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

from generate_fixtures import verify_source

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "app/src/test/resources/interop/v1"


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65_536), b""):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--desktop-source", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    args = parser.parse_args()
    source = args.desktop_source.resolve()
    pin = verify_source(source)
    work = args.work_dir.resolve()
    if not work.is_dir() or not work.name.startswith("synthetic-interop-"):
        raise ValueError("Expected disposable synthetic work directory")
    sys.path.insert(0, str(source / "src"))
    from desktop_2fa.vault.vault import Vault, InvalidPassword, CorruptedVault, UnsupportedFormat
    from desktop_2fa.totp.generator import generate

    manifest = json.loads((CORPUS / "manifest.json").read_text(encoding="utf-8"))
    before = {name: file_hash(CORPUS / name) for name in manifest["files"]}
    assert before == {name: info["sha256"] for name, info in manifest["files"].items()}
    expected = json.loads((CORPUS / "supported.json").read_text(encoding="utf-8"))
    fixed = json.loads((CORPUS / "fixed-time-codes.json").read_text(encoding="utf-8"))

    def codes_match(vault):
        for row in fixed:
            actual = [generate(e.secret, timestamp=row["timestamp"], digits=e.digits,
                               period=e.period, algorithm=e.algorithm) for e in vault.entries]
            assert actual == row["codes"], "Synthetic fixed-time code mismatch"

    for case in manifest["cases"]:
        if "recipe" in case:
            path = work / (case["id"] + ".d2fa")
            recipe = case["recipe"]
            prefix = (CORPUS / recipe["prefix"]).read_bytes()
            with path.open("xb") as stream:
                stream.write(prefix)
                remaining = recipe["zero_pad_to"] - len(prefix)
                while remaining:
                    block = bytes(min(remaining, 65_536))
                    stream.write(block)
                    remaining -= len(block)
            assert file_hash(path) == case["sha256"]
        else:
            path = CORPUS / case["file"]
        original = file_hash(path)
        try:
            vault = Vault.load(path, case["password"])
        except (InvalidPassword, CorruptedVault, UnsupportedFormat):
            assert case["desktop_load"] == "reject", "Unexpected pinned Desktop rejection"
        else:
            assert case["desktop_load"] == "accept", "Unexpected pinned Desktop acceptance"
            if case["id"] == "supported":
                assert vault.data.model_dump() == expected, "Synthetic field mismatch"
                codes_match(vault)
            if "desktop_generate" in case:
                e = vault.entries[0]
                try:
                    generate(e.secret, timestamp=60, digits=e.digits, period=e.period, algorithm=e.algorithm)
                except ValueError:
                    assert case["desktop_generate"] is False
                else:
                    assert case["desktop_generate"] is True
        assert file_hash(path) == original, "Source changed during load/rejection"

    password = next(case["password"] for case in manifest["cases"] if case["id"] == "supported")
    for name in ("kotlin-from-python", "kotlin-fresh"):
        path = work / (name + ".d2fa")
        original = file_hash(path)
        vault = Vault.load(path, password)
        assert vault.data.model_dump() == expected, "Kotlin to Python fields differ"
        codes_match(vault)
        vault.save(work / (name + "-via-python.d2fa"), password, overwrite=False)
        assert file_hash(path) == original, "Kotlin source changed"

    watch_v2 = work / "watch-v2.d2fa"
    archive = work / "no-backup/transfer-migration/vault-v1.d2fa"
    v2_before, archive_before = file_hash(watch_v2), file_hash(archive)
    try:
        Vault.load(watch_v2, password)
    except UnsupportedFormat:
        pass
    else:
        raise AssertionError("Pinned Desktop unexpectedly accepted Watch v2")
    retained = Vault.load(archive, password)
    assert retained.data.model_dump() == expected, "Retained v1 fields differ"
    codes_match(retained)
    assert file_hash(watch_v2) == v2_before and file_hash(archive) == archive_before

    assert {name: file_hash(CORPUS / name) for name in before} == before
    report = dict(desktop_commit=pin["commit"], cases=len(manifest["cases"]),
                  fixed_time_codes=len(fixed) * len(expected["entries"]), round_trips=2,
                  watch_v2_rejected=True, v1_archive_read=True)
    (work / "bridge-result.json").write_text(json.dumps(report), encoding="utf-8")
    print("Pinned Desktop scenarios and synthetic round-trip fields/codes verified")


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        # Pydantic/provider errors can include input; never print them or a traceback.
        raise SystemExit(f"Pinned Desktop verification failed: {type(failure).__name__}") from None
