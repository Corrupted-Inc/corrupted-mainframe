package com.github.corruptedinc.corruptedmainframe.calculator

import java.math.BigDecimal
import java.math.MathContext
import ch.obermuhlner.math.big.BigDecimalMath
import java.lang.IllegalStateException

class Calculator(private val context: MathContext) {
    private fun String.bigDec(): BigDecimal? {
        val replaced = replace("%", "-")

        val noPrefix = replaced.removePrefix("%")
        if (noPrefix in variables) {
            return if (replaced.startsWith("-")) variables[noPrefix]?.unaryMinus() else variables[noPrefix]
        }

        return replaced.toBigDecimalOrNull()
    }
    private fun BigDecimal.str(): String = toPlainString().replace("-(\\d+(?:\\.\\d+)?|\\.\\d+)".toRegex(), "%$1")
    private val variables = mutableMapOf<String, BigDecimal>()
    companion object {
        private const val MAX_OPS = 100
    }

    init {
        for (constant in Constants.values()) {
            variables[constant.name] = constant.value
            variables[constant.name.lowercase()] = constant.value
        }
    }

    fun evaluate(inp: String): BigDecimal {
        var processed = inp
        for (op in Operation.values()) {
            // extra spaces/no spaces around operators break it
            processed = processed.replace("\\s+${Regex.escape(op.symbol)}\\s+".toRegex(), " ${op.symbol} ")
        }
        if ("^\\w{1,50} = ".toRegex().containsMatchIn(inp)) {
            val varName = inp.substringBefore(" = ")
            return evaluate(inp.removePrefix("$varName = ").substringBefore(" #"), 0, varName)
        }
        return evaluate(inp.substringBefore(" #"), 0, null)
    }

    private fun evaluate(inp: String, level: Int, outputVariableName: String?): BigDecimal {
        val dec = inp.bigDec()
        if (dec != null) return dec.apply {
            if (outputVariableName != null) variables[outputVariableName] = this
        }

        var subbed = inp
        if (level == 0) {
            subbed = subbed.replace("-(\\d+(?:\\.\\d+)?|\\.\\d+)".toRegex(), "%$1")
        }
        val delegated = mutableListOf<String>()
        var parenCount = 0
        for (c in subbed) {
            if (c == '(') {
                parenCount++
//                if (parenCount++ == 0) {
//                    delegated.add("")
//                    continue
//                }
            } else if (c == ')') {
                if (--parenCount == 0) {
                    val evaluated = evaluate(delegated.last(), level + 1, outputVariableName)
                    subbed = subbed.replaceFirst("(" + delegated.last() + ")", evaluated.str())
                    continue
                }
            }
            if (parenCount > 0) {
                delegated[delegated.lastIndex] = delegated.last() + c
            }
        }


        subbed = evalOps(subbed, "^")
        subbed = evalOps(subbed, "*/")
        subbed = evalOps(subbed, "+-")

        return subbed.bigDec()?.apply {
            if (outputVariableName != null) variables[outputVariableName] = this
        } ?: throw IllegalStateException("Failed to evaluate expression!")
    }

    private fun evalOps(inp: String, ops: String): String {
        var subbed = inp
        @Suppress("UnusedPrivateMember")  // what do you want from me
        for (i in 0..MAX_OPS) {
            for (op in ops) {
                subbed = subbed.replace(" $op ", op.toString())
            }

            val toEvaluate = ("[\\w.]+" + "[${Regex.escape(ops)}]" + "[\\w.]+").toRegex().findAll(subbed)
            @Suppress("SpreadOperator")  // -_-
            for (item in toEvaluate) {
                val first = item.value.split(*ops.toCharArray()).first().bigDec()!!
                val second = item.value.split(*ops.toCharArray()).last().bigDec()!!
                val opStr = "[${Regex.escape(ops)}]".toRegex().find(item.value)
                val op = Operation.values().find { it.symbol == opStr?.value }
                    ?: throw IllegalArgumentException("Couldn't find operation in '${item.value}'!")
                subbed = subbed.replace(item.value, op.inv(first, second).str())
            }
            if (!subbed.any { it in ops }) return subbed
        }
        throw IllegalArgumentException("Too many args!")
    }

    enum class Operation(
        val symbol: String, val lambda: (a: BigDecimal, b: BigDecimal, context: MathContext) -> BigDecimal) {

        EXPONENT("^", { a, b, context -> BigDecimalMath.pow(a, b, context) }),
        MULTIPLY("*", { a, b, context -> a.multiply(b, context) }),
        DIVIDE("/", { a, b, context -> a.divide(b, context) }),
        ADD("+", { a, b, context -> a.add(b, context) }),
        SUBTRACT("-", { a, b, context -> a.subtract(b, context) })
    }

    private fun Operation?.inv(a: BigDecimal, b: BigDecimal): BigDecimal = this?.lambda?.invoke(a, b, context) ?: a
}
