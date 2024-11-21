package com.github.corruptedinc.corruptedmainframe.annotations

@Target(AnnotationTarget.CLASS)
annotation class ParentCommand(val name: String = "", val description: String, val global: Boolean = false)

@Target(AnnotationTarget.CLASS)
annotation class CommandGroup(val name: String = "", val description: String)

@Target(AnnotationTarget.FUNCTION)
annotation class Command(val name: String = "", val description: String, val group: String = "", val global: Boolean = false)

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Param(val name: String = "", val description: String)

typealias P = Param

// support hasn't made it into JDA quite yet, see https://github.com/discord-jda/JDA/pull/2633
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class UserInstallable

@Target(AnnotationTarget.FUNCTION)
@Repeatable
annotation class Autocomplete(val path: String)
