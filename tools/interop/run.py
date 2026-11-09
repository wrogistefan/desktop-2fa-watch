"""Run full JVM/APK verification with the actual pinned Desktop bridge enabled."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

from generate_fixtures import verify_source

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--desktop-source", type=Path, required=True)
    args = parser.parse_args()
    source = args.desktop_source.resolve()
    verify_source(source)
    subprocess.run([sys.executable, str(ROOT / "tools/interop/verify_fixtures.py")], check=True, cwd=ROOT)
    env = os.environ.copy()
    env.update(D2FA_INTEROP="1", D2FA_INTEROP_REPO=str(ROOT),
               D2FA_DESKTOP_SOURCE=str(source), D2FA_INTEROP_PYTHON=sys.executable)
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run([str(wrapper), ":app:testDebugUnitTest", ":app:assembleDebug", "--stacktrace"],
                   check=True, cwd=ROOT, env=env)
    result = ET.parse(ROOT / "app/build/test-results/testDebugUnitTest/TEST-org.desktop2fa.desktop.watch.vault.PinnedInteropTest.xml")
    case = result.find(".//testcase[@name='actualPinnedPythonRunsBothRoundTripDirections']")
    if case is None or any(case.find(tag) is not None for tag in ("skipped", "failure", "error")):
        raise RuntimeError("Actual pinned Desktop bridge did not pass")
    print("Full JVM/APK checks include a passing actual pinned Desktop bridge")


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        raise SystemExit(f"Interoperability runner failed: {type(failure).__name__}") from None
