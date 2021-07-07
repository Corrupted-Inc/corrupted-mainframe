package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.commands.CommandHandler.*
import kotlin.reflect.KClass

typealias Runner<S, D> = suspend (sender: S, args: Map<String, Any>) -> InternalCommandResult<D>

typealias Sender<S, D> = (CommandResult<S, D>) -> Unit

typealias ErrorConverter<S, D> = (Command<S, D>, CommandException) -> D

class CommandException(cause: String) : Exception(cause)

/**
 * A system for handling commands.  [S] is usually some way of representing the source of the command, and [D] is the
 * command result type.  Example usage:
 * ```
 * val handler = CommandHandler<String, String> { result -> println(result) }
 * handler.register(CommandBuilder<String, String>("help").arg(IntArg("page"))
 *     .ran { sender, args -> InternalCommandResult(..., true) }.build()
 * )
 * GlobalScope.launch {
 *     handler.handleAndSend("!", "!help 2", "username")
 * }
 * ```
 */
open class CommandHandler<S, D>(val send: Sender<S, D>, val error: ErrorConverter<S, D>) {
    val commands = mutableListOf<Command<S, D>>()

    data class CommandCategory(val name: String, val subcategories: List<CommandCategory>)

    open class Argument<T : Any>(val type: KClass<T>, val parser: (String) -> T, val checker: (String) -> Boolean, val name: String, val optional: Boolean, val vararg: Boolean) {
        override fun toString(): String {
            return "${this::class.java.simpleName}(type=$type, name=$name, optional=$optional, vararg=$vararg)"
        }
    }

    class StringArg(name: String, optional: Boolean = false, vararg: Boolean = false) : Argument<String>(String::class, { it }, { true }, name, optional, vararg)

    class IntArg(name: String, optional: Boolean = false, vararg: Boolean = false) : Argument<Int>(Int::class, String::toInt, { it.matches("-?\\d+".toRegex()) }, name, optional, vararg)

    class LongArg(name: String, optional: Boolean = false, vararg: Boolean = false) : Argument<Long>(Long::class, String::toLong, { it.matches("-?\\d+".toRegex()) }, name, optional, vararg)

    class DoubleArg(name: String, optional: Boolean = false, vararg: Boolean = false) : Argument<Double>(Double::class, String::toDouble, { it.matches("^-?\\d+($|(\\.\\d+))".toRegex()) }, name, optional, vararg)

    class Command<S, D> private constructor(
        val base: List<String>,
        val arguments: List<Argument<*>>,
        val help: String,
        val runner: Runner<S, D>,
        val overrideSend: Sender<S, D>?,
        val category: CommandCategory?,
        val validator: ((sender: S, args: Map<String, Any>) -> D?)?
    ) {
        init {
            if (arguments.dropLast(1).any { it.vararg }) throw IllegalArgumentException("Only the last argument can be a vararg!  (in command $base)")
        }

        fun matches(args: List<String>): Pair<Boolean, List<Argument<*>>> {
            if (args.isEmpty() && !arguments.any { !it.optional }) return Pair(true, arguments)
            var index = -1
            val used = mutableListOf<Argument<*>>()
            for (found in args) {
                var arg: Argument<*>
                arg = if (++index >= arguments.size) {
                    if (arguments.lastOrNull()?.vararg == true) {
                        arguments.last()
                    } else {
                        return Pair(false, emptyList())
                    }
                } else {
                    arguments.getOrNull(index) ?: return Pair(false, emptyList())
                }

                while (arg.optional && !arg.checker(found)) {
                    arg = if (++index > arguments.size) {
                        if (arguments.lastOrNull()?.vararg == true) {
                            arguments.last()
                        } else {
                            return Pair(false, emptyList())
                        }
                    } else {
                        arguments.getOrNull(index) ?: return Pair(false, emptyList())
                    }
                }
                if (!arg.checker(found)) {
                    return Pair(false, emptyList())
                }
                used.add(arg)
            }
            if (arguments.minus(used).any { !it.optional }) return Pair(false, emptyList())
            return Pair(true, used)
        }

        class CommandBuilder<S, D> {
            private val base: List<String>
            private val args: List<Argument<*>>
            private val runner: Runner<S, D>?
            private val overrideSend: Sender<S, D>?
            private val help: String
            private val category: CommandCategory?
            private val validator: ((sender: S, args: Map<String, Any>) -> D?)?

            private constructor(base: List<String>, args: List<Argument<*>>, help: String, runner: Runner<S, D>?, overrideSend: Sender<S, D>?, category: CommandCategory?, validator: ((sender: S, args: Map<String, Any>) -> D?)?) {
                this.base = base
                this.args = args
                this.runner = runner
                this.overrideSend = overrideSend
                this.help = help
                this.category = category
                this.validator = validator
            }

            constructor(vararg base: String) {
                this.base = base.toList()
                args = emptyList()
                runner = null
                overrideSend = null
                help = ""
                category = null
                validator = null
            }

            fun arg(type: Argument<*>): CommandBuilder<S, D> {
                return CommandBuilder(base, args.plus(type), help, runner, overrideSend, category, validator)
            }

            fun args(vararg type: Argument<*>): CommandBuilder<S, D> {
                return CommandBuilder(base, args.plus(type), help, runner, overrideSend, category, validator)
            }

            fun ran(runner: Runner<S, D>): CommandBuilder<S, D> {
                return CommandBuilder(base, args, help, runner, overrideSend, category, validator)
            }

            fun sent(overrideSend: Sender<S, D>): CommandBuilder<S, D> {
                return CommandBuilder(base, args, help, runner, overrideSend, category, validator)
            }

            fun help(text: String): CommandBuilder<S, D> {
                return CommandBuilder(base, args, text, runner, overrideSend, category, validator)
            }

            fun category(category: CommandCategory?): CommandBuilder<S, D> {
                return CommandBuilder(base, args, help, runner, overrideSend, category, validator)
            }

            fun validator(validator: ((sender: S, args: Map<String, Any>) -> D?)?): CommandBuilder<S, D> {
                return CommandBuilder(base, args, help, runner, overrideSend, category, validator)
            }

            fun build(): Command<S, D> {
                return Command(base, args, help, runner!!, overrideSend, category, validator)
            }
        }
    }

