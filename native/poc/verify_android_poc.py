"""Check isolated PoC packaging, without accessing device/application data."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

sdk = Path(os.environ["ANDROID_HOME"])
host = {"win32": "windows-x86_64", "darwin": "darwin-x86_64"}.get(sys.platform, "linux-x86_64")
suffix = ".exe" if os.name == "nt" else ""
nm = sdk / ("ndk/28.2.13676358/toolchains/llvm/prebuilt/" + host + "/bin/llvm-nm" + suffix)
aapt = sdk / ("build-tools/36.0.0/aapt2" + suffix)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk", type=Path)
parser.add_argument("--watch-app", action="store_true", help="Verify the product app identity instead of the isolated PoC")
parser.add_argument("--without-native", action="store_true", help="Require the ordinary offline app with no transfer native library/notices")
parser.add_argument("--offline", action="store_true", help="Refuse an INTERNET permission")
args = parser.parse_args()
if args.without_native and not args.watch_app:
    parser.error("--without-native requires --watch-app")
apk = args.apk
badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
expected_package = "org.desktop2fa.desktop.watch" if args.watch_app else "org.desktop2fa.transfer.poc"
if f"package: name='{expected_package}'" not in badging or "sharedUserId" in badging:
    raise SystemExit("Application identity check failed")
if args.offline and "android.permission.INTERNET" in badging:
    raise SystemExit("Offline app unexpectedly requests network permission")
with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as temp:
    notices = ("assets/legal/PROJECT-LICENSE", "assets/legal/BORINGSSL-LICENSE")
    if args.without_native:
        if any(name in archive.namelist() for name in notices):
            raise SystemExit("Ordinary app unexpectedly contains native license assets")
    else:
        for notice in notices:
            if not archive.read(notice):
                raise SystemExit("Missing packaged license notice")
    names = [n for n in archive.namelist() if n.startswith("lib/") and n.endswith(".so")]
    core = set() if args.without_native else {
        "lib/" + abi + "/libd2fa_transfer.so" for abi in ("armeabi-v7a", "arm64-v8a", "x86_64")}
    # Existing Compose dependency, also present in ordinary pre-transfer Watch APKs.
    graphics_abis = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64") if args.without_native else ("armeabi-v7a", "arm64-v8a", "x86_64")
    graphics = {"lib/" + abi + "/libandroidx.graphics.path.so" for abi in graphics_abis} if args.watch_app else set()
    if set(names) != core | graphics:
        raise SystemExit("Unexpected packaged native libraries")
    for i, name in enumerate(sorted(core)):
        library = Path(temp) / (str(i) + ".so")
        library.write_bytes(archive.read(name))
        output = subprocess.check_output([str(nm), "-D", "--defined-only", str(library)], text=True)
        symbols = [line.split()[-1] for line in output.splitlines() if line.strip()]
        allowed = all(symbol.startswith("d2fa_") or
                      symbol.startswith("Java_org_desktop2fa_transfer_NativeCore_") for symbol in symbols)
        denied = any(symbol.startswith("d2fa_test_") or symbol.endswith("_testKey") for symbol in symbols)
        if "d2fa_create" not in symbols or not allowed or denied:
            raise SystemExit("Android private export guard failed")
print("PASS: application identity, expected native packaging/permissions, no public BoringSSL/test-key API")
