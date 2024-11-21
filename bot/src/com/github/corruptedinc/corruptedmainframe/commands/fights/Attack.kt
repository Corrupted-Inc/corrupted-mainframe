package com.github.corruptedinc.corruptedmainframe.commands.fights

import com.github.corruptedinc.corruptedmainframe.utils.runIf
import kotlin.random.Random

interface Attack {
    enum class Category(val bitmask: ULong, val pickable: Boolean = true) {
        GENERAL(0b1U), FRC(0b10U), TECHNICAL(0b100U), LINUX(0b1000U), MINDUSTRY(0b10000U), BOT(0b1UL shl 63, false)
    }

    companion object {
        private val attacks = listOf(
            // general
            SimpleAttack(1.0, "%a punched %v!", 0, 10.0, 1.0, 0.5),
            SimpleAttack(0.5, "%a slapped %v!", 2, 15.0, 2.0, 0.5),
            SimpleAttack(0.25, "%a kicked %v!", 5, 30.0, 2.0, 0.5),
            SimpleAttack(0.25, "%a dropped an anvil on %v!", 5, 30.0, 2.0, 0.5),
            SimpleAttack(0.25, "%a chokeslammed %v!", 8, 45.0, 2.0, 0.5),
            SimpleAttack(0.25, "%a booped %v on the nose!", 10, 60.0, 2.0, 0.5),
            SimpleAttack(0.25, "%a threw a hammer at %v!", 10, 50.0, 2.0, 0.5),
            SimpleAttack(0.1, "%a slashed %v!", 10, 50.0, 2.0, 0.25),
            SimpleAttack(0.25, "%a smacked %v!", 0, 10.0, 1.0, 0.5),
            SimpleAttack(0.05, "%a bit %v!", 0, 50.0, 0.0, 0.5),
            SimpleAttack(0.05, "%a slammed %v!", 10, 50.0, 0.0, 0.5),
            SimpleAttack(0.05, "%a headbutted %v!", 10, 50.0, 0.0, 1.0),
            InstakillAttack(0.001, "%a bit %v and gave them rabies!", 0),
            InstakillAttack(0.01, "%a seduced %v!", 5),
            InstakillAttack(0.001, "%v learned what polonium tastes like!", 0),
            InstakillAttack(0.01, "%v fell out a window!", 0),

            // FRC
            SimpleAttack(Category.FRC, 0.5, "%a ran %v over with a robot!", 0, 10.0, 1.0, 0.5),
            SimpleAttack(Category.FRC, 0.5, "%a gave %v a G204 violation!", 10, 25.0, 0.0, 0.0),
            SimpleAttack(Category.FRC, 0.25, "%a gave %v a yellow card!", 10, 50.0, 0.0, 0.0),
            InstakillAttack(Category.FRC, 0.05, "%a gave %v a red card!", 15),
            SimpleAttack(Category.FRC, 0.25, "%a forced %v to retune their PID loop!", 5, 25.0, 1.0, 0.5),
            SimpleAttack(Category.FRC, 0.25, "%a forced %v to remachine %ri10-20 parts!", 5, 25.0, 1.0, 0.5),
            SimpleAttack(Category.FRC, 0.05, "%a broke %v's SD card!", 5, 25.0, 1.0, 0.5),
            InstakillAttack(Category.FRC, 0.01, "%a reported %v to the pit admin for not wearing safety glasses!", 0),
            SimpleAttack(Category.FRC, 0.2, "%a told %v it was programming's fault %ri5-1000 times!", 5, 10.0, 1.0, 0.5),
            SimpleAttack(Category.FRC, 0.5, "%a stabbed %v with a wago!", 5, 60.0, 1.0, 0.25),
            InstakillAttack(Category.FRC, 0.01, "I don't think that's steam...", 0),
            SimpleAttack(Category.FRC, 0.2, "%a told %v it was straight enough!", 5, 50.0, 1.0, 0.5),
            // the future is now, old man
            //SimpleAttack(Category.FRC, 0.2, "%a burned %v's eyes out with a vision ring!", 5, 50.0, 1.0, 0.5),
            SimpleAttack(Category.FRC, 0.2, "%a fooled %v's robot with a fake apriltag!", 2, 25.0, 1.0, 0.5),
            // TODO: field fault, damages everyone

            // misc. technical
            SimpleAttack(listOf(Category.TECHNICAL, Category.FRC), 0.5, "%a DDoS'ed %v!", 5, 50.0, 2.0, 0.75),
            SimpleAttack(listOf(Category.TECHNICAL), 0.5, "%a stabbed %v with a screwdriver!", 5, 50.0, 1.0, 0.25),
            SimpleAttack(listOf(Category.TECHNICAL, Category.FRC), 0.5, "%a updated %v's firmware!", 10, 50.0, 1.0, 0.25),
            SimpleAttack(listOf(Category.TECHNICAL, Category.FRC), 0.5, "%a touched %v's BIOS!", 10, 50.0, 1.0, 0.25),
            SimpleAttack(listOf(Category.TECHNICAL, Category.FRC), 0.5, "%v forgot to use git!", 10, 50.0, 1.0, 0.25),
            SimpleAttack(listOf(Category.TECHNICAL, Category.FRC), 0.25, "%v forgot to start a transaction!", 10, 50.0, 1.0, 0.25),
            SimpleAttack(listOf(Category.TECHNICAL, Category.FRC), 0.25, "%a 'fixed' %v's code!" /* this is a joke please do fix my code */, 10, 50.0, 1.0, 0.25),
            SimpleAttack(listOf(Category.TECHNICAL, Category.FRC), 0.125, "%a peed in the UPW supply!", 10, 50.0, 1.0, 0.25),
            InstakillAttack(listOf(Category.TECHNICAL, Category.FRC), 0.05, "Oops, %v's files have been encrypted!", 0),

            // bot only
            InstakillAttack(listOf(Category.BOT), 0.5, "%a atomized %v!", 0),
            InstakillAttack(listOf(Category.BOT), 0.5, "%a liquidated %v!", 0),
            //InstakillAttack(listOf(Category.BOT), 0.25, "`DELETE FROM users WHERE name=`%v", 0),
            InstakillAttack(listOf(Category.BOT), 0.5, "%v gazed into %a's cold, dead eyes!", 0),
            InstakillAttack(listOf(Category.BOT), 0.5, "%a invoked its god-like power!", 0),
            InstakillAttack(listOf(Category.BOT), 0.5, "%a erased %v!", 0),
            InstakillAttack(listOf(Category.BOT), 0.5, "%a shattered %v!", 0),
            InstakillAttack(listOf(Category.BOT), 0.5, "%a obliterated %v!", 0),
            InstakillAttack(listOf(Category.BOT), 0.5, "%a pulverised %v!", 0),
            InstakillAttack(listOf(Category.BOT), 0.5, "%a eviscerated %v!", 0),
            InstakillAttack(listOf(Category.BOT), 0.5, "%a vaporized %v!", 0),
            InstakillAttack(listOf(Category.BOT), 0.5, "%a consumed %v!", 0),
            InstakillAttack(listOf(Category.BOT), 0.5, "%a deleted %v!", 0),
            InstakillAttack(listOf(Category.BOT), 0.1, "%a disappeared %v!", 0),
            //InstakillAttack(listOf(Category.BOT), 0.1, "%a compressed some lithium-6 deuteride!", 0),
            InstakillAttack(listOf(Category.BOT), 0.1, "%a cured %v's treasonous tendencies!", 0),
            InstakillAttack(listOf(Category.BOT), 0.1, "%a executed %v!", 0),
            //InstakillAttack(listOf(Category.BOT), 0.1, "%a is become death, destroyer of worlds!", 0),
            InstakillAttack(listOf(Category.BOT), 0.25, "%a violated the geneva conventions!", 0),
            InstakillAttack(listOf(Category.BOT), 0.25, "%a ground %v to dust!", 0),
            InstakillAttack(listOf(Category.BOT), 0.1, "%a deorbited the moon!", 0),
            InstakillAttack(listOf(Category.BOT), 0.1, "%a demonstrated the formation of trinitite to %v!", 0),
            InstakillAttack(listOf(Category.BOT), 0.01, "%a licked %v!", 0),

            // LINUX
            // REGULAR ATTACKS
            SimpleAttack(Category.LINUX, 1.0, "%a unleashed the penguins!", 0, 10.0, 1.0, 0.45),
            SimpleAttack(Category.LINUX, 0.5, "%a installed TempleOS on %v's computer!", 2, 15.0, 1.0, 0.3),
            SimpleAttack(Category.LINUX, 0.5, "%a ranted for %ri2-24 hours about systemd!", 5, 30.0, 1.0, 0.75),
            SimpleAttack(Category.LINUX, 0.5, "%a told %v their rice sucks!", 8, 20.0, 1.0, 0.5),
            SimpleAttack(Category.LINUX, 0.25, "%a ran `rm -rf /home/`%v", 15, 60.0, 2.0, 0.85),
            SimpleAttack(Category.LINUX, 0.5, "%a installed a virus on %v's computer", 0, 15.0, 1.0, 0.5),
            SimpleAttack(Category.LINUX, 0.5, "%a messed with %v's grub config!", 20, 45.0, 1.0, 0.5),
            SimpleAttack(Category.LINUX, 0.5, "%a exploited dirtycow!", 20, 75.0, 2.0, 0.5),
            // CRITICAL MISSES LINUX
            //SimpleAttack(Category.LINUX, 0.2, "%v attempted to delete a virus but ran `rm -rf` on root!", 0, 15.0, 1.0, 0.5),
            SimpleAttack(Category.LINUX, 0.15, "%v failed their Gentoo install %ri7-14 days in!", 8, 45.0, 2.0, 0.5),
            SimpleAttack(Category.LINUX, 0.2, "%v couldn't run their game with proton and cried!", 0, 10.0, 1.0, 0.5),
            SimpleAttack(Category.LINUX, 0.15, "%v accidentally deleted /bin/!", 0, 20.0, 1.0, 0.5),
            // INSTANT LINUX
            InstakillAttack(Category.LINUX, 0.0125, "%a zeroed %v's hard-drive!", 15),
            InstakillAttack(Category.LINUX, 0.0100, "`sudo rm -rf /*`", 10),
            InstakillAttack(Category.LINUX, 0.0025, "I'd just like to interject for a moment...", 5),
            // TODO: Balance damage and scaling (Probably won't happen, but this way I feel better about myself)
        )

        fun pickAttack(level: Int, rand: Random, categories: List<Category> = listOf(Category.GENERAL)): Attack {
            val available = attacks.filter { it.usable(level, categories) }.shuffled(rand)
            var value = rand.nextDouble(available.sumOf { it.odds(level) })
            for (a in available) {
                if (value < a.odds(level)) return a
                value -= a.odds(level)
            }
            // should never happen
            return available.last()
        }
    }

