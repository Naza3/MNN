#!/usr/bin/env python3
"""Fetch only the existing, hash-pinned auxiliary Sherpa JNI library."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
APP_ROOT = HERE.parents[1]


def extract_verified(archive, lock, destination):
    data = archive.read_bytes()
    actual = hashlib.sha256(data).hexdigest()
    if actual != lock["archive_sha256"]:
        raise RuntimeError(f"Sherpa archive hash mismatch: {actual}")
    with zipfile.ZipFile(archive) as source:
        # Do not extract arbitrary archive paths or bundled replacement engines.
        content = source.read(lock["member"])
        if content[:6] != b"\x7fELF\x02\x01":
            raise RuntimeError("Sherpa member is not a little-endian ELF64 library")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(content)
    return {"verified_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "url": lock["url"], "archive_sha256": actual,
            "library_sha256": hashlib.sha256(content).hexdigest(),
            "source_provenance": lock["source_provenance"]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report-dir", type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads((HERE / "build-lock.json").read_text())["sherpa"]
    destination = APP_ROOT / "app/src/main/jniLibs/arm64-v8a" / lock["member"]
    with tempfile.TemporaryDirectory(prefix="mnn-sherpa-") as temp:
        archive = Path(temp) / "sherpa.zip"
        subprocess.run(["curl", "--fail", "--show-error", "--location", "--proto", "=https",
                        "--proto-redir", "=https", "--tlsv1.2", "--retry", "3", "--max-time", "300",
                        lock["url"], "--output", str(archive)], check=True)
        report = extract_verified(archive, lock, destination)
    args.report_dir.mkdir(parents=True, exist_ok=True)
    (args.report_dir / "sherpa-provenance.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Verified auxiliary {lock['member']}; main MNN will be built from source")


if __name__ == "__main__":
    main()
