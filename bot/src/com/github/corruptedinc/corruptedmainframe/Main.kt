package com.github.corruptedinc.corruptedmainframe

import com.github.corruptedinc.corruptedmainframe.discord.Bot
import java.io.File
import java.text.ParseException

fun main() {
    val config = Config.load(File("config.json"))
    config ?: throw ParseException("Failed to load configuration!")
    Bot(config)
}
