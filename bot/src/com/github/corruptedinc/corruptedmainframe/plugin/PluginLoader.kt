package com.github.corruptedinc.corruptedmainframe.plugin

import com.beust.klaxon.Klaxon
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import net.dv8tion.jda.api.JDABuilder
import java.io.File
import java.net.URLClassLoader
import java.util.zip.ZipFile

class PluginLoader(private val pluginDir: File, private val bot: Bot) {
    private val plugins: MutableList<Pair<Plugin, PluginMetadata>> = mutableListOf()

    init {
        val klaxon = Klaxon()
        for (file in pluginDir.listFiles { _, name -> name.endsWith(".jar") } ?: emptyArray()) {
            val name = file.name
            val loader = URLClassLoader(arrayOf(file.toURI().toURL()), ClassLoader.getSystemClassLoader())
            val zip = ZipFile(file)

            val metaFile = zip.getEntry("plugin.json")?.let { zip.getInputStream(it) }?.readAllBytes()?.decodeToString()
            if (metaFile == null) {
                bot.log.error("Failed to load plugin '$name', missing plugin.json!")
                continue
            }
            val metadata = klaxon.parse<PluginMetadata>(metaFile)
            if (metadata == null) {
                bot.log.error("Plugin '$name' doesn't contain a valid plugin.json!")
                continue
            }

            @Suppress("SwallowedException")  // it isn't, I promise
            try {
                val cls = loader.loadClass(metadata.pluginClassName)
                val constructor = cls.getConstructor()
                val instance = constructor.newInstance() as Plugin
                plugins.add(instance to metadata)
            } catch (e: ClassNotFoundException) {
                bot.log.error("Failed to load class '${metadata.pluginClassName}' from plugin '$name'!")
            } catch (e: ClassCastException) {
                bot.log.error("Class '${metadata.pluginClassName}' from plugin '$name' doesn't implement Plugin!")
            }
        }
        if (plugins.isNotEmpty()) bot.log.info("Loaded plugins: ${plugins.joinToString { it.second.name }}")
    }

    fun loadPlugins() {
        for (p in plugins) {
            @Suppress("TooGenericExceptionCaught")
            try {
                p.first.load(bot)
                p.first.registerCommands(bot.commands::register)
            } catch (e: Exception) {
                bot.log.error("Exception while loading plugin '${p.second.name}':\n${e.stackTraceToString()}")
                continue
            }
        }
    }

    fun applyToJDA(builder: JDABuilder) {
        for (p in plugins) {
            p.first.jdaInit(builder)
        }
    }
}
