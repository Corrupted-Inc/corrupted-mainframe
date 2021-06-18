package core

import com.beust.klaxon.Klaxon
import java.io.File

class Config(val token: String, val permaAdmins: List<String>, val databaseUrl: String, val databaseDriver: String) {
    companion object {
        fun load(file: File): Config? {
            return Klaxon().parse<Config>(file)
        }
    }
}
