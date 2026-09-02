#!/usr/bin/env python3
import argparse
import hashlib
import json
import subprocess
import tempfile
import zipfile
from pathlib import Path

PLUGIN_ID = "ai_limbs.system.plugin_center"
ENTRY_CLASS = "com.ai.limbs.plugincenter.PluginCenterEntry"
RUNTIME_ENTRY = "payload/plugin-center.apk"
SIGNATURE_ENTRY = "META-INF/AILIMBS.SIG"
FORMAT = "AIL_SYSTEM_PLUGIN_V1"
SCHEMA_VERSION = 1
HOST_ABI = 1


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def manifest_bytes(version: str, signer_id: str, apk_hash: str) -> bytes:
    manifest = {
        "format": FORMAT,
        "schema_version": SCHEMA_VERSION,
        "plugin_id": PLUGIN_ID,
        "version": version,
        "display": {
            "name": "Plugin Center",
            "description": "AI Limbs 插件与系统接口管理中心",
        },
        "system": {
            "role": "plugin_center",
            "host_abi": {"min": HOST_ABI, "max": HOST_ABI},
        },
        "runtime": {
            "kind": "android_inprocess",
            "entry": RUNTIME_ENTRY,
            "entry_class": ENTRY_CLASS,
        },
        "permissions": {"requested_scopes": []},
        "signature": {
            "algorithm": "Ed25519",
            "signer_id": signer_id,
            "entry": SIGNATURE_ENTRY,
        },
        "integrity": {
            "algorithm": "SHA-256",
            "entries": {RUNTIME_ENTRY: apk_hash},
        },
    }
    return json.dumps(
        manifest,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def sign_manifest(manifest: bytes, private_key: Path) -> bytes:
    with tempfile.TemporaryDirectory() as temp_dir:
        manifest_path = Path(temp_dir) / "system-plugin.json"
        signature_path = Path(temp_dir) / "AILIMBS.SIG"
        manifest_path.write_bytes(manifest)
        subprocess.run(
            [
                "openssl", "pkeyutl", "-sign", "-rawin",
                "-inkey", str(private_key),
                "-in", str(manifest_path),
                "-out", str(signature_path),
            ],
            check=True,
        )
        signature = signature_path.read_bytes()
        if not signature:
            raise RuntimeError("Ed25519 signature is empty")
        return signature


def package(apk: Path, output: Path, version: str, signer_id: str, private_key: Path) -> None:
    if not apk.is_file():
        raise FileNotFoundError(apk)
    if not private_key.is_file():
        raise FileNotFoundError(private_key)
    if output.suffix.lower() != ".ailpsys":
        raise ValueError("output must use .ailpsys")
    apk_hash = sha256(apk)
    manifest = manifest_bytes(version, signer_id, apk_hash)
    signature = sign_manifest(manifest, private_key)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("system-plugin.json", manifest)
        archive.write(apk, RUNTIME_ENTRY)
        archive.writestr(SIGNATURE_ENTRY, signature)

    print(f"created: {output}")
    print(f"plugin_id: {PLUGIN_ID}")
    print(f"version: {version}")
    print(f"payload_sha256: {apk_hash}")
    print(f"package_sha256: {sha256(output)}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Build PluginCenter.ailpsys from plugin-center.apk")
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--signer-id", default="ai-limbs-plugin-center-dev-v1")
    parser.add_argument("--private-key", required=True, type=Path)
    args = parser.parse_args()
    package(
        apk=args.apk.resolve(),
        output=args.output.resolve(),
        version=args.version.strip(),
        signer_id=args.signer_id.strip(),
        private_key=args.private_key.resolve(),
    )


if __name__ == "__main__":
    main()
