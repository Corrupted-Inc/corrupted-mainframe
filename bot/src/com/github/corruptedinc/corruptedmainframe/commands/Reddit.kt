package com.github.corruptedinc.corruptedmainframe.commands

import com.beust.klaxon.Converter
import com.beust.klaxon.JsonValue
import com.beust.klaxon.Klaxon
import com.beust.klaxon.Render
import java.util.*

class Reddit {
    private val klaxon = Klaxon()

    init {
        klaxon.converter(object : Converter {
            override fun canConvert(cls: Class<*>): Boolean {
                return cls == SubredditData::class.java
            }

            override fun fromJson(jv: JsonValue): Any? {
                @Suppress("SwallowedException", "TooGenericExceptionCaught", "MagicNumber")
                return try {
                    val j = jv.obj!!
                    SubredditData(
                        j.string("title")!!, j.long("subscribers")!!,
                        j.string("public_description")!!, Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                            .apply { timeInMillis = (j.float("created")!! * 1000).toLong() },
                        j.boolean("over18")!!
                    )
                } catch (e: NullPointerException) {
                    null
                }
            }

            override fun toJson(value: Any): String {
                require(value is SubredditData)

                return "{\"title\": ${Render.escapeString(value.title)}, \"subscribers\": ${value.subscribers}," +
                        "\"public_description\": \"${Render.escapeString(value.description)}\"," +
                        "\"created\": ${value.creationDate.timeInMillis / 1000}, \"over18\": ${value.nsfw}}"
            }
        })
    }

    data class SubredditData(val title: String, val subscribers: Long, val description: String,
                             val creationDate: Calendar, val nsfw: Boolean)
}
