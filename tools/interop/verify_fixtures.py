"""Offline stdlib-only corpus/hash checks; cross-language execution belongs to D2."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "app/src/test/resources/interop/v1"
pin = json.loads((ROOT / "tools/interop/desktop-pin.json").read_text(encoding="utf-8"))
manifest = json.loads((CORPUS / "manifest.json").read_text(encoding="utf-8"))
assert manifest["schema_version"] == 1 and manifest["synthetic_only"] is True
assert manifest["desktop_commit"] == pin["commit"] == "7d1089d6bee0f3f82678ca8a13988c742840fba0"
assert manifest["desktop_release"] == pin["release"] == "v0.10.0"
assert manifest["dependencies"] == pin["dependencies"]
assert manifest["max_vault_bytes"] == 10 * 1024 * 1024

files = manifest["files"]
assert {p.name for p in CORPUS.iterdir()} == set(files) | {"manifest.json"}
for name, info in files.items():
    assert Path(name).name == name
    data = (CORPUS / name).read_bytes()
    assert len(data) == info["bytes"] < manifest["max_vault_bytes"]
    assert hashlib.sha256(data).hexdigest() == info["sha256"], name
    assert info["provenance"]

cases = manifest["cases"]
ids = [case["id"] for case in cases]
assert len(ids) == len(set(ids))
assert {"supported", "historical-empty", "historical-unicode-fold", "historical-trailing-bits",
        "unknown-root", "unknown-entry", "header-version-2", "payload-version-2",
        "payload-version-missing", "payload-version-string", "malformed-json", "invalid-utf8",
        "invalid-key", "invalid-period", "unsupported-digits", "unsupported-algorithm",
        "wrong-password", "tampered", "truncated-tag", "short-header", "invalid-magic",
        "at-size-limit-invalid-auth", "over-size-limit"} == set(ids)
for case in cases:
    assert case["desktop_load"] in {"accept", "reject"}
    assert case["watch_load"] in {"accept", "reject"}
    assert case["password"].startswith("Synthetic-")
    if case["round_trip"]:
        assert case["desktop_load"] == case["watch_load"] == "accept"
    if "recipe" in case:
        recipe = case["recipe"]
        assert recipe["prefix"] in files
        prefix = (CORPUS / recipe["prefix"]).read_bytes()
        length = recipe["zero_pad_to"]
        assert length in {manifest["max_vault_bytes"], manifest["max_vault_bytes"] + 1}
        digest = hashlib.sha256(prefix)
        left = length - len(prefix)
        while left:
            block = bytes(min(left, 65_536))
            digest.update(block)
            left -= len(block)
        assert digest.hexdigest() == case["sha256"]
        assert case["bytes"] == length
    else:
        assert case["file"] in files

payload = json.loads((CORPUS / "supported.json").read_text(encoding="utf-8"))
entries = payload["entries"]
assert payload["version"] == 1 and len(entries) == 13
assert {(e["algorithm"], e["digits"]) for e in entries} == {
    (algorithm, digits) for algorithm in ("SHA1", "SHA256", "SHA512") for digits in (6, 7, 8)}
assert any(e["period"] != 30 for e in entries)
assert any(e["issuer"] is None and e["account_name"] is None for e in entries)
assert entries[10] == entries[11]
assert any(not e["issuer"].isascii() for e in entries if e["issuer"])
codes = json.loads((CORPUS / "fixed-time-codes.json").read_text(encoding="utf-8"))
assert len(codes) == 9
for row in codes:
    assert len(row["codes"]) == len(entries)
    assert all(code.isascii() and code.isdigit() and len(code) == entry["digits"]
               for code, entry in zip(row["codes"], entries))
print(f"Verified {len(files)} artifact hashes, {len(cases)} cases and both bounded-size recipes")
