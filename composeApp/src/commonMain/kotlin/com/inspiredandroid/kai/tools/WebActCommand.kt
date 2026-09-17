package com.inspiredandroid.kai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure command-building and result-parsing for the `web_act` tool (Kai-native
 * web interaction: no browser-use framework, no extra model, no keys). The
 * Android session manager executes these strings against a persistent
 * offscreen WebView; the main agent tool loop stays the driver, so Kai's
 * existing approval model applies unchanged.
 *
 * Element addressing is snapshot-scoped, mirroring ui_dump: `snapshotJs`
 * numbers the currently interactable elements, and tap/input report live text
 * so the caller verifies the element before trusting the action — tap matches
 * its snapshot hint, input matches the filled value (unless submit navigates
 * away). A changed page fails instead of acting blindly.
 */
object WebActCommand {
    const val MAX_ELEMENTS = 200
    const val MAX_TEXT_CHARS = 120

    /**
     * One shared selector for snapshot/tap/input. They must all number the same
     * collection — an input index observed from a snapshot only means the same
     * element when input re-queries this exact set (form fields are a subset,
     * so a narrower selector would silently shift every index).
     */
    private const val INTERACTIVE_SELECTOR =
        "a[href],button,input,select,textarea,[role=\"button\"],[role=\"link\"],[role=\"checkbox\"],[role=\"switch\"],[role=\"tab\"],[onclick]"

    fun snapshotJs(): String = """
        (function(){
          var els = document.querySelectorAll('$INTERACTIVE_SELECTOR');
          var out = [];
          for (var i = 0; i < els.length && out.length < $MAX_ELEMENTS; i++) {
            var e = els[i];
            var r = e.getBoundingClientRect();
            if (r.width < 2 || r.height < 2) continue;
            var cs = window.getComputedStyle(e);
            if (cs.visibility === 'hidden' || cs.display === 'none') continue;
            var text = (e.innerText || e.value || e.getAttribute('aria-label') || e.getAttribute('placeholder') || e.getAttribute('name') || '').trim().replace(/\s+/g, ' ').slice(0, $MAX_TEXT_CHARS);
            out.push({i: i, tag: e.tagName.toLowerCase(), text: text, type: e.getAttribute('type') || '', href: (e.getAttribute('href') || '').slice(0, 200)});
          }
          return out;
        })()
    """.trimIndent()

    /** Clicks the element at query position [index]; reports its live text for the caller to verify. */
    fun tapJs(index: Int): String = """
        (function(){
          var els = document.querySelectorAll('$INTERACTIVE_SELECTOR');
          var e = els[$index];
          if (!e) return {ok: false, text: ''};
          try { e.scrollIntoView({block: 'center'}); } catch (err) {}
          var text = (e.innerText || e.value || '').trim().replace(/\s+/g, ' ').slice(0, $MAX_TEXT_CHARS);
          e.click();
          return {ok: true, text: text};
        })()
    """.trimIndent()

    /** Fills the element at [index] like a human (focus + input/change events for framework bindings). */
    fun inputJs(index: Int, text: String, submit: Boolean): String {
        val encoded = Json.encodeToString(text)
        return """
        (function(){
          var els = document.querySelectorAll('$INTERACTIVE_SELECTOR');
          var e = els[$index];
          if (!e) return {ok: false, text: '', reason: 'missing'};
          var tag = e.tagName.toLowerCase();
          if (tag !== 'input' && tag !== 'select' && tag !== 'textarea' && e.isContentEditable !== true) {
            return {ok: false, text: '', reason: 'not_fillable'};
          }
          try { e.scrollIntoView({block: 'center'}); } catch (err) {}
          e.focus();
          e.value = $encoded;
          e.dispatchEvent(new Event('input', {bubbles: true}));
          e.dispatchEvent(new Event('change', {bubbles: true}));
          var live = (e.value || '').slice(0, $MAX_TEXT_CHARS);
          ${if (submit) "e.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', keyCode: 13, bubbles: true}));" else ""}
          return {ok: true, text: live};
        })()
        """.trimIndent()
    }