    fun usable(level: Int, categories: List<Category>): Boolean

    fun string(a: String, v: String, rand: Random): String

    fun damage(rand: Random, level: Int): Double

    fun odds(level: Int): Double

    open class SimpleAttack(val category: List<Category>, val odds: Double, val format: String, val minLevel: Int, val minDamage: Double, val damageScaling: Double, val randomFactor: Double) : Attack {

        constructor(category: Category, odds: Double, format: String, minLevel: Int, minDamage: Double, damageScaling: Double, randomFactor: Double) : this(listOf(category), odds, format, minLevel, minDamage, damageScaling, randomFactor)

        constructor(odds: Double, format: String, minLevel: Int, minDamage: Double, damageScaling: Double, randomFactor: Double) : this(listOf(Category.GENERAL), odds, format, minLevel, minDamage, damageScaling, randomFactor)

        override fun damage(rand: Random, level: Int): Double {
            return (minDamage + (level - minLevel) * damageScaling * 2).runIf(randomFactor != 0.0) { this * rand.nextDouble(1.0 - randomFactor, 1.0 + randomFactor) }
        }

        override fun usable(level: Int, categories: List<Category>): Boolean {
            return category.intersect(categories.toSet()).isNotEmpty() && level >= minLevel
        }

        override fun odds(level: Int): Double {
            return odds
        }

