package com.github.corruptedinc.corruptedmainframe

import com.github.corruptedinc.corruptedmainframe.discord.Bot
import java.io.File

fun main() {
    val config = Config.load(File("config.json"))
    config ?: throw RuntimeException("Failed to load configuration!")
    Bot(config)
}