    /**
     * Picks the element that actually scrolls: the document when it overflows,
     * otherwise the element with the largest visible overflow range — feed and
     * carousel layouts often scroll an inner div instead of the window, which
     * `window.scrollBy` silently ignores.
     */
    private const val PICK_SCROLLER_JS =
        "function pickScroller(){var doc=document.scrollingElement||document.documentElement;" +
            "var best=doc,bestRange=doc?doc.scrollHeight-doc.clientHeight:0;" +
            "var all=document.querySelectorAll('div,main,section,article,ul,ol');var lim=Math.min(all.length,1500);" +
            "for(var i=0;i<lim;i++){var e=all[i];var range=e.scrollHeight-e.clientHeight;if(range<=20)continue;" +
            "var oy=window.getComputedStyle(e).overflowY;if(oy!=='auto'&&oy!=='scroll')continue;" +
            "var r=e.getBoundingClientRect();if(r.width<100||r.height<100)continue;" +
            "if(range>bestRange){bestRange=range;best=e;}}return best||doc;}"

    /** Scrolls the real container by [dy] and reports the resulting position. */
    fun scrollJs(dy: Int): String = """
        (function(){
          $PICK_SCROLLER_JS
          try {
            var el = pickScroller();
            var before = el.scrollTop;
            el.scrollBy(0, $dy);
            var maxY = Math.max(0, el.scrollHeight - el.clientHeight);
            var y = Math.max(0, el.scrollTop);
            return {ok: true, y: Math.round(y), maxY: Math.round(maxY), atBottom: y >= maxY - 2, moved: y !== before};
          } catch (e) {
            return {ok: false, y: 0, maxY: 0, atBottom: false, moved: false};
          }
        })()
    """.trimIndent()

    /**
     * Current position of the real scroll container without moving it, so
     * snapshots can tell the agent whether more page exists below.
     */
    fun scrollInfoJs(): String = """
        (function(){
          $PICK_SCROLLER_JS
          try {
            var el = pickScroller();
            var maxY = Math.max(0, el.scrollHeight - el.clientHeight);
            var y = Math.max(0, el.scrollTop);
            return {ok: true, y: Math.round(y), maxY: Math.round(maxY), atBottom: y >= maxY - 2, moved: false};
          } catch (e) {
            return {ok: false, y: 0, maxY: 0, atBottom: false, moved: false};
          }
        })()
    """.trimIndent()

    fun backJs(): String = "(function(){if(history.length>1){history.back();return{ok:true} }return{ok:false}})()"

    data class WebElement(
        val index: Int,
        val tag: String,
        val text: String,
        val type: String,
        val href: String,
    )

    data class WebActionResult(
        val ok: Boolean,
        val text: String,
    )

    data class WebScrollResult(
        val ok: Boolean,
        val y: Int,
        val maxY: Int,
        val atBottom: Boolean,
        val moved: Boolean,
    )

    fun parseSnapshot(body: String): List<WebElement> {
        val rows = try {
            Json.parseToJsonElement(body).jsonArray
        } catch (_: Exception) {
            return emptyList()
        }
        return rows.mapNotNull { element ->
            val row = try {
                element.jsonObject
            } catch (_: Exception) {
                return@mapNotNull null
            }
            fun field(name: String): String = try {
                row[name]?.jsonPrimitive?.content.orEmpty()
            } catch (_: Exception) {
                ""
            }
            val index = field("i").toIntOrNull() ?: return@mapNotNull null
            WebElement(index, field("tag"), field("text"), field("type"), field("href"))
        }
    }

    fun parseScrollResult(body: String): WebScrollResult? {
        val row = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return null
        }
        if (!row.containsKey("ok")) return null
        fun int(name: String): Int = try {
            row[name]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        }

        fun bool(name: String): Boolean = try {
            row[name]?.jsonPrimitive?.content == "true"
        } catch (_: Exception) {
            false
        }
        return WebScrollResult(
            ok = bool("ok"),
            y = int("y"),
            maxY = int("maxY"),
            atBottom = bool("atBottom"),
            moved = bool("moved"),
        )
    }

    fun parseActionResult(body: String): WebActionResult? {
        val row = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return null
        }
        if (!row.containsKey("ok")) return null
        // JsonPrimitive.content stringifies booleans too, so quoted and bare
        // `true` both land here.
        val ok = try {
            row["ok"]?.jsonPrimitive?.content == "true"
        } catch (_: Exception) {
            return null
        }
        val text = try {
            row["text"]?.jsonPrimitive?.content.orEmpty()
        } catch (_: Exception) {
            ""
        }
        return WebActionResult(ok, text)
    }
}
