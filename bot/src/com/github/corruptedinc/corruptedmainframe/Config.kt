package com.github.corruptedinc.corruptedmainframe

import com.beust.klaxon.Klaxon
import java.io.File

class Config(val token: String, val permaAdmins: List<String>, val databaseUrl: String, val databaseDriver: String, val gitUrl: String, val blueAllianceToken: String) {
    companion object {
        fun load(file: File): Config? {
            // TODO: replace klaxon with something else, jackson is seemingly also in the jar
            // TODO: yaml
            return Klaxon().parse<Config>(file)
        }
    }
}
