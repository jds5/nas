package org.rokano.nasremote.security

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** No transcript or passphrase. Optional drafts plus exact executor and reading bookmarks. */
data class SessionMemo(
    val profile: String, val pane: String, val binding: String, val title: String,
    val draft: String = "", val anchor: String = "", val anchorOffset: Int = 0,
    val anchorByte: Long = 0, val seen: String = "", val uncertain: Boolean = false,
    val delivery: String = "", val answerDraft: String = "", val answerQuestion: String = "",
)
data class SavedSessions(val saveDrafts: Boolean = false, val entries: List<SessionMemo> = emptyList())

class SessionVault(context: Context) {
    private val record = ProfileVault(context, "sessions.v1", "nas-remote-sessions-v1", 2_097_152)
    fun load(): SavedSessions {
        val bytes = record.readRecord() ?: return SavedSessions()
        try {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            val enabled = root.optBoolean("saveDrafts")
            val array = root.optJSONArray("entries") ?: JSONArray()
            val entries = (0 until minOf(array.length(), 8)).map { i -> array.getJSONObject(i).let { j ->
                SessionMemo(j.getString("profile"), j.getString("pane"), j.getString("binding"), j.optString("title").take(160),
                    if (enabled) j.optString("draft").take(16384) else "", j.optString("anchor").take(128),
                    j.optInt("anchorOffset").coerceIn(0, 100000), j.optLong("anchorByte").coerceAtLeast(0),
                    j.optString("seen").take(128), j.optBoolean("uncertain"), j.optString("delivery").take(64),
                    if (enabled) j.optString("answerDraft").take(16384) else "", j.optString("answerQuestion").take(128))
            } }
            return SavedSessions(enabled, entries)
        } finally { bytes.fill(0) }
    }
    fun save(state: SavedSessions) {
        val entries = JSONArray()
        state.entries.takeLast(8).forEach { m ->
            entries.put(JSONObject().put("profile", m.profile).put("pane", m.pane).put("binding", m.binding).put("title", m.title)
                .put("draft", if (state.saveDrafts) m.draft else "").put("anchor", m.anchor).put("anchorOffset", m.anchorOffset)
                .put("anchorByte", m.anchorByte).put("seen", m.seen).put("uncertain", m.uncertain).put("delivery", m.delivery)
                .put("answerDraft", if (state.saveDrafts) m.answerDraft else "").put("answerQuestion", m.answerQuestion))
        }
        val bytes = JSONObject().put("saveDrafts", state.saveDrafts).put("entries", entries).toString().toByteArray()
        try { record.writeRecord(bytes) } finally { bytes.fill(0) }
    }
    fun delete() = record.delete()
}
