package com.github.corruptedinc.corruptedmainframe.commands

import com.beust.klaxon.Converter
import com.beust.klaxon.JsonValue
import com.beust.klaxon.Klaxon
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

class Reddit {
    private val klaxon = Klaxon()
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
    private val cache: LoadingCache<String, Posts?> =
        CacheBuilder.newBuilder().expireAfterWrite(Duration.of(1, ChronoUnit.HOURS))
            .maximumSize(SUBREDDIT_CACHE_SIZE)
            .build(object : CacheLoader<String, Posts?>() {
                override fun load(key: String): Posts? {
                    return top(key)
                }
            })

    companion object {
        private const val SUBREDDIT_CACHE_SIZE = 1024L
    }

    init {
        klaxon.converter(object : Converter {
            override fun canConvert(cls: Class<*>): Boolean {
                return cls == SubredditData::class.java
            }

            override fun fromJson(jv: JsonValue): Any? {
                @Suppress("SwallowedException", "TooGenericExceptionCaught", "MagicNumber")
                return try {
                    val j = jv.obj!!.obj("data")!!
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
                throw NotImplementedError()
            }
        })
        klaxon.converter(object : Converter {
            override fun canConvert(cls: Class<*>): Boolean {
                return cls == Posts::class.java
            }

            override fun fromJson(jv: JsonValue): Any? {
                @Suppress("SwallowedException", "TooGenericExceptionCaught", "MagicNumber")
                return try {
                    val j = jv.obj!!.obj("data")!!.array<Any>("children")!!
                    Posts(j.mapChildren {
                        val d = it.obj("data")!!
                        Post(d.string("author")!!,
                            "https://reddit.com" + d.string("permalink")!!, d.string("url")!!,
                            d.boolean("over_18")!!, Instant.ofEpochSecond(d.long("created_utc")!!),
                            d.string("title")!!)
                    }.toList())
                } catch (e: NullPointerException) {
                    null
                }
            }

            override fun toJson(value: Any): String {
                throw NotImplementedError()
            }
        })
    }

    data class SubredditData(val title: String, val subscribers: Long, val description: String,
                             val creationDate: Calendar, val nsfw: Boolean)

    fun subredditData(subreddit: String): SubredditData? {
        val u = URL("https://reddit.com/r/$subreddit/about.json")
        val req = HttpRequest.newBuilder(u.toURI()).GET().build()
        val body = client.send(req, HttpResponse.BodyHandlers.ofString())
        return klaxon.parse<SubredditData>(body.body())
    }

    data class Post(val author: String, val permalink: String, val imgUrl: String, val nsfw: Boolean,
                    val posted: Instant, val title: String)

    data class Posts(val posts: List<Post?>)

    fun top(subreddit: String): Posts? {
        val u = URL("https://reddit.com/r/$subreddit/top.json")

        val req = HttpRequest.newBuilder(u.toURI()).GET().build()
        val body = client.send(req, HttpResponse.BodyHandlers.ofString())
        return klaxon.parse<Posts>(body.body())
    }

    fun random(subreddit: String): Post? {
        val posts = cache[subreddit]
        return posts?.posts?.filterNotNull()?.filter { !it.nsfw }?.randomOrNull()
    }
}
