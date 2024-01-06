package com.github.corruptedinc.corruptedmainframe.commands.fights

import com.github.corruptedinc.corruptedmainframe.annotations.Command
import com.github.corruptedinc.corruptedmainframe.annotations.P
import com.github.corruptedinc.corruptedmainframe.commands.CommandException
import com.github.corruptedinc.corruptedmainframe.commands.Commands
import com.github.corruptedinc.corruptedmainframe.commands.Leveling
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.CmdCtx
import dev.minn.jda.ktx.coroutines.await
import kotlinx.coroutines.delay
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.commands.CommandInteraction
import net.dv8tion.jda.api.interactions.commands.OptionType
import org.jetbrains.exposed.sql.and
import java.security.SecureRandom
import java.time.Instant
import kotlin.math.*
import kotlin.random.Random
import kotlin.random.Random.Default.nextDouble

class Fights {
    val r = SecureRandom()
    sealed interface FightEvent {
        val initiatorHP: Double
        val victimHP: Double

        data class AttackEvent(val attacker: User, val victim: User, val attack: Attack, val damage: Double, override val initiatorHP: Double, override val victimHP: Double) : FightEvent
        data class WinEvent(val winner: User, val loser: User, val winnerXPGain: Double, val loserXPGain: Double, override val initiatorHP: Double, override val victimHP: Double) : FightEvent
        data class TieEvent(val xpGain: Double, override val victimHP: Double, override val initiatorHP: Double) : FightEvent
    }

    data class User(val discordID: Long, val level: Int, val isBot: Boolean, var hp: Double)

    inner class Fight(val initiator: User, val victim: User, seed: Long, vararg categories: Attack.Category) {
        val events: List<FightEvent>
        val rand: Random
        init {
            rand = Random(seed)
            initiator.hp = 50.0 + initiator.level * 2
            victim.hp = 50.0 + victim.level * 2

            val e = mutableListOf<FightEvent>()

            if (victim.isBot) {
                val attack = Attack.pickAttack(0, rand, listOf(Attack.Category.BOT))
                val damage = attack.damage(rand, 0)
                e.add(FightEvent.AttackEvent(victim, initiator, attack, damage, 0.0, victim.hp))
                e.add(FightEvent.WinEvent(victim, initiator, 0.0, 0.0, 0.0, victim.hp))
            } else {
                var winner: User? = null

                for (i in 0..5) {
                    if (i == 0 && rand.nextDouble() < 0.25) {} else {
                        val aAttack = Attack.pickAttack(initiator.level, rand, categories.toList())
                        val aDamage = aAttack.damage(rand, initiator.level)
                        victim.hp -= aDamage
                        victim.hp = victim.hp.coerceAtLeast(0.0)
                        e.add(FightEvent.AttackEvent(initiator, victim, aAttack, aDamage, initiator.hp, victim.hp))
                        if (victim.hp <= 0) {
                            winner = initiator
                            break
                        }
                    }

                    val bAttack = Attack.pickAttack(victim.level, rand, categories.toList())
                    val bDamage = bAttack.damage(rand, victim.level)
                    initiator.hp -= bDamage
                    initiator.hp = initiator.hp.coerceAtLeast(0.0)
                    e.add(FightEvent.AttackEvent(victim, initiator, bAttack, bDamage, initiator.hp, victim.hp))
                    if (initiator.hp <= 0) {
                        winner = victim
                        break
                    }
                }

                if (initiator.hp == victim.hp) {
                    e.add(FightEvent.TieEvent(xpGain(initiator.hp, victim.hp, (initiator.level + victim.level) / 2, (initiator.level + victim.level) / 2), initiator.hp, victim.hp))
                } else if (winner == null) {
                    winner = if (initiator.hp > victim.hp) initiator else victim
                    val loser = if (winner == initiator) victim else initiator
                    e.add(FightEvent.WinEvent(winner, loser,
                        xpGain(winner.hp, loser.hp, winner.level, loser.level),
                        xpGain(loser.hp, winner.hp, loser.level, winner.level),
                        initiator.hp, victim.hp
                    ))
                } else {
                    val loser = if (winner == initiator) victim else initiator
                    e.add(FightEvent.WinEvent(winner, loser,
                        xpGain(winner.hp, loser.hp, winner.level, loser.level),
                        xpGain(loser.hp, winner.hp, loser.level, winner.level),
                        initiator.hp, victim.hp
                    ))
                }
            }
            events = e
        }
    }

    companion object {
        fun xpGain(hp: Double, otherHP: Double, level: Int, otherLevel: Int): Double {
            val fraction = (hp / otherHP.coerceAtLeast(1.0)).run { if (this > 1.0) this * 2 else this }.div(4).coerceAtMost(2.0)
            val pts = Leveling.fightPoints(level.toDouble(), nextDouble(0.5, 1.0) * fraction)
            val levelFraction = (otherLevel.toDouble() + 1) / (level.toDouble() + 1)
            val levelMultiplier = if (levelFraction >= 1) log10(levelFraction) + 1 else levelFraction.pow(10)
            return pts * levelMultiplier
        }

        @JvmStatic
        fun main(args: Array<String>) {
            Fights().blah()
        }
    }

