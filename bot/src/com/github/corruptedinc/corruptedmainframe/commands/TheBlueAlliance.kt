package com.github.corruptedinc.corruptedmainframe.commands

import com.beust.klaxon.*
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import com.github.corruptedinc.corruptedmainframe.Config
import com.github.corruptedinc.corruptedmainframe.utils.levenshtein
import kotlinx.coroutines.*
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

// TODO: coroutines
class TheBlueAlliance(private val token: String, private val scope: CoroutineScope) {
    private val client = HttpClientBuilder.create().build()
    val klaxon = Klaxon()

    private inline fun <reified T> get(path: String): T? {
        val req = HttpGet(URI.create("https://www.thebluealliance.com/api/v3$path"))
        req.addHeader("X-TBA-Auth-Key", token)

        return client.execute(req) { output ->
            if (output.code / 100 !in 2..3) return@execute null  // if it isn't a success/redirect, return null
            val str = output.entity.content.readAllBytes().decodeToString()
            output.close()
            synchronized(klaxon) { try { klaxon.parse(str) } catch (e: Exception) { null } }
        }
    }

    private inline fun <reified T> getArray(path: String): List<T>? {
        val req = HttpGet(URI.create("https://www.thebluealliance.com/api/v3$path"))
        req.addHeader("X-TBA-Auth-Key", token)

        return client.execute(req) { output ->
            if (output.code / 100 !in 2..3) return@execute null  // if it isn't a success/redirect, return null
            val str = output.entity.content.readAllBytes().decodeToString()
            output.close()
            synchronized(klaxon) { try { klaxon.parseArray(str) } catch (e: Exception) { null } }
        }
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

    fun matches(teamNumber: Int, eventKey: String) =
        getArray<Match>("/team/frc$teamNumber/event/$eventKey/matches")

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

    fun autocompleteEventName(name: String, year: Int): List<String> {
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
            .replace("\\bgpk\\b".toRegex(), "glacier peak")

        val matchingYear = simpleEventCache[year] ?: emptyList()
        val abbreviated = matchingYear.sortedBy { biasedLevenshtein(it.name.lowercase(), replaced) }
        val full = matchingYear.find { levenshtein(it.key.lowercase(), replaced) <= 1 }

        val output = mutableListOf<String>()
        if (full != null) output.add(full.name)

        output.addAll(abbreviated.take(3).map { it.name })

        return output
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
            .replace("\\bgpk\\b".toRegex(), "glacier peak")
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
        // breaks for non-2022 years
//        @Json("score_breakdown") val scoreBreakdown: MatchScoreBreakdown2022? = null
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

    fun events(teamNumber: Int, year: Int) =
        getArray<String>("/team/frc$teamNumber/events/$year/keys")?.mapNotNull { event(it) } ?: emptyList()

    data class MatchScoreBreakdown2022(
        val red: MatchScoreBreakdown2022Alliance,
        val blue: MatchScoreBreakdown2022Alliance
    )

    data class MatchScoreBreakdown2022Alliance(
        val taxiRobot1: String,  // enum, either "Yes" or "No"
        val endgameRobot1: String,  // enum, "Traversal", "High", "Mid", "Low", "None"
        val taxiRobot2: String,  // enum, either "Yes" or "No"
        val endgameRobot2: String,  // enum, "Traversal", "High", "Mid", "Low", "None"
        val taxiRobot3: String,  // enum, either "Yes" or "No"
        val endgameRobot3: String,  // enum, "Traversal", "High", "Mid", "Low", "None"

        // AUTO
        // lower
        val autoCargoLowerNear: Int,
        val autoCargoLowerFar: Int,

        val autoCargoLowerBlue: Int,
        val autoCargoLowerRed: Int,

        // upper
        val autoCargoUpperNear: Int,
        val autoCargoUpperFar: Int,

        val autoCargoUpperBlue: Int,
        val autoCargoUpperRed: Int,

        val autoCargoTotal: Int,

        // TELEOP
        val teleopCargoLowerNear: Int,
        val teleopCargoLowerFar: Int,

        val teleopCargoLowerBlue: Int,
        val teleopCargoLowerRed: Int,

        val teleopCargoUpperNear: Int,
        val teleopCargoUpperFar: Int,

        val teleopCargoUpperBlue: Int,
        val teleopCargoUpperRed: Int,

        val teleopCargoTotal: Int,

        val matchCargoTotal: Int,
        val autoTaxiPoints: Int,
        val autoCargoPoints: Int,
        val autoPoints: Int,
        val quintetAchieved: Boolean,
        val teleopCargoPoints: Int,
        val endgamePoints: Int,
        val teleopPoints: Int,
        val cargoBonusRankingPoint: Boolean,
        val hangarBonusRankingPoint: Boolean,
        val foulCount: Int,
        val techFoulCount: Int,
        val adjustPoints: Int,
        val foulPoints: Int,
        val rp: Int,
        val totalPoints: Int
    )

    data class Zebra(
        val key: String,
        val times: List<Double>,
        val alliances: RedBlueZebra
    ) {
        data class RedBlueZebra(val red: List<ZebraTeam>, val blue: List<ZebraTeam>)

        data class ZebraTeam(
            @Json("team_key") val teamKey: String,
            val xs: List<Double?>,
            val ys: List<Double?>
        )

        fun speeds(team: ZebraTeam) = times.zip(team.xs.filterNotNull().zip(team.ys.filterNotNull())).zipWithNext().map { (prev, current) -> (sqrt((current.second.first - prev.second.first).pow(2) + (current.second.second - prev.second.second).pow(2))) / (current.first - prev.first) }

        fun accelerations(team: ZebraTeam) = times.zip(speeds(team)).zipWithNext().map { (prev, current) -> (current.second - prev.second).absoluteValue / (current.first - prev.first) }
    }

    fun zebra(match: Match) = get<Zebra>("/match/${match.key}/zebra_motionworks")
}

fun main() {
    val config = Config.load(File("config.json"))!!
    val tba = TheBlueAlliance(config.blueAllianceToken, CoroutineScope(Dispatchers.Default))
    val match = tba.matches(2910, "2023pncmp")!!.find { it.key == "2023pncmp_qm50" }!!
    val data = tba.zebra(match)!!
//    println("data: $data")
    val v = data.alliances.blue[1]
    val speeds = data.speeds(v)
    println("team: ${v.teamKey}")
    println("speeds: $speeds")
    println("Speeds:")
    println("min: ${speeds.min()} avg: ${speeds.average()} median: ${speeds.sorted()[speeds.size / 2]} max: ${speeds.max()}")

    val accelerations = data.accelerations(v)
    println("accelerations: ${accelerations.joinToString { "%.4f".format(it) }}")
    println("Accelerations:")
    println("min: ${accelerations.min()} avg: ${accelerations.average()} median: ${accelerations.sorted()[accelerations.size / 2]} max: ${accelerations.max()}")

    println(data.times)

//    println(tba.simpleEvent("2023orore"))
}