    data class CommandResult<S, D>(val sender: S, val value: D?, val success: Boolean, val command: Command<S, D>?)

    data class InternalCommandResult<D>(val value: D?, val success: Boolean)

    fun register(command: Command<S, D>) {
        commands.add(command)
    }

    fun register(command: Command.CommandBuilder<S, D>) {
        commands.add(command.build())
    }

    suspend fun handle(prefix: String, input: String, sender: S): CommandResult<S, D> {
        if (!input.startsWith(prefix)) return CommandResult(sender, null, false, null)
        val stripped = input.removePrefix(prefix).removePrefix(" ")
        val tokens = tokenize(stripped)
        val name = tokens.getOrNull(0) ?: return CommandResult(sender, null, false, null)
        val args = tokens.drop(1)
        val found = commands.singleOrNull { it.base.contains(name) && it.matches(args).first } ?: return CommandResult(sender, null, false, null)
        val usedArgs = found.matches(args).second
        val map = mutableMapOf<String, Any>()
        val varargAccumulator = mutableListOf<Any?>()
        for ((item, value) in usedArgs.zip(args)) {
            val parsed = item.parser(value)
            if (item.vararg) {
                varargAccumulator.add(parsed)
            } else {
                map[item.name] = parsed
            }
        }
        if (usedArgs.lastOrNull()?.vararg == true) {
            map[usedArgs.last().name] = varargAccumulator
        }
        found.validator?.invoke(sender, map)?.let { return CommandResult(sender, it, false, found) }
        return try {
            found.runner(sender, map)
                .run { CommandResult(sender, value, success, found) }
        } catch (e: CommandException) {
            CommandResult(sender, error(found, e), false, found)
        } catch (e: Exception) {
            e.printStackTrace()
            CommandResult(sender, null, false, found)
        }
    }

    suspend fun handleAndSend(prefix: String, input: String, sender: S): CommandResult<S, D> {
        val result = handle(prefix, input, sender)
        val c = result.command ?: return result
        val overrideSend = c.overrideSend ?: run { send(result); return result }
        overrideSend(result)
        return result
    }

    fun tokenize(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        if (!input.contains(' ')) return listOf(input)

        val sections = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var slashCount = 0
        for (c in input) {
            if (c == ' ' && !inQuotes) {
                if (current.isNotEmpty()) {
                    sections.add(current.toString())
                }
                current.clear()
                continue
            }

            if (c == '"') {
                if (slashCount % 2 == 0) {
                    if (inQuotes) {
                        inQuotes = false
                        sections.add(current.toString())
                        current.clear()
                    } else {
                        inQuotes = true
                    }
                    continue
                } else {
                    current.deleteCharAt(current.length - 1)
                }
            }
            if (c == '\\') slashCount++ else slashCount = 0
            if (slashCount == 2) {
                current.append('\\')
                current.deleteCharAt(current.length - 1)
                slashCount = 0
                continue
            }

            current.append(c)
        }
        if (current.isNotEmpty()) {
            sections.add(current.toString())
        }
        return sections
    }
}
