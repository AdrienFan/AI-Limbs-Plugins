# AI-Limbs-Plugins

Official public plugin repository for AI Limbs.

This repository is intended to host independently buildable AI Limbs plugins, including trusted system plugins (`.ailpsys`) and ordinary plugins (`.ailp`).

## Current plugin

- Plugin Center System V1
  - Android runtime module: `plugin-center`
  - Host ABI compile stubs: `system-sdk-stubs`
  - `.ailpsys` packager: `tools/package_ailpsys.py`

Plugin Center is built independently from the AI Limbs base APK. GitHub Actions only builds the unsigned plugin APK artifact; the Ed25519 system-plugin signing key is kept outside this public repository and is never committed.
## Top-bar overlay action

A provider may register `system.plugin_center.ui_accessories` / `register_page_slot_action` with
`action_kind: "overlay"`, `overlay_id`, and a provider whose metadata declares
`overlay_enabled: "true"`. The Host owns the Android window through
`host.window.overlay@1`; Plugin Center only routes the verified action.
An optional `badge_capability_id` must be owned by the same plugin and return
`unread_count`. The action is sorted by priority within `top_bar_start`.
