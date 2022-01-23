package com.github.corruptedinc.corruptedmainframe.plugin

import com.beust.klaxon.Klaxon
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import java.io.File
import java.lang.ClassCastException
import java.net.URLClassLoader

class PluginLoader(private val pluginDir: File, private val bot: Bot) {
    private val plugins: MutableList<Pair<Plugin, PluginMetadata>> = mutableListOf()

    fun loadPlugins() {
        val klaxon = Klaxon()
        for (file in pluginDir.listFiles { _, name -> name.endsWith(".jar") } ?: emptyArray()) {
            val name = file.name
            val loader = URLClassLoader(arrayOf(file.toURI().toURL()), ClassLoader.getSystemClassLoader())

            val metaFile = loader.getResourceAsStream("plugin.json")?.readAllBytes()?.decodeToString()
                ?: run { bot.log.error("Failed to load plugin '$name', missing plugin.json!"); return }
            val metadata = klaxon.parse<PluginMetadata>(metaFile)
                ?: run { bot.log.error("Plugin '$name' doesn't contain a valid plugin.json!"); return }

            @Suppress("SwallowedException")  // it isn't, I promise
            try {
                val cls = loader.loadClass(metadata.pluginClassName)
                val constructor = cls.getConstructor()
                val instance = constructor.newInstance() as Plugin
                @Suppress("TooGenericExceptionCaught")
                try {
                    instance.load()
                    instance.registerCommands(bot.commands::register)
                } catch (e: Exception) {
                    bot.log.error("Exception while loading plugin '$name':\n${e.stackTraceToString()}")
                    continue
                }
                plugins.add(instance to metadata)
            } catch (e: ClassNotFoundException) {
                bot.log.error("Failed to load class '${metadata.pluginClassName}' from plugin '$name'!")
            } catch (e: ClassCastException) {
                bot.log.error("Class '${metadata.pluginClassName}' from plugin '$name' doesn't implement Plugin!")
            }
        }
        bot.log.info("Loaded plugins: ${plugins.joinToString { it.second.name }}")
    }
}