    fun blah() {
        for (level in 0..100) {
            val userA = User(123, level, false, 0.0)
            val userB = User(456, level, false, 0.0)
            var sum = 0
            repeat(500) {
                sum += Fight(userA, userB, Random.nextLong(), Attack.Category.GENERAL, Attack.Category.TECHNICAL, Attack.Category.FRC).events.size - 1
            }
            println("$level, ${sum / 500.0}")
        }
    }

    suspend fun sendFight(bot: Bot, event: CommandInteraction, initiator: net.dv8tion.jda.api.entities.User, victim: net.dv8tion.jda.api.entities.User, guild: Guild, seed: Long) {
        val initiatorU = User(initiator.idLong, bot.leveling.level(initiator, guild).toInt(), initiator.isBot, 0.0)
        val victimU = User(victim.idLong, bot.leveling.level(victim, guild).toInt(), victim.isBot, 0.0)
        val fight = bot.database.trnsctn {
            Fight(initiatorU, victimU, seed, *guild.m.fightCategoryList.toList().toTypedArray())
        }

        val attackerXPGain = fight.events.last().run { if (this is FightEvent.TieEvent) this.xpGain else (if ((this as FightEvent.WinEvent).winner.discordID == initiator.idLong) winnerXPGain else loserXPGain) }
        val victimXPGain   = fight.events.last().run { if (this is FightEvent.TieEvent) this.xpGain else (if ((this as FightEvent.WinEvent).winner.discordID == initiator.idLong) loserXPGain else winnerXPGain) }

        val attackerID = initiator.idLong
        val victimID = victim.idLong

        val channelID = event.channel!!.idLong
        val str = StringBuilder()

        fun generateEmbed(): MessageEmbed {
            return Commands.embed("${guild.getMember(initiator)!!.effectiveName} wants to fight ${guild.getMember(victim)!!.effectiveName}!", description = str.toString() +
                    "\n\n**<@${fight.initiator.discordID}>'s HP:** ${fight.initiator.hp.roundToInt()}\n**<@${fight.victim.discordID}>'s HP:** ${fight.victim.hp.roundToInt()}",
                stripPings = false)
        }

        fight.initiator.hp = 2.0 * fight.initiator.level + 50
        fight.victim.hp = 2.0 * fight.victim.level + 50
        val id = event.replyEmbeds(generateEmbed()).await().retrieveOriginal().await().idLong

        for (item in fight.events) {
            delay(1000L)

            str.appendLine(when (item) {
                is FightEvent.AttackEvent -> item.attack.string("<@${item.attacker.discordID}>", "<@${item.victim.discordID}>", fight.rand) + " (${if (item.damage.isInfinite()) "999999999" else item.damage.roundToLong().toString()} damage)"
                is FightEvent.WinEvent -> "**<@${item.winner.discordID}> won, gaining ${item.winnerXPGain.roundToLong()} XP!  <@${item.loser.discordID}> lost, but gained ${item.loserXPGain.roundToLong()} XP.**"
                is FightEvent.TieEvent -> "**Tie!  Both parties gain ${item.xpGain.roundToLong()} XP!**"
            })

            fight.initiator.hp = item.initiatorHP
            fight.victim.hp = item.victimHP

            bot.jda.getTextChannelById(channelID)!!.editMessageEmbedsById(id, generateEmbed()).await()
        }

        var b1 = false
        var b2 = false
        bot.database.trnsctn {
            b1 = pts(initiator.m, event.guild!!.m).shadowbanned
            b2 = pts(victim.m, event.guild!!.m).shadowbanned
        }

        if (!b1) {
            bot.leveling.addPoints(bot.jda.getUserById(attackerID)!!, attackerXPGain, bot.jda.getTextChannelById(channelID)!!)
        }
        if (!b2) {
            bot.leveling.addPoints(bot.jda.getUserById(victimID)!!, victimXPGain, bot.jda.getTextChannelById(channelID)!!)
        }
    }

    @Command("fight", "Fight another user")
    suspend inline fun CmdCtx.fight(@P("user", "The user to fight") user: Member) {
        val attacker = event.member!!

        if (user == attacker) throw CommandException("You can't fight yourself!")

        val guild = event.guild!!
//            if (bot.leveling.level(attacker.user, guild) > bot.leveling.level(user.user, guild) + 5.0 && user.idLong != bot.jda.selfUser.idLong)
//                throw CommandException("Can't fight someone more than 5 levels lower than you!")

        bot.database.trnsctn {
            val u = attacker.user.m
            val g = guild.m
            val pts =
                ExposedDatabase.Point.find { (ExposedDatabase.Points.user eq u.id) and (ExposedDatabase.Points.guild eq g.id) }
                    .first()
            val cooldown = pts.fightCooldown.plus(g.fightCooldown)
            val now = Instant.now()
            if (cooldown.isAfter(now)) {
                throw CommandException("Can't fight again until <t:${cooldown.epochSecond}> (<t:${cooldown.epochSecond}:R>)!")
            }

            pts.fightCooldown = now
        }
        val seed = r.nextLong()
        this.run { bot.fights.sendFight(bot, event, attacker.user, user.user, guild, seed) }
    }
}
