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

from pem_scan import private_key_markers, credential_like_token
from public_fixture import validate_fixture_proof
from engine_source import verified_engine_paths, verify_app_native_roots

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


def foreground_type_matches(value, expected):
    # ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE is exactly 1 << 30.
    # apkanalyzer renders compiled enum/flag attributes as hexadecimal integers.
    # Equality, not a bitwise subset check, rejects specialUse | dataSync too.
    if expected != "specialUse":
        raise ValueError("Unsupported foreground service policy")
    if value == expected:
        return True
    if not value or not re.fullmatch(r"(?:0[xX][0-9a-fA-F]+|[0-9]+)", value):
        return False
    return int(value, 16 if value.lower().startswith("0x") else 10) == 0x40000000


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
    for key, label in (("service", "Local API"), ("chat_service", "Background chat")):
        matching = [s for s in services if s["name"] == expected[key]]
        if not matching:
            errors.append(f"{label} foreground service is missing")
            continue
        if len(matching) != 1:
            errors.append(f"{label} foreground service must be declared exactly once")
        for service in matching:
            if service["exported"] != "false":
                errors.append(f"{label} service must explicitly be non-exported")
            if not foreground_type_matches(service["foreground_service_type"], expected["foreground_service_type"]):
                errors.append(f"{label} service must use specialUse, not a time-limited dataSync type")
            if not (service["properties"].get("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE") or "").strip():
                errors.append(f"{label} special-use service requires a subtype explanation")
    for permission in ("INTERNET", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_SPECIAL_USE", "POST_NOTIFICATIONS", "WAKE_LOCK"):
        if "android.permission." + permission not in permissions:
            errors.append(f"Missing permission: {permission}")
    if report["debuggable"] != "false":
        errors.append("Release APK is unexpectedly debuggable")
    if report["allow_backup"] != "false" or report["full_backup_content"] != "false":
        errors.append("Release APK must disable App backup and full-backup content")
    if not report["data_extraction_rules"]:
        errors.append("Release APK must declare data extraction restrictions")
    return report, errors


def suspicious_entry(name, data, trusted_fixture_sha=None):
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
    if any(not item["accepted"] for item in private_key_markers(data, trusted_fixture_sha)):
        return "private key material"
    if credential_like_token(data):
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


def xml_resource_metadata(resource_dump):
    """Keep only XML resource metadata, never unrelated compiled string values."""
    lines, selected = [], False
    for line in resource_dump.splitlines():
        if re.match(r"\s*(?:resource |type |Package )", line):
            selected = bool(re.match(r"\s*resource 0x[0-9a-fA-F]+ (?:[^ :]+:)?xml/", line))
        if selected:
            lines.append(line)
    return "\n".join(lines) + "\n"


def resolve_extraction_resources(reference, resource_dump):
    """Follow the manifest's resource identity to every compiled XML variant.

    Release AAPT can shorten file paths and collapse symbolic resource names.
    Never guess a source filename or validate a different, unreferenced XML.
    Resource aliases/unknown formats fail closed instead of being skipped.
    """
    resources, current = [], None
    for line in xml_resource_metadata(resource_dump).splitlines():
        match = re.match(r"\s*resource (0x[0-9a-fA-F]+) ([^\s]+)(?:\s.*)?$", line)
        if match:
            current = {"id": match[1].lower(), "name": match[2], "values": []}
            resources.append(current)
        elif current is not None and line.strip():
            current["values"].append(line.strip())
    numeric = re.fullmatch(r"@(?:ref/)?(0[xX][0-9a-fA-F]+|[0-9]+)", reference or "")
    if numeric:
        value = numeric[1]
        resource_id = int(value, 16 if value.lower().startswith("0x") else 10)
        selected = [r for r in resources if int(r["id"], 16) == resource_id]
    else:
        symbolic = re.fullmatch(r"@((?:[^ :/]+:)?xml/[^ /]+)", reference or "")
        selected = [r for r in resources if symbolic and
                    (r["name"] == symbolic[1] or
                     (":" not in symbolic[1] and r["name"].split(":")[-1] == symbolic[1]))]
    if len(selected) != 1:
        raise ValueError("Manifest dataExtractionRules must resolve to exactly one XML resource ID")
    resource, variants, configurations = selected[0], [], set()
    for line in resource["values"]:
        match = re.fullmatch(r"\(([^)]*)\) \(file\) ([^\s]+) type=XML", line)
        if not match:
            raise ValueError("Backup XML resource has an unsupported or non-file value: " + line)
        config, path = match.groups()
        parts = PurePosixPath(path).parts
        if (not path.startswith("res/") or ".." in parts or "\\" in path or
                str(PurePosixPath(path)) != path or not path.endswith(".xml")):
            raise ValueError("Unsafe compiled backup XML path: " + path)
        if config in configurations:
            raise ValueError("Duplicate backup XML resource configuration: " + config)
        configurations.add(config)
        variants.append({"configuration": config, "path": path})
    if "" not in configurations:
        raise ValueError("Backup XML resource has no default configuration")
    return {"reference": reference, "resource_id": resource["id"],
            "resource_name": resource["name"], "variants": variants}


def audit_compiled_manifest(apk, apkanalyzer, aapt2, report_dir, expected, report):
    """Retain static diagnostic metadata before interpreting compiled values."""
    report_dir.mkdir(parents=True, exist_ok=True)
    diagnostics = report.setdefault("metadata_commands", [])

    def inspect(label, *args):
        result = subprocess.run([str(x) for x in args], capture_output=True, text=True)
        record = {"stage": label, "returncode": result.returncode}
        diagnostics.append(record)
        if result.returncode:
            # Only SDK diagnostics from this generated APK, no test/chat stdout.
            record["diagnostic"] = (result.stderr + result.stdout)[:4096]
            raise ValueError(f"APK metadata command failed: {label} (exit {result.returncode})")
        return result.stdout

    with zipfile.ZipFile(apk) as archive:
        entries = [{"path": n.filename, "size": n.file_size} for n in archive.infolist()
                   if n.filename.startswith("res/") or n.filename in {"AndroidManifest.xml", "resources.arsc"}]
    (report_dir / "apk-resource-entries.json").write_text(json.dumps(entries, indent=2) + "\n")
    xml = inspect("manifest_print", apkanalyzer, "manifest", "print", apk)
    (report_dir / "apk-manifest.xml").write_text(xml)
    resources = inspect("resource_table", aapt2, "dump", "resources", apk)
    (report_dir / "apk-xml-resource-table.txt").write_text(xml_resource_metadata(resources))
    manifest, errors = validate_manifest(xml, expected)
    report["manifest"] = manifest
    report["manifest_validation_errors"] = list(errors)
    resolution = resolve_extraction_resources(manifest["data_extraction_rules"], resources)
    report["data_extraction_resource"] = resolution
    entry_names = [entry["path"] for entry in entries]
    for index, variant in enumerate(resolution["variants"]):
        if entry_names.count(variant["path"]) != 1:
            raise ValueError("Backup XML ZIP entry must exist exactly once: " + variant["path"])
        rules_xml = inspect("backup_xml_" + str(index), apkanalyzer, "resources", "xml",
                            "--file", variant["path"], apk)
        filename = "data-extraction-rules.xml" if not variant["configuration"] else f"data-extraction-rules-{index}.xml"
        (report_dir / filename).write_text(rules_xml)
        variant["decoded_report"] = filename
        failures = validate_extraction_rules(rules_xml)
        variant["validation"] = "failed" if failures else "passed"
        errors.extend(f"{variant['path']} ({variant['configuration'] or 'default'}): {failure}" for failure in failures)
    return errors


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


def validate_sherpa_provenance(record, lock, library_sha, mnn_sha, inputs_sha):
    expected = {"source_kind": "built_from_locked_source", "library_sha256": library_sha,
                "mnn_library_sha256": mnn_sha, "engine_base_sha": lock["engine_base_sha"],
                "source_root": lock["sherpa"]["source_root"],
                "source_tree_sha": lock["sherpa"]["source_tree_sha"],
                "source_inputs_report_sha256": inputs_sha}
    return [f"Sherpa provenance mismatch: {key}" for key, value in expected.items() if record.get(key) != value]


def command(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--report-dir", type=Path, required=True)
    parser.add_argument("--gradle-home", type=Path, default=Path.home() / ".gradle")
    args = parser.parse_args()
    lock = json.loads((HERE / "build-lock.json").read_text())
    expected, tc = lock["expected_apk"], lock["toolchain"]
    report = {"apk_filename": args.apk.name, "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              "apk_size": args.apk.stat().st_size, "native_libraries": [], "errors": [],
              "runtime_validation": "not_run: requires a real arm64 Android device and a user-selected model",
              "license_closure": "source_and_notice_evidence: see NOTICE-AUDIT.md, sherpa-source-inputs.json and dependency-inventory.json"}
    errors = report["errors"]
    source_mnn = None
    source_sherpa = HERE.parents[1] / "app/src/main/jniLibs/arm64-v8a/libsherpa-mnn-jni.so"
    args.report_dir.mkdir(parents=True, exist_ok=True)
    apkanalyzer = args.sdk / "cmdline-tools/latest/bin/apkanalyzer"
    build_tools = args.sdk / "build-tools" / tc["build_tools"]
    readelf = args.sdk / "ndk" / tc["ndk"] / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    try:
        engine_root, engine_install = verified_engine_paths(args.report_dir)
        source_mnn = engine_install / "lib/libMNN.so"
        try:
            errors.extend(audit_compiled_manifest(args.apk, apkanalyzer, build_tools / "aapt2",
                                                 args.report_dir, expected, report))
        except (OSError, ValueError, ET.ParseError, zipfile.BadZipFile) as error:
            # Keep auditing independent native/signature properties for diagnosis.
            errors.append(str(error))
        try:
            report["native_build_roots"] = verify_app_native_roots(engine_root, engine_install)
        except (OSError, RuntimeError, ValueError, KeyError) as error:
            report["native_build_roots_error"] = str(error)
            errors.append(str(error))
        alignment = command(str(build_tools / "zipalign"), "-c", "-P", "16", "-v", "4", str(args.apk))
        (args.report_dir / "zipalign.txt").write_text(alignment)
        report["zipalign_16k"] = "passed"
        signature = subprocess.run([str(build_tools / "apksigner"), "verify", "--verbose", str(args.apk)],
                                   capture_output=True, text=True)
        report["signing"] = "unsigned" if signature.returncode else "signed"
        if not signature.returncode:
            errors.append("CI release APK must be unsigned; signing belongs to the user's controlled environment")
        proof = json.loads((args.report_dir / "public-test-fixture-provenance.json").read_text())
        validate_fixture_proof(proof, lock["public_test_fixture"], args.gradle_home)
        report["public_test_fixture"] = proof
        trusted_fixture_sha = lock["public_test_fixture"]["constant_sha256"]
        report["private_key_marker_classifications"] = []
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
                markers = private_key_markers(data, trusted_fixture_sha)
                if markers:
                    report["private_key_marker_classifications"].append({"entry": entry.filename, "markers": markers})
                reason = suspicious_entry(entry.filename, data, trusted_fixture_sha)
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
            sherpa_exports = packaged_symbols.get("libsherpa-mnn-jni.so", (set(), set()))[0]
            missing_jni = sorted(set(lock["sherpa"]["required_jni_exports"]) - sherpa_exports)
            if missing_jni:
                errors.append("Missing App ASR JNI exports: " + ", ".join(missing_jni))
            report["sherpa_jni_exports"] = "failed" if missing_jni else "passed"
            provenance_path = args.report_dir / "sherpa-provenance.json"
            provenance = json.loads(provenance_path.read_text())
            errors.extend(validate_sherpa_provenance(provenance, lock,
                hashlib.sha256(source_sherpa.read_bytes()).hexdigest(),
                hashlib.sha256(source_mnn.read_bytes()).hexdigest(),
                hashlib.sha256((args.report_dir / "sherpa-source-inputs.json").read_bytes()).hexdigest()))
            mnn_exports = packaged_symbols.get("libMNN.so", (set(), set()))[0]
            for name, (_, required) in packaged_symbols.items():
                missing = sorted(required - mnn_exports)
                if missing:
                    errors.append(f"Unresolved MNN ABI symbols in {name}: {', '.join(missing)}")
            report["mnn_symbol_compatibility"] = "failed" if any("Unresolved MNN ABI" in error for error in errors) else "passed"
        report["static_package_audit"] = "failed" if errors else "passed"
    except (OSError, RuntimeError, ValueError, ET.ParseError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
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
