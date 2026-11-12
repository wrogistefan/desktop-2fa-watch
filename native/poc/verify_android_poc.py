"""Check isolated PoC packaging, without accessing device/application data."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

sdk = Path(os.environ["ANDROID_HOME"])
nm = sdk / "ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"
aapt = sdk / "build-tools/36.0.0/aapt2"
apk = Path(sys.argv[1])
badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
if "package: name='org.desktop2fa.transfer.poc'" not in badging or "sharedUserId" in badging:
    raise SystemExit("Isolated package identity check failed")
with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as temp:
    for notice in ("assets/legal/PROJECT-LICENSE", "assets/legal/BORINGSSL-LICENSE"):
        if not archive.read(notice):
            raise SystemExit("Missing packaged license notice")
    names = [n for n in archive.namelist() if n.startswith("lib/") and n.endswith(".so")]
    expected = {"lib/" + abi + "/libd2fa_transfer.so" for abi in ("armeabi-v7a", "arm64-v8a", "x86_64")}
    if set(names) != expected:
        raise SystemExit("Unexpected packaged native libraries")
    for i, name in enumerate(sorted(names)):
        library = Path(temp) / (str(i) + ".so")
        library.write_bytes(archive.read(name))
        output = subprocess.check_output([str(nm), "-D", "--defined-only", str(library)], text=True)
        symbols = [line.split()[-1] for line in output.splitlines() if line.strip()]
        if ("d2fa_create" not in symbols or any(
                not (s.startswith("d2fa_") or s.startswith("Java_org_desktop2fa_transfer_NativeCore_"))
                or s.startswith("d2fa_test_") or s.endswith("_testKey") for s in symbols)):
            raise SystemExit("Android private export guard failed")
print("PASS: separate application ID, three ABIs, no public BoringSSL/test-key API")
