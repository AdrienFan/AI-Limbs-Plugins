# AI-Limbs-Plugins

Official public plugin repository for AI Limbs.

This repository is intended to host independently buildable AI Limbs plugins, including trusted system plugins (`.ailpsys`) and ordinary plugins (`.ailp`).

## Current plugin

- Plugin Center System V1
  - Android runtime module: `plugin-center`
  - Host ABI compile stubs: `system-sdk-stubs`
  - `.ailpsys` packager: `tools/package_ailpsys.py`

Plugin Center is built independently from the AI Limbs base APK. GitHub Actions only builds the unsigned plugin APK artifact; the Ed25519 system-plugin signing key is kept outside this public repository and is never committed.