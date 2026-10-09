# Self module presentation container, 1.3.44

Plugin Center no longer owns the full self-module management page or compact entry content. It reads self_resources through the existing plugin administration service, interprets the selected module descriptor at resources/presentation.json, then mounts its .ails HTML in ModuleHtmlView and places a small summary at the right of the inventory/add controls. Narrow widths or increased system font scale allow wrapping.

ModuleHtmlView is an offline page renderer without lifecycle semantics. SelfModuleScreen is the self control-plane adapter: it allows read/status, human request/submit/execute/cancel, explicit SAF package selection/export and clipboard transport. It has no AI review or autonomous interface. Native confirmation displays actual page write requests. Private paths, SAF URIs and authority bindings are supplied by the host adapter, never by page parameters.

The full-width old summary and hardcoded approval form/history are removed. Labels, multi-select request form, timing, lifecycle interaction and history presentation now ship inside .ails. An existing 0.1.1 package without HTML shows an explicit unavailable message and the separate native recovery surface. The native recovery entry is always accessible and preserves the existing AI authorization and signed migration checks.

Requires base build116 for generic resource reads and resource-bearing .ails admission, and blank module 0.1.2 for the first package-owned UI. It adds no ordinary-plugin/child runtime changes or new ABI classes. Payload applicationId is unchanged; .ailpsys is a system-plugin update rather than a standalone comparison app.

Source review and syntax checks are the current verification stage. Cloud compilation and on-device UI verification remain pending.
