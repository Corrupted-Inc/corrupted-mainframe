package com.github.corruptedinc.corruptedmainframe

import com.beust.klaxon.JsonParsingException
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import java.io.File

fun main() {
    val config = Config.load(File("config.json")) // Loads the configuration settings from config.json
    config ?: throw JsonParsingException("Failed to load configuration!") // Checks if the loading failed.

    // Starts the bot
    Bot(config)
}
