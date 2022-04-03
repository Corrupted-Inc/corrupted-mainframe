package com.github.corruptedinc.corruptedmainframe.math

import ch.obermuhlner.math.big.BigDecimalMath
import ch.obermuhlner.math.big.kotlin.bigdecimal.pow
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

// The jankinator, perry the platypus!
class InfixNotationParser(precision: Int) {
    private val mc = MathContext(precision, RoundingMode.HALF_UP)

    private val constants = mapOf(
        "\\bpi\\b".toRegex() to BigDecimalMath.pi(mc),
        "\\be\\b".toRegex() to BigDecimalMath.e(mc)
    )

    private sealed interface PhaseOneTokens {
        data class NumberToken(val num: BigDecimal) : PhaseOneTokens { override fun toString(): String = num.toPlainString() }

        data class FunctionNameToken(val name: String) : PhaseOneTokens

        data class OperatorToken(val op: Operator) : PhaseOneTokens { override fun toString() = op.name }

        object ParenthesisOpen : PhaseOneTokens { override fun toString() = "(" }

        object ParenthesisClose : PhaseOneTokens { override fun toString() = ")" }
    }

    enum class Operator {
        PLUS, MINUS, TIMES, DIVIDE, UNARY_MINUS, POW
    }

    // TODO: unary minus should be lower precedence than pow
    fun parse(expression: String): BigDecimal {
        var inp = expression.replace(" ", "")
        for (c in constants.entries) {
            inp = inp.replace(c.key, c.value.toPlainString())
        }

        val numberRegex = "\\b\\d+(\\.\\d+)?(e-?\\d+(\\.\\d+)?)?\\b".toRegex()

        val phaseOneTokens = mutableListOf<PhaseOneTokens>()
        val numbers = numberRegex.findAll(inp)

        val numberBuilder = StringBuilder()
        var match: MatchResult? = numbers.find { 0 in it.range }

        val functionNameBuilder = StringBuilder()

        for ((index, c) in inp.withIndex()) {
            if (match != null) {
                if (index !in match.range) {
                    phaseOneTokens.add(PhaseOneTokens.NumberToken(BigDecimal(numberBuilder.toString(), mc)))
                    numberBuilder.clear()
                    match = numbers.find { index in it.range }
                } else {
                    numberBuilder.append(c)
                    continue
                }
            } else {
                match = numbers.find { index in it.range }
                if (match != null) {
                    numberBuilder.append(c)
                    continue
                }
            }

            phaseOneTokens.add(when (c) {
                '(' -> {
                    if (functionNameBuilder.isNotEmpty()) {
                        phaseOneTokens.add(PhaseOneTokens.FunctionNameToken(functionNameBuilder.toString()))
                        functionNameBuilder.clear()
                    }
                    PhaseOneTokens.ParenthesisOpen
                }
                ')' -> PhaseOneTokens.ParenthesisClose
                '+' -> PhaseOneTokens.OperatorToken(Operator.PLUS)
                '*' -> PhaseOneTokens.OperatorToken(Operator.TIMES)
                '/' -> PhaseOneTokens.OperatorToken(Operator.DIVIDE)
                '^' -> PhaseOneTokens.OperatorToken(Operator.POW)
                '-' -> {
                    if (phaseOneTokens.isEmpty() ||
                        phaseOneTokens.last() is PhaseOneTokens.OperatorToken ||
                        phaseOneTokens.last() == PhaseOneTokens.ParenthesisOpen) {
                        PhaseOneTokens.OperatorToken(Operator.UNARY_MINUS)
                    } else {
                        PhaseOneTokens.OperatorToken(Operator.MINUS)
                    }
                }
                in 'a'..'z' -> {
                    functionNameBuilder.append(c)
                    null
                }
                else -> throw IllegalArgumentException(c.toString())
            } ?: continue)
        }
        if (numberBuilder.isNotEmpty()) {
            phaseOneTokens.add(PhaseOneTokens.NumberToken(BigDecimal(numberBuilder.toString(), mc)))
        }

        val toInvert = mutableListOf<Int>()
        var n = 0
        for ((first, second) in phaseOneTokens.zipWithNext()) {
            if ((first as? PhaseOneTokens.OperatorToken)?.op == Operator.UNARY_MINUS && second is PhaseOneTokens.NumberToken) {
                toInvert.add(n)
            }
            n++
        }

        for (idx in toInvert.reversed()) {
            phaseOneTokens.removeAt(idx)
            phaseOneTokens[idx] =
                PhaseOneTokens.NumberToken((phaseOneTokens[idx] as PhaseOneTokens.NumberToken).num.negate())
        }

        phaseOneTokens.add(0, PhaseOneTokens.ParenthesisOpen)
        phaseOneTokens.add(PhaseOneTokens.ParenthesisClose)

        val parenthesisLevels = mutableMapOf(0 to mutableListOf(0 until phaseOneTokens.size))
        var lvl = 0
        val starts = mutableListOf<Int>()
        for ((index, token) in phaseOneTokens.withIndex()) {
            if (token == PhaseOneTokens.ParenthesisOpen) {
                lvl++
                starts.add(index)
            }
            if (token == PhaseOneTokens.ParenthesisClose) {
                parenthesisLevels.getOrPut(lvl) { mutableListOf() }.add(starts.removeLast()..index)
                lvl--
            }
        }

        fun parseNoParenthesis(tokens: List<PhaseOneTokens>): BigDecimal {
            val t = tokens.toMutableList()

            fun parseSingleLevel(vararg ops: Operator) {
                val output = mutableListOf(t.first())
                var skipUntil = -1
                for (i in 1 until t.size) {
                    if (i < skipUntil) continue
                    val token = t[i]
                    if (token is PhaseOneTokens.OperatorToken && token.op in ops) {
                        val a = (t[i - 1] as PhaseOneTokens.NumberToken).num
                        val b = (t[i + 1] as PhaseOneTokens.NumberToken).num
                        skipUntil = i + 2
                        output.removeAt(i - 1)
                        output.add(
                            PhaseOneTokens.NumberToken(
                                when (token.op) {
                                    Operator.UNARY_MINUS -> throw RuntimeException("this should never happen")
                                    Operator.PLUS -> a + b
                                    Operator.MINUS -> a - b
                                    Operator.TIMES -> a * b
                                    Operator.DIVIDE -> a / b
                                    Operator.POW -> a.pow(b)
                                }
                            )
                        )
                    } else {
                        output.add(token)
                    }
                }

                t.clear()
                t.addAll(output)
            }

            parseSingleLevel(Operator.POW)
            parseSingleLevel(Operator.TIMES, Operator.DIVIDE)
            parseSingleLevel(Operator.PLUS, Operator.MINUS)

            return (t.single() as PhaseOneTokens.NumberToken).num
        }

        val tokens = phaseOneTokens.toMutableList()
        for (group in parenthesisLevels.toSortedMap().values.reversed()) {
            for (item in group) {
                if (tokens.size == 1) break
                val diff = item.last - item.first

                for (lst in parenthesisLevels.values) {
                    for ((i, v) in lst.withIndex()) {
                        if (v.first < item.first && v.last > item.last) {
                            lst[i] = v.first..(v.last - diff)
                        } else if (v.first > item.last) {
                            lst[i] = (v.first - diff)..(v.last - diff)
                        }
                    }
                }

                val t = tokens.slice(item).drop(1).dropLast(1)
                val num = parseNoParenthesis(t)
                repeat(item.last - item.first + 1) { tokens.removeAt(item.first) }
                tokens.add(item.first, PhaseOneTokens.NumberToken(num))
            }

            val replace = mutableListOf<Pair<Int, PhaseOneTokens>>()
            for (i in 0 until tokens.size - 1) {
                val t = tokens[i]
                val num = (tokens[i + 1] as? PhaseOneTokens.NumberToken)?.num ?: continue
                if (t is PhaseOneTokens.FunctionNameToken || (t as? PhaseOneTokens.OperatorToken)?.op == Operator.UNARY_MINUS) {
                    val newNum = if (t is PhaseOneTokens.FunctionNameToken) {
                        when (t.name.lowercase()) {
                            "sin" -> BigDecimalMath.sin(num, mc)
                            "cos" -> BigDecimalMath.cos(num, mc)
                            "tan" -> BigDecimalMath.tan(num, mc)
                            "cot" -> BigDecimalMath.cot(num, mc)
                            "sec" -> BigDecimal.ONE / BigDecimalMath.cos(num, mc)
                            "csc" -> BigDecimal.ONE / BigDecimalMath.sin(num, mc)

                            "asin" -> BigDecimalMath.asin(num, mc)
                            "acos" -> BigDecimalMath.acos(num, mc)
                            "atan" -> BigDecimalMath.atan(num, mc)
                            "acot" -> BigDecimalMath.acot(num, mc)
                            "asec" -> BigDecimalMath.acos(BigDecimal.ONE / num, mc)
                            "acsc" -> BigDecimalMath.asin(BigDecimal.ONE / num, mc)

                            "sqrt" -> BigDecimalMath.sqrt(num, mc)
                            "exp" -> BigDecimalMath.exp(num, mc)
                            "ln" -> BigDecimalMath.log(num, mc)
                            "log" -> BigDecimalMath.log10(num, mc)
                            else -> throw IllegalArgumentException("Unknown function '$t.name'!")
                        }
                    } else {
                        -num
                    }
                    replace.add(i to PhaseOneTokens.NumberToken(newNum))

                    for (lst in parenthesisLevels.values) {
                        for ((j, v) in lst.withIndex()) {
                            if (v.first < i && v.last > i) {
                                lst[j] = v.first until v.last
                            } else if (v.first > i) {
                                lst[j] = (v.first - 1) until v.last
                            }
                        }
                    }
                }
            }

            for (item in replace.reversed()) {
                tokens.removeAt(item.first)
                tokens.removeAt(item.first)
                tokens.add(item.first, item.second)
            }
        }

        return (tokens.single() as PhaseOneTokens.NumberToken).num
    }
}
