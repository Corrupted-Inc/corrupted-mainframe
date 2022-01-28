package com.github.corruptedinc.corruptedmainframe.commands

import com.beust.klaxon.Json
import com.beust.klaxon.Klaxon
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import com.github.corruptedinc.corruptedmainframe.utils.containsAny
import com.github.corruptedinc.corruptedmainframe.utils.levenshtein
import kotlinx.coroutines.*
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import java.net.URI
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.max

class TheBlueAlliance(private val token: String, private val scope: CoroutineScope) {
    private val client = HttpClientBuilder.create().build()
    private val klaxon = Klaxon()

    private inline fun <reified T> get(path: String): T? {
        val req = HttpGet()
        req.addHeader("X-TBA-Auth-Key", token)
        req.uri = URI.create("https://www.thebluealliance.com/api/v3$path")

        val output = client.execute(req)
        if (output.statusLine.statusCode / 100 !in 2..3) return null  // if it isn't a success/redirect, return null
        val str = output.entity.content.readAllBytes().decodeToString()
        output.close()
        return synchronized(klaxon) { try { klaxon.parse(str) } catch (e: Exception) { null } }
    }

    private inline fun <reified T> getArray(path: String): List<T>? {
        val req = HttpGet()
        req.addHeader("X-TBA-Auth-Key", token)
        req.uri = URI.create("https://www.thebluealliance.com/api/v3$path")

        val output = client.execute(req)
        if (output.statusLine.statusCode / 100 !in 2..3) return null  // if it isn't a success/redirect, return null
        val str = output.entity.content.readAllBytes().decodeToString()
        output.close()
        return synchronized(klaxon) { try { klaxon.parseArray(str) } catch (e: Exception) { null } }
    }

    data class TeamInfo(
        val key: String,
        @Json("team_number") val teamNumber: Int,
        val name: String,
        val nickname: String?,
        @Json("school_name") val school: String?,
        val city: String?,
        val country: String?,
        val website: String?,
        @Json("rookie_year") val rookieYear: Int?
    )

    fun teamInfo(teamNumber: Int) = get<TeamInfo>("/team/frc$teamNumber")

    data class TeamEventStatus(
        val qual: TeamEventStatusRank? = null,
        val playoff: Playoff? = null,
        @Json("alliance_status_str") val allianceStatusString: String? = null,
        @Json("playoff_status_str") val playoffStatusString: String? = null,
        @Json("overall_status_str") val overallStatusString: String? = null
    ) {
        data class TeamEventStatusRank(
            @Json("num_teams") val numTeams: Int?,
            val ranking: Ranking?,
            val status: String?,
        )

        data class Playoff(
            val level: String?,
            @Json("current_level_record") val currentLevelRecord: Record?,
            val record: Record?,
            val status: String?,
            @Json("playoff_average") val playoffAverage: Int?
        )

        data class Record(
            val losses: Int,
            val wins: Int,
            val ties: Int
        )

        data class Ranking(
            @Json("matches_played") val matchesPlayed: Int?,
            @Json("qual_average") val qualAverage: Double?,
            val record: Record?,
            val rank: Int?,
            val dq: Int?
        )
    }

    fun teamEventStatus(teamNumber: Int, eventKey: String) =
        get<TeamEventStatus>("/team/frc$teamNumber/event/$eventKey/status")

    data class District(
        val abbreviation: String,
        @Json("display_name") val displayName: String,
        val key: String,
        val year: Int
    )

    fun teamDistricts(teamNumber: Int) =
        getArray<District>("/team/frc$teamNumber/districts")

    private fun simpleEvents(year: Int) =
        getArray<SimpleEvent>("/events/$year/simple")

    private val simpleEventCache: LoadingCache<Int, List<SimpleEvent>> = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build {
            simpleEvents(it) ?: emptyList()
        }

    private val eventCache: LoadingCache<String, Event> = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build {
            event(it)!!
        }

    data class SimpleEvent(
        val key: String,
        val name: String,
        @Json("event_code") val eventCode: String,
        @Json("event_type") val eventType: Int?,
        val district: District?,
        val city: String?,
        @Json("state_prov") val stateProv: String?,
        val country: String?,
        val startDate: String? = null,
        val endDate: String? = null,
        val year: Int?
    )

