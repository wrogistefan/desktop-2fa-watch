"""Linux production-configuration export guard; never inspect key data."""
from pathlib import Path
import subprocess
import sys

library = Path(sys.argv[1]).resolve()
output = subprocess.check_output(["nm", "-D", "--defined-only", str(library)], text=True)
symbols = [line.split()[-1] for line in output.splitlines() if line.strip()]
if (not symbols or "d2fa_create" not in symbols or
        any(not (s.startswith("d2fa_") or s.startswith("Java_org_desktop2fa_transfer_NativeCore_"))
            for s in symbols) or
        any("test_key" in s or "testKey" in s for s in symbols)):
    raise SystemExit("Production-configuration export guard failed")
print("PASS: private BoringSSL symbols and full-key test API absent from production exports")
