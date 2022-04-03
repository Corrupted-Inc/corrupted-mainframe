package com.github.corruptedinc.corruptedmainframe.math

import java.math.BigDecimal

sealed interface Token {

    data class NumberToken(val value: BigDecimal) : Token

    data class OperationToken(val op: Op)

    enum class Op(val numArgs: Int = 1) {
        ADD(2), SUB(2), MUL(2), DIV(2),
        SQRT, POW(2), EXP, LN, LOG10, LOGB(2),
        SIN, COS, TAN, COT, SEC, CSC,
        ASIN, ACOS, ATAN, ACOT, ASEC, ACSC,
    }
}
