package com.github.corruptedinc.corruptedmainframe.plugin

import com.beust.klaxon.Klaxon
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import java.io.File
import java.net.URLClassLoader

class PluginLoader(private val pluginDir: String, private val bot: Bot) {

    fun loadPlugins() {
        val klaxon = Klaxon()
        for (file in File(pluginDir).listFiles { _, name -> name.endsWith(".jar") } ?: emptyArray()) {
            val loader = URLClassLoader(arrayOf(file.toURI().toURL()))

            val metaFile = loader.getResourceAsStream("plugin.json")?.readAllBytes()?.decodeToString() ?: run { bot.log.error("Failed to load plugin '${file.name}', missing plugin.json!"); return }
            val metadata = klaxon.parse<PluginMetadata>(metaFile) ?: run { bot.log.error("Plugin '${file.name}' doesn't contain a valid plugin.json!"); return }
        }
    }
}
