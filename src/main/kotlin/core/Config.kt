package core

import com.beust.klaxon.Klaxon
import java.io.File

class Config(val token: String) {
    companion object {
        fun load(file: File): Config? {
            return Klaxon().parse<Config>(file)
        }
    }

    override fun toString(): String {
        return "Config{token=***}"
    }
}
