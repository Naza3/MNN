#!/usr/bin/env python3
"""Audit the actual unsigned arm64 APK; report facts separately from device claims."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import struct
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
ANDROID = "{http://schemas.android.com/apk/res/android}"
SYSTEM_LIBS = {"libandroid.so", "liblog.so", "libm.so", "libdl.so", "libc.so", "libz.so",
               "libjnigraphics.so", "libmediandk.so", "libEGL.so", "libGLESv2.so",
               "libGLESv3.so", "libOpenSLES.so", "libaaudio.so", "libvulkan.so"}
MODEL_SUFFIXES = {".mnn", ".weight", ".safetensors", ".gguf", ".onnx", ".tflite"}


def elf_load_segments(data):
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected ELF64 little-endian")
    if struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError("Expected AArch64 ELF machine")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    if phentsize < 56 or phoff + phentsize * phnum > len(data):
        raise ValueError("Invalid ELF program header table")
    segments = []
    for i in range(phnum):
        values = struct.unpack_from("<IIQQQQQQ", data, phoff + i * phentsize)
        if values[0] == 1:
            _, _, offset, virtual, _, size, _, align = values
            segments.append({"offset": offset, "virtual_address": virtual,
                             "file_size": size, "alignment": align})
    if not segments:
        raise ValueError("ELF has no PT_LOAD segments")
    return segments


def validate_manifest(xml, expected):
    root = ET.fromstring(xml)
    sdk = root.find("uses-sdk")
    app = root.find("application")
    permissions = sorted({n.get(ANDROID + "name") for n in root.findall("uses-permission")})
    services = []
    for node in app.findall("service") if app is not None else []:
        services.append({"name": node.get(ANDROID + "name"),
                         "exported": node.get(ANDROID + "exported"),
                         "foreground_service_type": node.get(ANDROID + "foregroundServiceType"),
                         "properties": {p.get(ANDROID + "name"): p.get(ANDROID + "value")
                                        for p in node.findall("property")}})
    report = {"package": root.get("package"),
              "min_sdk": sdk.get(ANDROID + "minSdkVersion") if sdk is not None else None,
              "target_sdk": sdk.get(ANDROID + "targetSdkVersion") if sdk is not None else None,
              "version_code": root.get(ANDROID + "versionCode"),
              "version_name": root.get(ANDROID + "versionName"),
              "debuggable": app.get(ANDROID + "debuggable", "false") if app is not None else None,
              "allow_backup": app.get(ANDROID + "allowBackup") if app is not None else None,
              "full_backup_content": app.get(ANDROID + "fullBackupContent") if app is not None else None,
              "data_extraction_rules": app.get(ANDROID + "dataExtractionRules") if app is not None else None,
              "permissions": permissions, "services": services}
    errors = []
    for name in ("package", "min_sdk", "target_sdk", "version_code", "version_name"):
        if report[name] != expected[name]:
            errors.append(f"Manifest {name}: expected {expected[name]}, got {report[name]}")
    service = next((s for s in services if s["name"] == expected["service"]), None)
    if not service:
        errors.append("Local API foreground service is missing")
    else:
        if service["exported"] != "false":
            errors.append("Local API service must explicitly be non-exported")
        if service["foreground_service_type"] != expected["foreground_service_type"]:
            errors.append("Local API service must use specialUse, not a time-limited dataSync type")
        if not service["properties"].get("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"):
            errors.append("Local API special-use service requires a subtype explanation")
    for permission in ("INTERNET", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_SPECIAL_USE", "POST_NOTIFICATIONS"):
        if "android.permission." + permission not in permissions:
            errors.append(f"Missing permission: {permission}")
    if report["debuggable"] != "false":
        errors.append("Release APK is unexpectedly debuggable")
    if report["allow_backup"] != "false" or report["full_backup_content"] != "false":
        errors.append("Release APK must disable App backup and full-backup content")
    if not report["data_extraction_rules"]:
        errors.append("Release APK must declare data extraction restrictions")
    return report, errors


def suspicious_entry(name, data):
    path = PurePosixPath(name)
    if path.suffix.lower() in MODEL_SUFFIXES or "builtin_models/" in name:
        return "model asset"
    if path.suffix.lower() in {".jks", ".keystore", ".p12", ".pem", ".key"}:
        return "credential/key container"
    if path.name in {"google-services.json", "local.properties", ".env"}:
        return "local configuration"
    if name.startswith("assets/") and path.suffix.lower() == ".bin" and len(data) > 262144:
        return "large binary model candidate"
    # Only expose a filename/reason, never a credential's matched bytes.
    if re.search(rb"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----", data):
        return "private key material"
    if re.search(rb"\b(?:sk-(?:proj-)?[A-Za-z0-9_-]{32,}|gh[pousr]_[A-Za-z0-9]{30,})\b", data):
        return "credential-like token"
    return None


def validate_extraction_rules(xml):
    root = ET.fromstring(xml)
    errors = []
    required = {"root", "sharedpref", "database", "file", "external"}
    if root.tag != "data-extraction-rules":
        return ["Incorrect backup data extraction root"]
    for mode in ("cloud-backup", "device-transfer"):
        node = root.find(mode)
        excluded = {e.get("domain") for e in node.findall("exclude") if e.get("path") == "."} if node is not None else set()
        if not required.issubset(excluded) or (node is not None and node.findall("include")):
            errors.append(f"Data extraction rules must exclude all private domains for {mode}")
    return errors


def extraction_reference_matches(reference, resource_dump):
    name = "local_api_data_extraction_rules"
    if reference == "@xml/" + name:
        return True
    expected_ids = re.findall(r"resource\s+(0x[0-9a-fA-F]+)\s+[^\n]*?xml/" + name + r"(?:\s|$)", resource_dump)
    # apkanalyzer versions may print a symbolic reference or its numeric ID.
    value = reference.removeprefix("@").removeprefix("ref/") if reference else ""
    try:
        return int(value, 0) in {int(item, 16) for item in expected_ids}
    except ValueError:
        return False


def dynamic_symbols(text):
    defined, required = set(), set()
    for line in text.splitlines():
        fields = line.split()
        if len(fields) < 8 or not fields[0].rstrip(":").isdigit():
            continue
        name = fields[7].split("@", 1)[0]
        if fields[6] != "UND":
            defined.add(name)
        elif fields[4] != "WEAK" and ("3MNN" in name or name.startswith("MNN")):
            required.add(name)
    return defined, required


def command(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--report-dir", type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads((HERE / "build-lock.json").read_text())
    expected, tc = lock["expected_apk"], lock["toolchain"]
    report = {"apk_filename": args.apk.name, "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              "apk_size": args.apk.stat().st_size, "native_libraries": [], "errors": [],
              "runtime_validation": "not_run: requires a real arm64 Android device and a user-selected model",
              "license_closure": "review_required: see NOTICE-AUDIT.md and dependency-inventory.json"}
    errors = report["errors"]
    source_mnn = HERE.parents[4] / "project/android/build_64/lib/libMNN.so"
    source_sherpa = HERE.parents[1] / "app/src/main/jniLibs/arm64-v8a/libsherpa-mnn-jni.so"
    args.report_dir.mkdir(parents=True, exist_ok=True)
    apkanalyzer = args.sdk / "cmdline-tools/latest/bin/apkanalyzer"
    build_tools = args.sdk / "build-tools" / tc["build_tools"]
    readelf = args.sdk / "ndk" / tc["ndk"] / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    try:
        xml = command(str(apkanalyzer), "manifest", "print", str(args.apk))
        manifest, failures = validate_manifest(xml, expected)
        report["manifest"] = manifest
        errors.extend(failures)
        (args.report_dir / "apk-manifest.xml").write_text(xml)
        rules_xml = command(str(apkanalyzer), "resources", "xml", "--file",
                            "res/xml/local_api_data_extraction_rules.xml", str(args.apk))
        errors.extend(validate_extraction_rules(rules_xml))
        resources = command(str(build_tools / "aapt2"), "dump", "resources", str(args.apk))
        if not extraction_reference_matches(manifest["data_extraction_rules"], resources):
            errors.append("Manifest does not reference the audited backup-exclusion XML resource")
        (args.report_dir / "data-extraction-rules.xml").write_text(rules_xml)
        alignment = command(str(build_tools / "zipalign"), "-c", "-P", "16", "-v", "4", str(args.apk))
        (args.report_dir / "zipalign.txt").write_text(alignment)
        report["zipalign_16k"] = "passed"
        signature = subprocess.run([str(build_tools / "apksigner"), "verify", "--verbose", str(args.apk)],
                                   capture_output=True, text=True)
        report["signing"] = "unsigned" if signature.returncode else "signed"
        if not signature.returncode:
            errors.append("CI release APK must be unsigned; signing belongs to the user's controlled environment")
        with zipfile.ZipFile(args.apk) as archive, tempfile.TemporaryDirectory(prefix="mnn-apk-audit-") as temp:
            libs = {PurePosixPath(n).name for n in archive.namelist() if n.startswith("lib/arm64-v8a/") and n.endswith(".so")}
            for required in expected["native_libraries"]:
                if required not in libs:
                    errors.append(f"Missing required real native library: {required}")
            packaged_symbols = {}
            for entry in archive.infolist():
                if entry.is_dir():
                    continue
                data = archive.read(entry)
                reason = suspicious_entry(entry.filename, data)
                if reason:
                    errors.append(f"Prohibited APK entry ({reason}): {entry.filename}")
                if not (entry.filename.startswith("lib/") and entry.filename.endswith(".so")):
                    continue
                if not entry.filename.startswith("lib/arm64-v8a/"):
                    errors.append(f"Unexpected ABI: {entry.filename}")
                    continue
                segments = elf_load_segments(data)
                for segment in segments:
                    if segment["alignment"] < 16384 or (segment["offset"] - segment["virtual_address"]) % 16384:
                        errors.append(f"Not 16KiB PT_LOAD aligned: {entry.filename}")
                local = Path(temp) / PurePosixPath(entry.filename).name
                local.write_bytes(data)
                notes = command(str(readelf), "--notes", str(local))
                build_ids = re.findall(r"Build ID:\s*([0-9a-f]+)", notes)
                if local.name in {"libMNN.so", "libsherpa-mnn-jni.so"}:
                    source = source_mnn if local.name == "libMNN.so" else source_sherpa
                    source_ids = re.findall(r"Build ID:\s*([0-9a-f]+)", command(str(readelf), "--notes", str(source)))
                    if not build_ids or build_ids != source_ids:
                        errors.append(f"Packaged {local.name} does not match its verified source artifact Build ID")
                    report.setdefault("native_provenance_matches", {})[local.name] = {
                        "build_id": build_ids, "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                        "matched": bool(build_ids) and build_ids == source_ids}
                packaged_symbols[local.name] = dynamic_symbols(command(str(readelf), "--dyn-syms", "--wide", str(local)))
                dynamic = command(str(readelf), "--dynamic", str(local))
                needed = sorted(set(re.findall(r"\(NEEDED\).*?\[([^]]+)\]", dynamic)))
                missing = sorted(set(needed) - libs - SYSTEM_LIBS)
                if missing:
                    errors.append(f"Unresolved native dependencies for {local.name}: {', '.join(missing)}")
                report["native_libraries"].append({"name": local.name,
                    "sha256": hashlib.sha256(data).hexdigest(), "size": len(data),
                    "zip_compression": entry.compress_type, "build_id": build_ids, "load_segments": segments,
                    "needed": needed, "unresolved": missing})
            mnn_exports = packaged_symbols.get("libMNN.so", (set(), set()))[0]
            for name, (_, required) in packaged_symbols.items():
                missing = sorted(required - mnn_exports)
                if missing:
                    errors.append(f"Unresolved MNN ABI symbols in {name}: {', '.join(missing)}")
            report["mnn_symbol_compatibility"] = "failed" if any("Unresolved MNN ABI" in error for error in errors) else "passed"
        report["static_package_audit"] = "failed" if errors else "passed"
    except (OSError, ValueError, ET.ParseError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        errors.append(str(error))
        report["static_package_audit"] = "failed"
    finally:
        (args.report_dir / "apk-audit.json").write_text(json.dumps(report, indent=2) + "\n")
    for error in errors:
        print(f"AUDIT FAILED: {error}")
    if errors:
        raise SystemExit(1)
    print(f"APK static audit passed: {report['apk_sha256']}; device and license review remain separate")


if __name__ == "__main__":
    main()
