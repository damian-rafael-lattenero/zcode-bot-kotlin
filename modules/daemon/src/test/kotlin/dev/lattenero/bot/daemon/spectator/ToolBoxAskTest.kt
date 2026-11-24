package dev.lattenero.bot.daemon.spectator

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** The question box speaks the question and its options — never the raw JSON (24/11). */
class ToolBoxAskTest {

    @Test
    fun theQuestionSpeaksAsOptionsNotJson() {
        val inp = buildJsonObject {
            put("questions", buildJsonArray {
                add(
                    buildJsonObject {
                        put("header", "Deploy")
                        put("question", "¿Deployo el formato?")
                        put("multiSelect", false)
                        put(
                            "options",
                            buildJsonArray {
                                add(buildJsonObject { put("label", "Deployá ya"); put("description", "ahora") })
                                add(buildJsonObject { put("label", "Esperá"); put("description", "después") })
                            },
                        )
                    },
                )
            })
        }
        assertEquals(
            "🔧 <b>AskUserQuestion</b>\n" +
                "❓ <b>Deploy</b>\n" +
                "¿Deployo el formato?\n" +
                "• <b>Deployá ya</b> — ahora\n" +
                "• <b>Esperá</b> — después",
            ToolBox.box("AskUserQuestion", inp),
        )
    }

    @Test
    fun anUnknownShapeFallsBackToTheJson() {
        val text = ToolBox.box("AskUserQuestion", buildJsonObject { put("nada", "x") })
        assertEquals("🔧 <b>AskUserQuestion</b>\n<pre><code class=\"language-json\">{\"nada\": \"x\"}</code></pre>", text)
    }
}
