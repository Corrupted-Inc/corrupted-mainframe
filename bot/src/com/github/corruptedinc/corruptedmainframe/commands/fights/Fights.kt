package com.github.corruptedinc.corruptedmainframe.commands.fights

import com.github.corruptedinc.corruptedmainframe.commands.Commands
import com.github.corruptedinc.corruptedmainframe.commands.Leveling
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import dev.minn.jda.ktx.await
import kotlinx.coroutines.delay
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.CommandInteraction
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction
import kotlin.math.*
import kotlin.random.Random.Default.nextDouble

class Fights(private val bot: Bot) {
    sealed interface FightEvent {
        val initiatorHP: Double
        val victimHP: Double

        data class AttackEvent(val attacker: User, val victim: User, val attack: Attack, val damage: Double, override val initiatorHP: Double, override val victimHP: Double) : FightEvent
        data class WinEvent(val winner: User, val loser: User, val winnerXPGain: Double, val loserXPGain: Double, override val initiatorHP: Double, override val victimHP: Double) : FightEvent
        data class TieEvent(val xpGain: Double, override val victimHP: Double, override val initiatorHP: Double) : FightEvent
    }

    data class User(val discordID: Long, val level: Int, val isBot: Boolean, var hp: Double)

    inner class Fight(val initiator: User, val victim: User, val events: List<FightEvent>) {
        constructor(initiator: User, victim: User, bullying: Boolean, vararg categories: Attack.Category) : this(initiator, victim, kotlin.run {
            initiator.hp = 50.0 + initiator.level * 2
            victim.hp = 50.0 + victim.level * 2

            val e = mutableListOf<FightEvent>()

            if (victim.isBot || (bullying && bot.database.user(victim.discordID).botAdmin)) {
                val attack = Attack.pickAttack(0, listOf(if (bullying) Attack.Category.BULLYING else Attack.Category.BOT))
                val damage = attack.damage(0)
                e.add(FightEvent.AttackEvent(victim, initiator, attack, damage, 0.0, victim.hp))
                e.add(FightEvent.WinEvent(victim, initiator, 0.0, 0.0, 0.0, victim.hp))
            } else if (bullying) {
                val attack = Attack.pickAttack(0, listOf(Attack.Category.BOT))
                val damage = attack.damage(0)
                e.add(FightEvent.AttackEvent(initiator, victim, attack, damage, 0.0, initiator.hp))
                e.add(FightEvent.WinEvent(initiator, victim, 0.0, 0.0, 0.0, initiator.hp))
            } else {
                var winner: User? = null

                for (i in 0..5) {
                    val aAttack = Attack.pickAttack(initiator.level, categories.toList())
                    val aDamage = aAttack.damage(initiator.level)
                    victim.hp -= aDamage
                    victim.hp = victim.hp.coerceAtLeast(0.0)
                    e.add(FightEvent.AttackEvent(initiator, victim, aAttack, aDamage, initiator.hp, victim.hp))
                    if (victim.hp <= 0) {
                        winner = initiator
                        break
                    }

                    val bAttack = Attack.pickAttack(victim.level, categories.toList())
                    val bDamage = bAttack.damage(victim.level)
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
            e
        })
    }

    companion object {
        fun xpGain(hp: Double, otherHP: Double, level: Int, otherLevel: Int): Double {
            val fraction = (hp / otherHP.coerceAtLeast(1.0)).run { if (this > 1.0) this * 2 else this }.div(4).coerceAtMost(2.0)
            val pts = Leveling.fightPoints(level.toDouble(), nextDouble(0.5, 1.0) * fraction)
            val levelFraction = (otherLevel.toDouble() + 1) / (level.toDouble() + 1)
            val levelMultiplier = if (levelFraction >= 1) log10(levelFraction) + 1 else levelFraction.pow(10)
            return pts * levelMultiplier
        }
    }

    suspend fun sendFight(event: CommandInteraction, initiator: net.dv8tion.jda.api.entities.User, victim: net.dv8tion.jda.api.entities.User, guild: Guild, bullying: Boolean) {
        val initiatorU = User(initiator.idLong, bot.leveling.level(initiator, guild).toInt(), initiator.isBot, 0.0)
        val victimU = User(victim.idLong, bot.leveling.level(victim, guild).toInt(), victim.isBot, 0.0)
        val fight = bot.database.trnsctn {
            Fight(initiatorU, victimU, bullying, *bot.database.guild(guild).fightCategoryList.toList().toTypedArray())
        }

        val attackerXPGain = fight.events.last().run { if (this is FightEvent.TieEvent) this.xpGain else (if ((this as FightEvent.WinEvent).winner.discordID == initiator.idLong) winnerXPGain else loserXPGain) }
        val victimXPGain   = fight.events.last().run { if (this is FightEvent.TieEvent) this.xpGain else (if ((this as FightEvent.WinEvent).winner.discordID == initiator.idLong) loserXPGain else winnerXPGain) }

        val attackerID = initiator.idLong
        val victimID = victim.idLong

        val channelID = event.channel!!.idLong
        val str = StringBuilder()

        fun generateEmbed(): MessageEmbed {
            return Commands.embed("${guild.getMember(initiator)!!.effectiveName} wants to ${if (bullying) "bully" else "fight"} ${guild.getMember(victim)!!.effectiveName}!", description = str.toString() +
                    "\n\n**<@${fight.initiator.discordID}>'s HP:** ${fight.initiator.hp.roundToInt()}\n**<@${fight.victim.discordID}>'s HP:** ${fight.victim.hp.roundToInt()}",
                stripPings = false)
        }

        fight.initiator.hp = 2.0 * fight.initiator.level + 50
        fight.victim.hp = 2.0 * fight.victim.level + 50
        val id = event.replyEmbeds(generateEmbed()).await().retrieveOriginal().await().idLong

        for (item in fight.events) {
            delay(1000L)

            str.appendLine(when (item) {
                is FightEvent.AttackEvent -> item.attack.string("<@${item.attacker.discordID}>", "<@${item.victim.discordID}>") + " (${if (item.damage.isInfinite()) "999999999" else item.damage.roundToLong().toString()} damage)"
                is FightEvent.WinEvent -> "**<@${item.winner.discordID}> won, gaining ${item.winnerXPGain.roundToLong()} XP!  <@${item.loser.discordID}> lost, but gained ${item.loserXPGain.roundToLong()} XP.**"
                is FightEvent.TieEvent -> "**Tie!  Both parties gain ${item.xpGain.roundToLong()} XP!**"
            })

            fight.initiator.hp = item.initiatorHP
            fight.victim.hp = item.victimHP

            bot.jda.getTextChannelById(channelID)!!.editMessageEmbedsById(id, generateEmbed()).await()
        }

        bot.leveling.addPoints(bot.jda.getUserById(attackerID)!!, attackerXPGain, bot.jda.getTextChannelById(channelID)!!)
        bot.leveling.addPoints(bot.jda.getUserById(victimID)!!, victimXPGain, bot.jda.getTextChannelById(channelID)!!)
    }
}