        override fun string(a: String, v: String, rand: Random): String {
            return format.replace("%a", a).replace("%v", v).replace("%ri(\\d+)-(\\d+)".toRegex()) { rand.nextInt(it.groups[1]!!.value.toInt(), it.groups[2]!!.value.toInt()).toString() }
        }
    }

    class InstakillAttack(val category: List<Category>, val odds: Double, val format: String, val minLevel: Int) : Attack {
        constructor(odds: Double, format: String, minLevel: Int) : this(listOf(Category.GENERAL), odds, format, minLevel)
        constructor(category: Category, odds: Double, format: String, minLevel: Int) : this(listOf(category), odds, format, minLevel)

        override fun usable(level: Int, categories: List<Category>): Boolean {
            return category.intersect(categories.toSet()).isNotEmpty() && level >= minLevel
        }

        override fun string(a: String, v: String, rand: Random): String {
            return format.replace("%a", a).replace("%v", v).replace("%ri(\\d+)-(\\d+)".toRegex()) { rand.nextInt(it.groups[1]!!.value.toInt(), it.groups[2]!!.value.toInt()).toString() }
        }

        override fun damage(rand: Random, level: Int): Double {
            return Double.POSITIVE_INFINITY
        }

        override fun odds(level: Int): Double {
            return odds
        }
    }
}
