package com.github.corruptedinc.corruptedmainframe

import com.beust.klaxon.JsonParsingException
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import org.apache.log4j.BasicConfigurator
import org.apache.log4j.Level
import org.apache.log4j.Logger
import java.io.File

fun main() {
    BasicConfigurator.configure()  // Fixes log4j warning
    Logger.getRootLogger().level = Level.INFO

    val config = Config.load(File("config.json"))
    config ?: throw JsonParsingException("Failed to load configuration!")
    Bot(config)
}