    fun simpleEvent(key: String) =
        get<SimpleEvent>("/event/$key")

    fun event(key: String) =
        get<Event>("/event/$key")

    fun matches(teamNumber: Int, event: String) =
        getArray<Match>("/team/frc$teamNumber/event/$event/matches")

    private fun biasedLevenshtein(a: String, b: String): Double {
        var l = levenshtein(a, b).toDouble() / max(a.length, b.length)

        if (b.contains(a) || a.contains(b)) l /= 8
        if (b.contains("\\b$a\\b".toRegex()) || a.contains("\\b$b\\b".toRegex())) l /= 16

        return l
    }

    fun simpleEventByName(name: String, year: Int): SimpleEvent? {
        val matchingYear = simpleEventCache[year] ?: emptyList()
        val abbreviated = matchingYear.minByOrNull { biasedLevenshtein(it.name.lowercase(), name.lowercase()) }
        val full = matchingYear.minByOrNull { biasedLevenshtein(it.key.lowercase(), name.lowercase()) }

        return if (((abbreviated?.run { biasedLevenshtein(this.name.lowercase(), name.lowercase()) }) ?: 1000.0) < (full?.run { biasedLevenshtein(key.lowercase(), name.lowercase()) } ?: 1000.0)) {
            abbreviated
        } else {
            full
        }
    }

    fun eventByName(name: String, year: Int): Event? {
        val replaced = name.lowercase()
            .replace("\\bchs\\b".toRegex(), "chesapeake")
            .replace("\\bfim\\b".toRegex(), "michigan")
            .replace("\\btx\\b".toRegex(), "texas")
            .replace("\\bin\\b".toRegex(), "indiana")
            .replace("\\bisr\\b".toRegex(), "israel")
            .replace("\\bfma\\b".toRegex(), "mid-atlantic")
            .replace("\\bfnc\\b".toRegex(), "north carolina")
            .replace("\\bne\\b".toRegex(), "new england")
            .replace("\\bont\\b".toRegex(), "ontario")
            .replace("\\bpnw\\b".toRegex(), "pacific northwest")
            .replace("\\bpch\\b".toRegex(), "peachtree")
            .replace("\\bdistrict champs\\b".toRegex(), "district championships")
            .replace("\\bdcmps?\\b".toRegex(), "district championships")
        val simple = simpleEventByName(replaced, year) ?: return null
        return eventCache[simple.key]
    }

    data class Event(
        val key: String,
        val name: String,
        @Json("event_code") val eventCode: String,
        @Json("event_type") val eventType: Int?,
        val district: District?,
        val city: String?,
        @Json("state_prov") val stateProv: String?,
        val country: String?,
        @Json("start_date") val startDate: String,
        @Json("end_date") val endDate: String,
        val year: Int?,
        @Json("short_name") val shortName: String? = null,
        @Json("event_type_string") val eventTypeString: String? = null,
        val week: Int? = null,
        val address: String? = null,
        @Json("location_name") val locationName: String? = null,
        val timezone: String? = null,
        val website: String? = null,
        val webcasts: List<Webcast>? = null,
        @Json("division_keys") val divisionKeys: List<String>? = null,
        @Json("parent_event_key") val parentEventKey: String? = null,
        @Json("playoff_type") val playoffType: Int? = null,
        @Json("playoff_type_string") val playoffTypeString: String? = null
    ) {
        data class Webcast(
            val type: String,
            val channel: String,
            val date: String? = null,
            val file: String? = null
        )
    }

    data class Match(
        val key: String,
        @Json("comp_level") val compLevel: String,
        @Json("set_number") val setNumber: Int,
        @Json("match_number") val matchNumber: Int,
        val alliances: Alliances? = null,
        @Json("winning_alliance") val winningAlliance: String? = null,
        @Json("event_key") val eventKey: String,
        val time: Long? = null,
        @Json("actual_time") val actualTime: Long? = null,
        @Json("predicted_time") val predictedTime: Long? = null,
        @Json("post_result_time") val postResultTime: Long? = null,
    ) {
        data class Alliances(
            val red: Alliance,
            val blue: Alliance
        )

        data class Alliance(
            val score: Int,
            @Json("team_keys") val teamKeys: List<String>
        )
    }
}
