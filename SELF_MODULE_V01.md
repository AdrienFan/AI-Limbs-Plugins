# Self Module control plane — 1.3.43 / code47

The special .ails module appears in its own summary card with current effective authorization. Details contain actual grants/expiry, a collapsible multiselect application form, explicit authorized operations and per-item approval history.

ONE_TIME binds exact package/version/migration target and is executed upon AI approval. TIMED/LONG creates one independent request per selected function with a shared batch ID and human reason. AI can reject individual items and shorten duration; the approval result controls effective authority. Duration begins at approval. Existing applications never execute automatically when a persistent grant is enabled.

The UI can view/request/cancel and use existing grants. It cannot approve or revoke grants. Host admission, one-slot lifecycle, signed migration handoff and trusted AI review remain in the paired base build115.

Cloud build only. The APK is a payload for a signed .ailpsys package, not a standalone app to install directly. Host and Resident device acceptance is still required after compilation.

First cloud compile found a missing facade import in the summary card; corrected for 1.3.43.
