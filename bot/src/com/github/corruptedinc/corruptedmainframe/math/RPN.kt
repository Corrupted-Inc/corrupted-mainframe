package com.github.corruptedinc.corruptedmainframe.math

import ch.obermuhlner.math.big.BigDecimalMath
import ch.obermuhlner.math.big.kotlin.bigdecimal.pow
import com.github.corruptedinc.corruptedmainframe.math.Token.Op.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.*

class RPN(precision: Int) {
    val stack = LinkedList<BigDecimal>()
    private val mc = MathContext(precision, RoundingMode.HALF_UP)

    fun push(value: BigDecimal) {
        stack.add(value)
    }

    fun peek() = stack.lastOrNull()

    fun op(op: Token.Op) {
        val args = stack.takeLast(op.numArgs)
        repeat(op.numArgs) { stack.pop() }

        when (op) {
            ADD -> args[0] + args[1]
            SUB -> args[0] - args[1]
            MUL -> args[0] * args[1]
            DIV -> args[0] / args[1]
            SQRT -> args[0].sqrt(mc)
            POW -> args[0].pow(args[1])
            EXP -> BigDecimalMath.exp(args[0], mc)
            LN -> BigDecimalMath.log(args[0], mc)
            LOG10 -> BigDecimalMath.log10(args[0], mc)
            LOGB -> BigDecimalMath.log(args[0], mc) / BigDecimalMath.log(args[1], mc)
            SIN -> BigDecimalMath.sin(args[0], mc)
            COS -> BigDecimalMath.cos(args[0], mc)
            TAN -> BigDecimalMath.tan(args[0], mc)
            COT -> BigDecimalMath.cot(args[0], mc)
            SEC -> BigDecimal.ONE / BigDecimalMath.cos(args[0], mc)
            CSC -> BigDecimal.ONE / BigDecimalMath.sin(args[0], mc)
            ASIN -> BigDecimalMath.asin(args[0], mc)
            ACOS -> BigDecimalMath.acos(args[0], mc)
            ATAN -> BigDecimalMath.atan(args[0], mc)
            ACOT -> BigDecimalMath.acot(args[0], mc)
            ASEC -> BigDecimalMath.acos(BigDecimal.ONE / args[0], mc)
            ACSC -> BigDecimalMath.asin(BigDecimal.ONE / args[0], mc)
        }
    }
}
