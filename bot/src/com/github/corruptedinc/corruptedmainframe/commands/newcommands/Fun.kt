package com.github.corruptedinc.corruptedmainframe.commands.newcommands

import com.github.corruptedinc.corruptedmainframe.annotations.Command
import com.github.corruptedinc.corruptedmainframe.annotations.Param
import com.github.corruptedinc.corruptedmainframe.commands.CommandException
import com.github.corruptedinc.corruptedmainframe.commands.Markov
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import com.github.corruptedinc.corruptedmainframe.utils.CmdCtx
import com.github.corruptedinc.corruptedmainframe.utils.ephemeral
import dev.minn.jda.ktx.coroutines.await
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import org.intellij.lang.annotations.Language
import org.jetbrains.exposed.sql.and
import java.security.SecureRandom
import java.time.Instant
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

object Fun {
    class SlotMachine(val cost: Double, val nWheels: Int, val symbols: List<Symbol>) {
        private val rand = SecureRandom()

        data class Symbol(val c: String, val tier: Int, var weight: Double, var value: Double) {
            override fun toString(): String {
                return c
            }
        }

        fun test(): Double {
            val runs = 1_000_000
            var winnings = 0.0
            repeat(runs) {
                winnings += run().winnings
            }
            return winnings / (runs * cost)
        }

        fun test2(): Double {
            val runs = 1_000_000
            val betMul = 100
            var xp = 1e12
            repeat(runs) {
                xp -= cost * betMul
                xp += run().winnings * betMul
            }
            return (xp - 1e12) / runs
        }

        sealed interface PrizeCondition {
            val winnings: Double
        }

        data class Match(val symbols: List<Pair<Symbol, Int>>, override val winnings: Double) : PrizeCondition

        data class Run(val symbols: List<Symbol>, val conditions: List<PrizeCondition>) {
            val winnings = conditions.sumOf { it.winnings }
        }

        fun run(): Run {
            val wheels = (0 until nWheels).map { weightedSelection() }
            val matches = mutableListOf<PrizeCondition>()
            matches.addAll(symbols.mapNotNull {
                val count = wheels.count { v -> v == it }
                if (count <= 1) return@mapNotNull null
                return@mapNotNull Match(wheels.zip(wheels.indices).filter { v -> v.first == it }, count * it.value)
            })
            return Run(wheels, matches)
        }

        private fun weightedSelection(): Symbol {
            val shuffled = symbols.shuffled(rand)
            val weights = shuffled.sumOf { it.weight }
            val num = rand.nextDouble() * weights
            var n = shuffled[0].weight
            var i = 0
            while (n < num) {
                n += shuffled[++i].weight
            }
            return shuffled[i]
        }
    }

    val symbols = listOf(
        SlotMachine.Symbol(":apple:", 0, 1.0, 2.0),
        SlotMachine.Symbol(":pear:", 0, 1.0, 2.0),
        SlotMachine.Symbol(":lemon:", 0, 1.0, 2.0),
        SlotMachine.Symbol(":banana:", 0, 1.0, 2.0),
        SlotMachine.Symbol(":cherries:", 0, 1.0, 2.0),
        SlotMachine.Symbol(":green_apple:", 0, 1.0, 2.0),

        SlotMachine.Symbol(":spades:", 1, 0.75, 8.0),
        SlotMachine.Symbol(":seven:", 1, 0.75, 8.0),
        SlotMachine.Symbol(":white_square_button:", 1, 0.75, 8.0),
        SlotMachine.Symbol(":diamonds:", 1, 0.75, 8.0),

        SlotMachine.Symbol(":star:", 2, 0.05, 64.0),
        SlotMachine.Symbol(":black_joker:", 2, 0.05, 64.0),
        SlotMachine.Symbol(":fleur_de_lis:", 2, 0.05, 64.0)
    )

    val machine = SlotMachine(5.0, 4, symbols)

