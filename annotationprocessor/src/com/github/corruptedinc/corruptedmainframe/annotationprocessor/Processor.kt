package com.github.corruptedinc.corruptedmainframe.annotationprocessor

import com.github.corruptedinc.corruptedmainframe.annotations.*
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import net.dv8tion.jda.api.entities.IMentionable
import net.dv8tion.jda.api.entities.Message.Attachment
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData
import java.time.Instant
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

class Processor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private var done = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (done) return emptyList()
        done = true
        val symbols = resolver.getSymbolsWithAnnotation(Command::class.qualifiedName!!)

        val parsed = symbols.map { cmd ->
            val parent = cmd.parent as? KSClassDeclaration
//            val group = parent?.findAnnotation<CommandGroup>()
            var group: ParsedCommandGroup? = null

            val foundGroup = parent?.findAnnotation<CommandGroup>()
            val p = if (foundGroup != null) {
                group = ParsedCommandGroup(foundGroup.name.ifBlank { parent.simpleName.getShortName() }, foundGroup.description)
                val parentClass = (parent.parent as? KSClassDeclaration)
                val pa = parentClass?.findAnnotation<ParentCommand>() ?: throw RuntimeException("command group must be part of a parent command")

                ParsedParentCommand(pa.name.ifBlank { parentClass.simpleName.getShortName() }, pa.description, pa.global)
            } else {
                val parentAnnotation = parent?.findAnnotation<ParentCommand>()

                parentAnnotation?.let { ParsedParentCommand(it.name.ifBlank { parent.simpleName.getShortName() }, it.description, it.global) }
            }
//            val parentAnnotation = parent?.findAnnotation<ParentCommand>()
//            val p = parentAnnotation?.let { ParsedParentCommand(it.name.ifBlank { parent.simpleName.getShortName() }, it.description, it.global) }
            parseSingle(cmd as KSFunctionDeclaration, group, p)
        }.toList()

        val asTree: MutableMap<ParsedParentCommand?, MutableMap<ParsedCommandGroup?, List<ParsedSingleCommand>>> = mutableMapOf()
        // (parsed.mapNotNull { it.parent }.toSet() + null).associateWith { parsed.filter { v -> v.parent == it } }
        val parentSet = parsed.mapNotNull { it.parent }.toMutableSet() + null
        val groupSet = parsed.mapNotNull { it.group }.toSet() + null
        for (item in parentSet) {
            val value: MutableMap<ParsedCommandGroup?, List<ParsedSingleCommand>> = mutableMapOf()
            for (v in groupSet) {
                value[v] = parsed.filter { it.parent == item && it.group == v }
            }
            asTree[item] = value
        }

        val files = parsed.mapNotNull { it.func.containingFile }.toSet()
        val output = environment.codeGenerator.createNewFile(Dependencies(true, *files.toTypedArray()), "com.github.corruptedinc.corruptedmainframe.gen", "GeneratedCommands", "kt")

        val type = TypeSpec.objectBuilder("GeneratedCommands")

        val dataBuilder = CodeBlock.builder()

        dataBuilder.add("return listOf(")

        for (parent in asTree) {
            val k = parent.key ?: continue
            dataBuilder.add("Cmd(slash(%S, %S)", k.name, k.description)
            if (parent.value.any { it.key != null }) {
                dataBuilder.add(".addSubcommandGroups(")
                for ((group, cmds) in parent.value) {
                    group ?: continue
                    dataBuilder.add("SubcommandGroupData(%S, %S)", group.name, group.description)
                    dataBuilder.add(".addSubcommands(")
                    for (cmd in cmds) {
                        dataBuilder.add("SubcommandData(%S, %S)", cmd.name, cmd.description)
                        for (param in cmd.params) {
                            dataBuilder.add(".addOption(OptionType.%L, %S, %S, %L, %L)", param.type.name, param.name, param.description, !param.optional, param.autocomplete != null)
                        }
                        dataBuilder.add(",")
                    }
                    dataBuilder.add(")")
                }
                dataBuilder.add(")")
            }

            if (parent.value[null] != null) {
                dataBuilder.add(".addSubcommands(")
                for (cmd in parent.value[null]!!) {
                    dataBuilder.add("SubcommandData(%S, %S)", cmd.name, cmd.description)
                    for (param in cmd.params) {
                        dataBuilder.add(".addOption(OptionType.%L, %S, %S, %L, %L)", param.type.name, param.name, param.description, !param.optional, param.autocomplete != null)
                    }
                    dataBuilder.add(",")
                }
            }
            dataBuilder.add("), ${k.global}),")
        }
        for (g in asTree[null].orEmpty()) {
            for (command in g.value) {
                dataBuilder.add("Cmd(slash(%S, %S)", command.name, command.description)
                for (param in command.params) {
                    dataBuilder.add(".addOption(OptionType.%L, %S, %S, %L, %L)", param.type.name, param.name, param.description, !param.optional, param.autocomplete != null)
                }
                dataBuilder.add(", ${command.global}),")
            }
        }

        dataBuilder.add(")")

        type.addFunction(
            FunSpec.builder("commandData")
                .returns(List::class.asTypeName().parameterizedBy(ClassName.bestGuess("com.github.corruptedinc.corruptedmainframe.commands.Commands.Cmd")))
                .addCode(dataBuilder.build())
                .build()
        )

        val listenerFun = FunSpec.builder("registerListeners")
            .addParameter("bot", ClassName("com.github.corruptedinc.corruptedmainframe.discord", "Bot"))

        val mainListenerBlock = CodeBlock.builder()

        mainListenerBlock.add("bot.jda.listener<SlashCommandInteractionEvent>{event->")
        mainListenerBlock.add("if(bot.database.bannedT(event.user))return@listener;")
        mainListenerBlock.add("val hook=event.hook;val start=System.currentTimeMillis();try{")
        mainListenerBlock.add("CmdCtx(bot,event).apply{")

        mainListenerBlock.add("when(event.commandPath){")
        val importSet = mutableSetOf<Triple<ClassName, List<String>, String>>()
        val rng = Random(0)
        for (command in parsed) {
            val path = command.parent?.name?.plus("/").orEmpty() + command.group?.name?.plus("/").orEmpty() + command.name
            val garbage = rng.nextULong().toString()
            mainListenerBlock.add("%S -> %L(", path, command.func.simpleName.asString() + garbage)
            for (param in command.params) {
                mainListenerBlock.add("event.getOption(%S)%L%L%L", param.name, if (param.optional) "?" else "!!", when (param.type) {
                    OptionType.STRING -> ".asString"
                    OptionType.INTEGER -> if (param.functionParam.type.qName() == "kotlin.Int") ".asInt" else ".asLong"
                    OptionType.BOOLEAN -> ".asBoolean"
                    OptionType.USER -> ".asUser"
                    OptionType.CHANNEL -> ".asChannel as? " + param.functionParam.type.qName()
                    OptionType.ROLE -> ".asRole"
                    OptionType.MENTIONABLE -> ".asMentionable"
                    OptionType.NUMBER -> ".asDouble"
                    OptionType.ATTACHMENT -> ".asAttachment"
                    else -> throw IllegalArgumentException()
                }, if (param.optional) "" else " ?: throw CommandException(\"\")")
                mainListenerBlock.add(",")
            }
            mainListenerBlock.add(")")
            importSet.add(Triple((command.func.parentDeclaration as KSClassDeclaration).toClassName(), listOf(command.func.simpleName.asString()), command.func.simpleName.asString() + garbage))
            mainListenerBlock.add(";")
        }
        mainListenerBlock.add("}")

        mainListenerBlock.add("}")

        mainListenerBlock.add("""
            } catch (e: CommandException) {
                val embed = embed("Error", description = e.message, color = ERROR_COLOR)
                if (event.isAcknowledged) {
                    hook.editOriginalEmbeds(embed).await()
                } else {
                    event.replyEmbeds(embed).ephemeral().await()
                }
            } catch (e: Exception) {
                val embed = embed("Internal Error", color = ERROR_COLOR)
                if (event.isAcknowledged) {
                    hook.editOriginalEmbeds(embed).await()
                } else {
                    event.replyEmbeds(embed).ephemeral().await()
                }
                bot.log.error("Error in command '${/*this is dumb*/'$'}{event.commandPath}':\n" + e.stackTraceToString())
            }
            bot.database.trnsctn{val g=event.guild!!.m;val u=event.user.m;com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.CommandRun.new{this.guild=g.id;this.user=u.id;this.timestamp=Instant.now();this.command=event.commandPath;this.millis=System.currentTimeMillis()-start;}}
            """.trimIndent())

        mainListenerBlock.add("}")
        mainListenerBlock.add(";")

        listenerFun.addCode(mainListenerBlock.build())

        val autocompleteListenerBlock = CodeBlock.builder()

        autocompleteListenerBlock.add("bot.jda.listener<CommandAutoCompleteInteractionEvent>{event->")

        autocompleteListenerBlock.add("AtcmpCtx(bot,event).apply{")

        autocompleteListenerBlock.add("val res=when(\"\${event.commandPath}/\${event.focusedOption.name}\"){")

        for (command in parsed) {
            for (opt in command.params) {
                val auto = opt.autocomplete ?: continue
                val cName = (auto.func.parentDeclaration as KSClassDeclaration).toClassName()
                val name = importSet.find { it.first == cName && it.second == listOf(auto.func.simpleName.asString()) }?.third ?: run { val name = auto.func.simpleName.asString() + rng.nextULong().toString(); importSet.add(Triple(cName, listOf(auto.func.simpleName.asString()), name)); name }
                autocompleteListenerBlock.add("%S -> %L()", command.parent?.name?.plus("/").orEmpty() + command.name + "/${opt.name}", name)
                autocompleteListenerBlock.add(";")
            }
        }

        autocompleteListenerBlock.add("else -> return@listener")

        autocompleteListenerBlock.add("};")

        autocompleteListenerBlock.add("event.replyChoices(res).await()")
        autocompleteListenerBlock.add("}")

        autocompleteListenerBlock.add("}")

        listenerFun.addCode(autocompleteListenerBlock.build())

        type.addFunction(listenerFun.build())

        fun FileSpec.Builder.import(cls: KClass<*>) = addImport(cls.java.packageName, cls.simpleName!!)

        val fileSpec = FileSpec.builder("com.github.corruptedinc.corruptedmainframe.gen", "GeneratedCommands")
            .apply {
                for (import in importSet) {
                    addAliasedImport(import.first, import.second.joinToString("."), import.third)
                }
            }
            .addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("\"USELESS_ELVIS\", \"RemoveRedundantQualifierName\", \"RedundantVisibilityModifier\", \"UnusedImport\", \"RedundantUnitReturnType\"").build())
            .addType(type.build())
            .import(SubcommandData::class)
            .import(SubcommandGroupData::class)
            .import(OptionType::class)
            .addImport(Commands::class, "slash")
            .addImport("com.github.corruptedinc.corruptedmainframe.utils", "CommandContext", "CmdCtx", "AtcmpCtx")
            .addImport("dev.minn.jda.ktx.events", "listener")
            .addImport("com.github.corruptedinc.corruptedmainframe.commands", "CommandException")
            .addImport("com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion", "embed", "ERROR_COLOR")
            .addImport("dev.minn.jda.ktx.coroutines", "await")
            .addImport("com.github.corruptedinc.corruptedmainframe.utils", "ephemeral")
            .addImport("com.github.corruptedinc.corruptedmainframe.utils", "commandPath")
            .addImport("com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion", "m")
            .addImport("com.github.corruptedinc.corruptedmainframe.commands.Commands", "Cmd")
            .import(SlashCommandInteractionEvent::class)
            .import(CommandAutoCompleteInteractionEvent::class)
            .import(Instant::class)
            .build()

        val b = StringBuilder()
        fileSpec.writeTo(b)
        output.write(b.toString().encodeToByteArray())
        output.close()

        return emptyList()
    }

    private tailrec fun KSTypeReference.qName(): String {
        val dec = resolve().declaration
        return if (dec is KSTypeAlias) {
            dec.type.qName()
        } else {
            dec.qualifiedName!!.asString()
        }
    }

    private inline fun <reified T : Annotation> KSAnnotated.findAnnotation(): T? {
//        val found = annotations.singleOrNull { it.annotationType.qName() == T::class.qualifiedName } ?: return null
//        // no questions
//        val args = found::class.java.getDeclaredMethod("getResolved").apply { isAccessible = true }.invoke(found).let { it::class.java.interfaces.first().getDeclaredMethod("getAllValueArguments").invoke(it) } as Map<*, *>
//        val values = args.mapKeys { it.key.toString() }.mapValues { it.value!!::class.java.superclass.getDeclaredMethod("getValue").invoke(it.value) }
//            .mapKeys { k -> T::class.primaryConstructor!!.parameters.single { it.name == k.key } }
//        return T::class.constructors.first().callBy(values)
        return findAnnotations<T>().singleOrNull()
    }

    private inline fun <reified T : Annotation> KSAnnotated.findAnnotations(): List<T> {
        val getAllValueArguments = Class.forName("org.jetbrains.kotlin.resolve.lazy.descriptors.LazyAnnotationDescriptor").getDeclaredMethod("getAllValueArguments")
        return annotations.filter { it.annotationType.qName() == T::class.qualifiedName }.map { found ->
            val args = found::class.java.getDeclaredMethod("getResolved").apply { isAccessible = true }.invoke(found).let { getAllValueArguments.invoke(it) } as Map<*, *>
            val values = args.mapKeys { it.key.toString() }.mapValues { it.value!!::class.java.superclass.getDeclaredMethod("getValue").invoke(it.value) }
                .mapKeys { k -> T::class.primaryConstructor!!.parameters.single { it.name == k.key } }
            T::class.constructors.first().callBy(values)
        }.toList()
    }

    private fun parseParameter(func: KSFunctionDeclaration, param: KSValueParameter, path: String): ParsedParam {
        val cls = func.parentDeclaration as KSClassDeclaration
        val annotation = param.findAnnotation<Param>()!!

        val pName = annotation.name.ifBlank { param.name!!.asString() }
//        if (path.contains("starboard") && path.contains("modify")) {
//            throw RuntimeException("AAAAA $path/$pName ")
//        }
        val foundAutocomplete = cls.getAllFunctions().flatMap { it.findAnnotations<Autocomplete>().map { a -> a to it } }
//            .also { if (it.toList().isNotEmpty() && path.contains("modify")) throw RuntimeException("AAAAAA ${it.toList().map { v -> v.first.path.removePrefix("/") }} $path/$pName") }
            .filter { it.first.path.removePrefix("/") == "$path/$pName" }.singleOrNull()
//        if (foundAutocomplete?.first != null) {
//            throw RuntimeException("AAAAAAAAAAAAAAAA")
//        }
        if (foundAutocomplete != null) {
            val at = foundAutocomplete.second
            require(at.extensionReceiver!!.qName() == "com.github.corruptedinc.corruptedmainframe.utils.AutocompleteContext")
            require(at.parameters.isEmpty())
            require(Modifier.SUSPEND in at.modifiers)
            require(Modifier.INLINE in at.modifiers)
            require(at.returnType!!.qName() == List::class.qualifiedName)
            require(at.returnType!!.resolve().arguments.first().type!!.qName() == net.dv8tion.jda.api.interactions.commands.Command.Choice::class.qualifiedName)
        }
        val autocomplete = foundAutocomplete?.let { ParsedAutocomplete(it.second) }

        val t = param.type.resolve()
        val type = when (val tName = param.type.qName()) {
            String::class.qualifiedName -> OptionType.STRING
            Int::class.qualifiedName -> OptionType.INTEGER
            Long::class.qualifiedName -> OptionType.INTEGER
            Boolean::class.qualifiedName -> OptionType.BOOLEAN
            User::class.qualifiedName -> OptionType.USER
            Role::class.qualifiedName -> OptionType.ROLE
            IMentionable::class.qualifiedName -> OptionType.MENTIONABLE
            Double::class.qualifiedName -> OptionType.NUMBER
            Attachment::class.qualifiedName -> OptionType.ATTACHMENT  // TODO: ByteArray/InputStream jank?
            // TODO: enums?
            // TODO: instant, duration
            else -> if ((t.declaration as KSClassDeclaration).getAllSuperTypes().any { it.declaration.qualifiedName!!.asString() == "net.dv8tion.jda.api.entities.channel.Channel" } || tName == "net.dv8tion.jda.api.entities.channel.Channel") OptionType.CHANNEL else throw IllegalArgumentException(param.name!!.asString())
        }

        val optional = param.hasDefault || t.isMarkedNullable

        return ParsedParam(pName, annotation.description, param, type, optional, autocomplete)
    }

    private fun parseSingle(func: KSFunctionDeclaration, group: ParsedCommandGroup?, parent: ParsedParentCommand?): ParsedSingleCommand {
        require(func.extensionReceiver!!.qName() == "com.github.corruptedinc.corruptedmainframe.utils.CommandContext")
        require(Modifier.SUSPEND in func.modifiers)
        require(Modifier.INLINE in func.modifiers)
        val annotation = func.findAnnotation<Command>()!!
        val name = annotation.name.ifBlank { func.simpleName.getShortName() }
        val path = (if (parent != null) "${parent.name}/" else "") + (if (group != null) "${group.name}/" else "") + name
        val params = func.parameters.map { parseParameter(func, it, path) }
        return ParsedSingleCommand(name, annotation.description, params, func, group, parent, parent?.global ?: annotation.global)
    }

    data class ParsedParentCommand(val name: String, val description: String, val global: Boolean)

    data class ParsedCommandGroup(val name: String, val description: String)

    data class ParsedSingleCommand(val name: String, val description: String, val params: List<ParsedParam>, val func: KSFunctionDeclaration, val group: ParsedCommandGroup?, val parent: ParsedParentCommand?, val global: Boolean)

    data class ParsedParam(val name: String, val description: String, val functionParam: KSValueParameter, val type: OptionType, val optional: Boolean, val autocomplete: ParsedAutocomplete?) {
        init {
            if (description.length > 100) throw RuntimeException("Description of command '$name' is longer than 100 characters!")
        }
    }

    data class ParsedAutocomplete(val func: KSFunctionDeclaration)
}
