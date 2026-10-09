# Self module presentation container, 1.3.44

Plugin Center no longer owns the full self-module management page or compact entry content. It reads self_resources through the existing plugin administration service, interprets the selected module descriptor at resources/presentation.json, then mounts its .ails HTML in ModuleHtmlView and places a small summary at the right of the inventory/add controls. Narrow widths or increased system font scale allow wrapping.

ModuleHtmlView is an offline page renderer without lifecycle semantics. SelfModuleScreen is the self control-plane adapter: it allows read/status, human request/submit/execute/cancel, explicit SAF package selection/export and clipboard transport. It has no AI review or autonomous interface. Native confirmation displays actual page write requests. Private paths, SAF URIs and authority bindings are supplied by the host adapter, never by page parameters.

The full-width old summary and hardcoded approval form/history are removed. Labels, multi-select request form, timing, lifecycle interaction and history presentation now ship inside .ails. An existing 0.1.1 package without HTML shows an explicit unavailable message and the separate native recovery surface. Native recovery is provided when the module is missing or its presentation fails, preserving the existing AI authorization and signed migration checks.

Requires base build116 for generic resource reads and resource-bearing .ails admission, and blank module 0.1.2 for the first package-owned UI. It adds no ordinary-plugin/child runtime changes or new ABI classes. Payload applicationId is unchanged; .ailpsys is a system-plugin update rather than a standalone comparison app.

Source review and syntax checks are the current verification stage. Cloud compilation and on-device UI verification remain pending.

## 1.3.45 offline loading repair

Module HTML now loads through a single Base64 memory document. The synthetic HTTPS load path is removed; network, file/content reads, external resource requests and navigation remain blocked. Main-frame HTTP errors are now also reported, and the JS transport uses a JVM-visible class. Ownership remains unchanged: the module supplies both UI documents, the container supplies transport and placement.

Three Android WebView instrumentation regressions read the actual module pages from the selected source revision during cloud checkout. They check management/form/ongoing grants, unapproved-action visibility and compact state display. Independent module packaging supplies 0.1.3 through its own workflow. These are prepared tests; cloud and device results must be recorded before claiming a working repair.

## 1.3.46 rendering-test classpath repair

Cloud run 37897731633 assembled the production APK successfully, then failed at compileDebugAndroidTestKotlin: Compose runtime was absent from the instrumentation compiler classpath. No page-rendering assertions ran, and the verified APK upload was skipped.

The instrumentation configuration now declares the same Compose BOM and its runtime explicitly. Production dependencies remain compileOnly so the system-plugin payload continues using host-provided Compose. Both APKs assemble before the emulator starts. The offscreen test bridge dispatches on the main looper rather than waiting for an unattached View.post queue, and signals status only after JavaScript delivery completes.

All three actual-module rendering checks remain required. Version is 1.3.46 (code 50); base build116 and independently packaged self module 0.1.3 are unchanged. Cloud compile and Android rendering verification are pending for this correction; device visual verification remains pending.

## 1.3.47 exact memory-document admission

Cloud run 37899982022 compiled both APKs and executed all three rendering tests. All three failed with a main-frame HTTP 403 produced by the renderer's unconditional shouldInterceptRequest response. Android invokes this callback for data URLs as well as network requests, so replacing HTTPS with loadData alone did not repair the document admission.

The renderer now constructs one explicit UTF-8 Base64 data URL and loads that exact URL. Only its top-level GET receives the platform's normal in-memory loading path. Other URLs, subframes and methods still receive 403; page navigation, networking, file/content access and persistence remain blocked. The module continues owning its HTML, and no base or module program code changes are required.

The three real-page checks remain unchanged. A fourth boundary test loads the real summary then checks that only the current document is admitted, while unrelated data, HTTP(S), file/content, subframe and POST requests remain blocked. Total required cloud tests: four. Center 1.3.47 (code 51) cloud validation is pending; device layout verification remains pending. Base remains build116 and self module remains independently packaged 0.1.3.

## 1.3.48 console placement and normal-page cleanup

Run 37902138870 completed successfully with the four required Android rendering checks. The user's device screenshots confirmed a working module-owned management page and summary with self module 0.1.3.

The five inventory counts now use the full header width before the action row, so the self-module tile cannot force the disabled count to wrap. The action row places Add Plugin on the left and the unchanged module-owned summary on the right; narrow widths and larger fonts retain wrapping. The native Host Recovery link is removed from the normal module page. Missing resources and rendering failures still reach the independent recovery surface automatically.

Only Plugin Center changes: version 1.3.48 (code 52). The base remains build116 and self module remains 0.1.3. No lifecycle, authorization, module HTML or bridge behavior changes. Cloud validation and device verification of this placement revision are pending.

## 1.3.49 compact import/search column

The user confirmed the 1.3.48 device layout and requested removing the large empty bands above and below Add Plugin. The full-width inventory count row remains first. Below it, Add Plugin and the search field form one left column with a 6dp gap, top-aligned beside the unchanged module-owned summary. The search field now uses the left column's width. Page/status filters and sorting remain full-width below this header; narrow widths and larger font scales stack the tile below the controls.

Search input, applied query, keyboard submission, clear/focus handling and all filter/sort callbacks are preserved while their presentation components are split. Imported package candidates remain visible before the filter row. Module HTML, dimensions, lifecycle and permissions are unchanged.

Only Plugin Center changes: 1.3.49 (code 53). Base build116 and independently packaged self module 0.1.3 remain unchanged. Diff checks passed; cloud compilation/rendering regressions and device layout verification for this revision are pending.

## 1.3.50 summary height follows the complete control column

The user confirmed the 1.3.49 layout on device and requested extending the summary through the empty region beside the filter row. On wide layouts, the left column now contains Add Plugin, search and filter/sort controls. It determines the header height; a matchParentSize layer places the summary at the right and fills that measured height. The card bottom aligns with the filter row bottom, retaining the normal 14dp separation before the plugin section or import candidates. The tile cannot inflate the measured header or overlap the left controls.

Narrow layouts keep their stacked controls and default summary height. All search, filtering, sorting and module-open callbacks are preserved. Module HTML and text stay inside .ails; this change only resizes its presentation container. Imported package candidates remain beneath the header controls.

Only Plugin Center changes: version 1.3.50 (code 54). Base build116 and self module 0.1.3 remain unchanged. Diff checks passed; cloud compilation/rendering regressions and device layout verification for this revision remain pending.