    @Command("slots", "Play the slots! Costs 5 XP to play by default", global = false)
    suspend inline fun CmdCtx.slots(@Param("wager", "The amount of XP to wager") wager: Double?) {
        val multiplier = (wager ?: machine.cost) / machine.cost
        if (multiplier < 0.1) throw CommandException("Must bet at least ${machine.cost / 10} XP!")
        if (multiplier > 5000.0) throw CommandException("The maximum bet is 5000 XP!")
        val result = machine.run()
        val indices = result.symbols.map { symbols.indexOf(it) }
        val output = StringBuilder()
        for (j in -1..1) {
            if (j == 0) output.append("`  >`") else output.append("`   `")
            for (i in indices) {
                output.append(symbols[((i + j) % symbols.size).absoluteValue].c)
            }
            if (j == 0) output.append("`<`")
            output.append('\n')
        }
        for (w in result.conditions) {
            output.appendLine(when (w) {
                is SlotMachine.Match -> "${w.symbols.size}x ${w.symbols.first().first}"
            } + " (+${(w.winnings * multiplier).roundToInt()} XP)")
        }
        if (result.winnings == 0.0) {
            output.appendLine(
                listOf(
                    "Better luck next time",
                    "Give it another spin!",
                    "So close!"
                ).random()
            )
        } else {
            output.appendLine("**You won ${result.winnings * multiplier} XP!**")
        }

        bot.database.trnsctn {
            val g = event.guild!!.m
            val u = event.user.m
            val points = bot.database.pts(u, g)
            if (points.points < machine.cost * multiplier) throw CommandException("Not enough XP to play!")

            ExposedDatabase.SlotRun.new {
                this.points = points
                this.bet = wager ?: 5.0
                this.winnings = result.winnings * multiplier
                this.results = result.symbols.joinToString { it.c }
                this.timestamp = Instant.now()
            }

            points.points += (result.winnings - machine.cost) * multiplier
        }

        event.replyEmbeds(embed("Slots", description = output.toString())).await()
    }

    @Command("slotstats", "Check your lifetime /slots winnings", global = false)
    suspend inline fun CmdCtx.slotsstats(@Param("user", "The user to check the stats for") target: User?) {
        val u = event.guild!!.retrieveMemberById((target ?: event.user).idLong).await() ?: throw CommandException("Member not found!")
        var winnings = 0.0
        var count = 0L
        var avgWinnings = 0.0
        var avgBet = 0.0
        bot.database.trnsctn {
            val pts = bot.database.pts(u.user.m, u.guild.m)

            winnings = pts.slots.sumOf { it.winnings - it.bet }
            count = pts.slots.count()
            avgWinnings = winnings / count
            avgBet = pts.slots.sumOf { it.bet } / count
        }

        event.replyEmbeds(embed("Slot Stats", description = "${u.asMention} has played the slots $count times, betting on average $avgBet and winning on average $avgWinnings.  Their lifetime winnings are $winnings xp.", stripPings = false)).await()
    }


        @Command("pickupline", "Generate a pickup line", global = true)
    suspend inline fun CmdCtx.pickupline(@Param("coherence", "0-100%, default 100%.") coherence: Double?,
                                         @Param("count", "1-50, default 1") count: Int?) {
        val cnt = (count ?: 1).coerceIn(1..50)
        if (event.guild?.idLong == 292134570940301312) {
            throw CommandException("/pickupline cannot in this server.  It's still usable in DMs or other servers (use /invite)")
        }
        val c = coherence ?: 100.0
        if (c !in 0.0..100.0) throw CommandException("Coherence must be between 0 and 100!")
        val msg = (0 until cnt).joinToString("\n") { Markov.generateFull(c) }
        event.reply(msg).await()
    }

    @Command("submitpickupline", "Submit a pickup line to the corpus")
    suspend inline fun CmdCtx.submitpickupline(@Param("line", "The pickup line") line: String) {
        if (line.length > 255) throw CommandException("Pickup line must be 255 characters or less!")
        bot.database.trnsctn {
            ExposedDatabase.SubmittedPickupLine.new {
                this.user = event.user.m
                this.guild = event.guild!!.m
                this.timestamp = Instant.now()
                this.line = line
            }
        }
        event.replyEmbeds(embed("Submitted", description = "Your pickup line has been submitted to the corpus")).ephemeral().await()
    }
}

fun main() {
    println(Fun.machine.test2())
}
