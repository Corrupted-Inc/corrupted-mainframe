package com.github.corruptedinc.corruptedmainframe

import com.beust.klaxon.Klaxon
import java.io.File

class Config(val token: String, val permaAdmins: List<String>, val databaseUrl: String, val databaseDriver: String,
             val gitUrl: String, val blueAllianceToken: String) {
    companion object {
        fun load(file: File): Config? {
            return Klaxon().parse<Config>(file)
        }
    }
}
