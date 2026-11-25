package com.expressive.keyboard

import org.json.JSONObject

data class SettingsLayoutSpec(
    val order: List<SettingsPage>,
    val pinned: List<SettingsPage>,
    val hidden: Set<SettingsPage>
) {
    fun arrange(pages: List<SettingsPage>): List<SettingsPage> = pages
        .filter { it !in hidden && it !in pinned }
        .sortedBy { page -> order.indexOf(page).takeIf { it >= 0 } ?: Int.MAX_VALUE }

    companion object {
        private val required = setOf(SettingsPage.PLUGINS, SettingsPage.BACKUP, SettingsPage.ABOUT)

        fun parse(json: JSONObject?): SettingsLayoutSpec? {
            if (json == null) return null
            fun pages(key: String): List<SettingsPage> {
                val values = json.optJSONArray(key) ?: return emptyList()
                return (0 until minOf(values.length(), 40)).mapNotNull { index ->
                    val name = values.optString(index).trim().replace('-', '_').replace(' ', '_')
                    SettingsPage.entries.firstOrNull { it != SettingsPage.HOME && it.name.equals(name, ignoreCase = true) }
                }.distinct()
            }
            val hidden = pages("hidden").filterNot { it in required }.toSet()
            val pinned = pages("pinned").filterNot { it in hidden }
            val order = pages("order")
            if (order.isEmpty() && pinned.isEmpty() && hidden.isEmpty()) return null
            return SettingsLayoutSpec(order, pinned, hidden)
        }
    }
}

data class ToolbarLayoutSpec(val order: List<String>, val hidden: Set<String>) {
    fun arrange(available: List<String>, userOrder: List<String>, userHidden: Set<String>): List<String> {
        val first = order.filter { it in available }
        val rest = Toolbar.arrange(available, userOrder, emptySet(), includeHidden = true)
        return (first + rest.filterNot { it in first }).filterNot { it in hidden || it in userHidden }
    }

    companion object {
        fun parse(json: JSONObject?, pluginId: String): ToolbarLayoutSpec? {
            if (json == null) return null
            fun ids(key: String): List<String> {
                val values = json.optJSONArray(key) ?: return emptyList()
                return (0 until minOf(values.length(), 60)).mapNotNull { index ->
                    val id = values.optString(index).trim()
                    when {
                        id.isEmpty() -> null
                        id.startsWith("button:") -> "btn:$pluginId:" + id.removePrefix("button:")
                        else -> id
                    }
                }.distinct()
            }
            val order = ids("order")
            val hidden = ids("hidden").toSet() - setOf("plugins", "settings")
            if (order.isEmpty() && hidden.isEmpty()) return null
            return ToolbarLayoutSpec(order, hidden)
        }
    }
}
