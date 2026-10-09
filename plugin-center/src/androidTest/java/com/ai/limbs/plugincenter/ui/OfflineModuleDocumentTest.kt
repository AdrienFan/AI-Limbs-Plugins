package com.ai.limbs.plugincenter.ui

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises real Android WebView against the module-owned pages checked out by CI. */
@RunWith(AndroidJUnit4::class)
class OfflineModuleDocumentTest {
    @Test fun managementPageRendersButtonsFormsAndLongApprovalsOffline() {
        render("index.html", true) { view, inspect ->
            val result = inspect("JSON.stringify({title:document.title,line:document.getElementById('moduleLine').textContent,grants:document.getElementById('grants').innerText,buttons:['upgrade','rollback','migrate'].map(id=>({id,visible:getComputedStyle(document.getElementById(id)).display!=='none',disabled:document.getElementById(id).disabled})),choices:document.querySelectorAll('#permissionChoices input[type=checkbox]').length,modes:[...document.getElementById('mode').options].map(o=>o.value),background:getComputedStyle(document.body).backgroundColor,origin:location.protocol})")
            assertEquals("空白自我模块", result.getString("title"))
            assertTrue(result.getString("line").contains("0.1.3"))
            assertTrue(result.getString("grants").contains("长期有效"))
            assertEquals(3, result.getInt("choices"))
            assertEquals(listOf("ONE_TIME", "TIMED", "LONG"), (0..2).map { result.getJSONArray("modes").getString(it) })
            for (i in 0..2) {
                assertTrue(result.getJSONArray("buttons").getJSONObject(i).getBoolean("visible"))
                assertFalse(result.getJSONArray("buttons").getJSONObject(i).getBoolean("disabled"))
            }
            assertEquals("data:", result.getString("origin"))
            val settings = BooleanArray(2)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                settings[0] = view.settings.allowFileAccess
                settings[1] = view.settings.blockNetworkLoads
            }
            assertFalse(settings[0])
            assertTrue(settings[1])
        }
    }
    @Test fun managementActionsRemainVisibleWithoutOngoingApprovals() {
        render("index.html", false) { _, inspect ->
            val result = inspect("JSON.stringify({grants:document.getElementById('grants').innerText,hidden:document.getElementById('actions').hidden,upgradeDisabled:document.getElementById('upgrade').disabled,migrateDisabled:document.getElementById('migrate').disabled})")
            assertTrue(result.getString("grants").contains("无持续授权"))
            assertFalse(result.getBoolean("hidden"))
            assertFalse(result.getBoolean("upgradeDisabled"))
            assertFalse(result.getBoolean("migrateDisabled"))
        }
    }
    @Test fun compactSummaryRendersModuleStateOffline() {
        render("summary.html", false) { _, inspect ->
            val result = inspect("JSON.stringify({title:document.title,state:document.getElementById('state').textContent,manage:document.getElementById('manage').textContent})")
            assertEquals("自我模块", result.getString("title"))
            assertEquals("运行中", result.getString("state"))
            assertTrue(result.getString("manage").contains("管理"))
        }
    }
    private fun render(name: String, approved: Boolean, check: (WebView, (String) -> JSONObject) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val html = instrumentation.context.assets.open("self-module/" + name).bufferedReader().use { it.readText() }
        val error = AtomicReference<String?>()
        val started = CountDownLatch(1)
        lateinit var view: WebView
        val grants = JSONArray()
        if (approved) for (operation in listOf("upgrade", "rollback")) grants.put(JSONObject().put("operation", operation).put("mode", "LONG"))
        val snapshot = JSONObject().put("success", true).put("device_id", "a".repeat(64))
            .put("module", JSONObject().put("identity_id", "fixture").put("module_version", "0.1.3").put("state_schema_version", 1).put("lifecycle_state", "ACTIVE"))
            .put("versions", JSONArray(listOf("0.1.1", "0.1.3"))).put("requests", JSONArray())
            .put("authorizations", JSONObject().put("effective_grants", grants))
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext)
            view.addJavascriptInterface(ModuleMessageBridge { message ->
                val envelope = JSONObject(message)
                val method = envelope.getString("method")
                if (method !in setOf("__ready", "status")) error.set("Unexpected page operation: " + method)
                val response = if (method == "status") snapshot else JSONObject().put("success", true)
                val reply = JSONObject().put("id", envelope.getString("id")).put("response", response).toString()
                view.post {
                    view.evaluateJavascript("window.ailsReceive(" + JSONObject.quote(reply) + ");", null)
                    if (method == "status") started.countDown()
                }
            }, "AilsTransport")
            OfflineModuleDocument.load(view, html, 1f) { error.set(it); started.countDown() }
        }
        try {
            assertTrue("Module startup timed out", started.await(20, TimeUnit.SECONDS))
            assertNull(error.get())
            val inspect: (String) -> JSONObject = { script ->
                val completed = CountDownLatch(1)
                val value = AtomicReference<String>()
                instrumentation.runOnMainSync { view.evaluateJavascript(script) { value.set(it); completed.countDown() } }
                assertTrue("DOM inspection timed out", completed.await(10, TimeUnit.SECONDS))
                // evaluateJavascript quotes a JS string one additional time.
                JSONObject(JSONArray("[" + value.get() + "]").getString(0))
            }
            check(view, inspect)
            assertNull(error.get())
        } finally { instrumentation.runOnMainSync { view.removeJavascriptInterface("AilsTransport"); view.destroy() } }
    }
}
