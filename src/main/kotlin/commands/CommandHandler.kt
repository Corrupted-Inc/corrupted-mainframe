package commands

import kotlin.reflect.KClass

typealias Runner<S, D> = suspend (sender: S, args: Map<String, Any>) -> CommandHandler.InternalCommandResult<D>

typealias Sender<S, D> = (CommandHandler.CommandResult<S, D>) -> Unit

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
open class CommandHandler<S, D>(val send: Sender<S, D>) {
    val commands = mutableListOf<Command<S, D>>()

    data class CommandCategory(val name: String, val subcategories: List<CommandCategory>)

    open class Argument<T : Any>(val type: KClass<T>, val parser: (String) -> T, val checker: (String) -> Boolean, val name: String, val optional: Boolean)

    class StringArg(name: String, optional: Boolean = false) : Argument<String>(String::class, { it }, { true }, name, optional)

    class IntArg(name: String, optional: Boolean = false) : Argument<Int>(Int::class, String::toInt, { it.matches("-?\\d+".toRegex()) }, name, optional)

    class LongArg(name: String, optional: Boolean = false) : Argument<Long>(Long::class, String::toLong, { it.matches("-?\\d+".toRegex()) }, name, optional)

    class DoubleArg(name: String, optional: Boolean = false) : Argument<Double>(Double::class, String::toDouble, { it.matches("^-?\\d+($|(\\.\\d+))".toRegex()) }, name, optional)

    class Command<S, D> private constructor(
        val base: String,
        val arguments: List<Argument<*>>,
        val help: String,
        val runner: Runner<S, D>,
        val overrideSend: Sender<S, D>?,
        val category: CommandCategory?
    ) {

        fun matches(args: List<String>): Pair<Boolean, List<Argument<*>>> {
            if (args.isEmpty() && !arguments.any { !it.optional }) return Pair(true, arguments)
            var index = 0
            val used = mutableListOf<Argument<*>>()
            for (found in args) {
                var arg = arguments.getOrNull(index++) ?: return Pair(false, emptyList())
                while (arg.optional && !arg.checker(found)) {
                    index++
                    arg = arguments.getOrNull(index) ?: return Pair(false, emptyList())
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
            private val base: String
            private val args: List<Argument<*>>
            private val runner: Runner<S, D>?
            private val overrideSend: Sender<S, D>?
            private val help: String
            private val category: CommandCategory?

            private constructor(base: String, args: List<Argument<*>>, help: String, runner: Runner<S, D>?, overrideSend: Sender<S, D>?, category: CommandCategory?) {
                this.base = base
                this.args = args
                this.runner = runner
                this.overrideSend = overrideSend
                this.help = help
                this.category = category
            }

            constructor(base: String) {
                this.base = base
                args = emptyList()
                runner = null
                overrideSend = null
                help = ""
                category = null
            }

            fun arg(type: Argument<*>): CommandBuilder<S, D> {
                return CommandBuilder(base, args.plus(type), help, runner, overrideSend, category)
            }

            fun args(vararg type: Argument<*>): CommandBuilder<S, D> {
                return CommandBuilder(base, args.plus(type), help, runner, overrideSend, category)
            }

            fun ran(runner: Runner<S, D>): CommandBuilder<S, D> {
                return CommandBuilder(base, args, help, runner, overrideSend, category)
            }

            fun sent(overrideSend: Sender<S, D>): CommandBuilder<S, D> {
                return CommandBuilder(base, args, help, runner, overrideSend, category)
            }

            fun help(text: String): CommandBuilder<S, D> {
                return CommandBuilder(base, args, text, runner, overrideSend, category)
            }

            fun category(category: CommandCategory?): CommandBuilder<S, D> {
                return CommandBuilder(base, args, help, runner, overrideSend, category)
            }

            fun build(): Command<S, D> {
                return Command(base, args, help, runner!!, overrideSend, category)
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
        val found = commands.singleOrNull { it.base == name && it.matches(args).first } ?: return CommandResult(sender, null, false, null)
        val usedArgs = found.matches(args).second
        val map = mutableMapOf<String, Any>()
        for ((item, value) in usedArgs.zip(args)) {
            map[item.name] = item.parser(value)
        }
        return try { found.runner(sender, map).run { CommandResult(sender, value, success, found) } } catch (e: Exception) { e.printStackTrace(); CommandResult(sender, null, false, found) }
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
