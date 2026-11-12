"""Developer-only production-configuration export guard; never inspect key data."""
import os
from pathlib import Path
import re
import subprocess
import sys

library = Path(sys.argv[1]).resolve()
if sys.platform == "win32":
    vswhere = Path(os.environ["ProgramFiles(x86)"]) / "Microsoft Visual Studio/Installer/vswhere.exe"
    found = subprocess.check_output([str(vswhere), "-latest", "-products", "*", "-requires",
        "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-find",
        r"VC\Tools\MSVC\**\bin\Hostx64\x64\dumpbin.exe"], text=True).splitlines()
    if not found:
        raise SystemExit("Developer dumpbin tool unavailable")
    output = subprocess.check_output([found[0], "/EXPORTS", str(library)], text=True)
    symbols = re.findall(r"^\s*\d+\s+[0-9A-Fa-f]+\s+[0-9A-Fa-f]+\s+(\S+)", output, re.MULTILINE)
elif sys.platform == "darwin":
    output = subprocess.check_output(["nm", "-gU", str(library)], text=True)
    symbols = [line.split()[-1].removeprefix("_") for line in output.splitlines() if line.strip()]
elif sys.platform.startswith("linux"):
    output = subprocess.check_output(["nm", "-D", "--defined-only", str(library)], text=True)
    symbols = [line.split()[-1] for line in output.splitlines() if line.strip()]
else:
    raise SystemExit("No export verifier for this platform")
if (not symbols or "d2fa_create" not in symbols or
        any(not (s.startswith("d2fa_") or s.startswith("Java_org_desktop2fa_transfer_NativeCore_"))
            for s in symbols) or
        any(s.startswith("d2fa_test_") or s.endswith("_testKey") for s in symbols)):
    raise SystemExit("Production-configuration export guard failed")
print("PASS: private BoringSSL symbols and test API absent from production exports")
