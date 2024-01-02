package com.github.corruptedinc.corruptedmainframe.commands

import com.beust.klaxon.*
import com.github.corruptedinc.corruptedmainframe.utils.Row
import com.github.corruptedinc.corruptedmainframe.utils.table

class ThrustCurve {
    val motors: List<Motor>

    init {
        val file = this::class.java.classLoader.getResourceAsStream("motors.json")!!.readAllBytes().decodeToString()
        val klaxon = Klaxon()
        klaxon.converter(object : Converter {
            override fun canConvert(cls: Class<*>) = cls == TwoDDoubleArray::class.java

            override fun fromJson(jv: JsonValue): Any? {
                val items = mutableListOf<List<Double>>()
                @Suppress("UNCHECKED_CAST")
                for (item in (jv.array as? JsonArray<JsonArray<Double>> ?: return null)) {
                    val v = mutableListOf<Double>()
                    for (a in item) {
                        v.add(a)
                    }
                    items.add(v)
                }
                val width = items.map { it.size }.fold(-1) { existing, new -> if (existing == -1) return@fold new; if (new == existing) { return@fold existing } else { return null } }
                val array = mutableListOf<Double>()
                for (item in items) {
                    array.addAll(item)
                }
                return TwoDDoubleArray(array.toDoubleArray(), width)
            }

            override fun toJson(value: Any): String {
                throw RuntimeException()
            }
        })

        motors = klaxon.parseArray(file)!!
    }

    class Motor(val name: String, @Json("diameter") val diameterMM: Double, @Json("length") val lengthMM: Double, val delays: List<Double>, @Json("prop_mass") val propMass: Double, @Json("mass") val totalMass: Double, val manufacturer: String, @Json("avg_thrust") val avgThrust: Double, @Json("peak_thrust") val peakThrust: Double, @Json("burn_time") val burnTime: Double, val data: TwoDDoubleArray) {
        @Json(ignored = true)
        val properName = properNameRegex.find(name)!!.value
        companion object {
            @Json(ignored = true)
            val properNameRegex = "(1/[24])?[a-z|A-Z]\\d+".toRegex()
        }

        override fun toString(): String {
            return "$properName $name diameter = ${diameterMM}mm length = ${lengthMM}mm delays = ${delays.joinToString { "${it}s" }} propellant mass = $propMass kg total mass = $totalMass kg avg thrust = ${avgThrust}N peak thrust = ${peakThrust}N burn time = ${burnTime}s manufacturer = $manufacturer\n$data"
        }
    }

    class TwoDDoubleArray(val data: DoubleArray, val width: Int) : Iterable<List<Double>> {
        constructor(width: Int, height: Int) : this(DoubleArray(width * height), width)

        operator fun get(x: Int, y: Int) = data[x + (y * width)]
        operator fun set(x: Int, y: Int, value: Double) { data[x + (y * width)] = value }

        override fun iterator(): Iterator<List<Double>> {
            return data.toList().chunked(width).iterator()
        }

        override fun toString(): String {
            val first = Row(*Array(width) { "" })
            return table(arrayOf(first) + this.map { Row(*it.map { v -> v.toString() }.toTypedArray()) })
        }
    }
}

//fun main() {
//    println(ThrustCurve().motors)
//}
