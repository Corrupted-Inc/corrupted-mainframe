package com.github.corruptedinc.corruptedmainframe.annotations

@Target(AnnotationTarget.CLASS)
annotation class ParentCommand(val name: String = "", val description: String)

@Target(AnnotationTarget.FUNCTION)
annotation class Command(val name: String = "", val description: String, val group: String = "")

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Param(val name: String = "", val description: String)

typealias P = Param

@Target(AnnotationTarget.FUNCTION)
annotation class Autocomplete(val path: String)
