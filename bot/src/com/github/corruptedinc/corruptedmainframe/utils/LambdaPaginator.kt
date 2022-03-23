package com.github.corruptedinc.corruptedmainframe.utils

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Emoji
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.TextChannel
import net.dv8tion.jda.api.events.GenericEvent
import net.dv8tion.jda.api.exceptions.ErrorHandler
import net.dv8tion.jda.api.hooks.EventListener
import net.dv8tion.jda.api.hooks.SubscribeEvent
import net.dv8tion.jda.api.interactions.Interaction
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.buttons.ButtonInteraction
import net.dv8tion.jda.api.requests.ErrorResponse
import org.apache.commons.codec.binary.Base64
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

val DEFAULT_PREV = Button.secondary("prev", Emoji.fromUnicode("⬅️"))
val DEFAULT_NEXT = Button.secondary("next", Emoji.fromUnicode("➡️"))
val DEFAULT_DELETE = Button.danger("delete", Emoji.fromUnicode("\uD83D\uDEAE"))

class LambdaPaginator internal constructor(private val nonce: String, private val ttl: Long,
                                           private val page: (Long) -> MessageEmbed,
                                           private val size: Long): EventListener {
    private var expiresAt: Long = System.currentTimeMillis() + ttl

    private var index = 0L

    var filter: (ButtonInteraction) -> Boolean = { true }

    fun filterBy(filter: (ButtonInteraction) -> Boolean): LambdaPaginator {
        this.filter = filter
        return this
    }

    var prev: Button = DEFAULT_PREV
    var next: Button = DEFAULT_NEXT
    var delete: Button = DEFAULT_DELETE

    internal val controls: ActionRow
        get() = ActionRow.of(
        prev.withDisabled(index == 0L).withId("$nonce:prev"),
        next.withDisabled(index == size - 1).withId("$nonce:next"),
        delete.withId("$nonce:delete")
    )

    @SubscribeEvent
    override fun onEvent(event: GenericEvent) {
        if (expiresAt < System.currentTimeMillis()) unregister(event.jda)
        if (event !is ButtonInteraction || expiresAt < System.currentTimeMillis()) return
        val buttonId = event.componentId
        if (!buttonId.startsWith(nonce) || !filter(event)) return
        expiresAt = System.currentTimeMillis() + ttl
        val (_, operation) = buttonId.split(":")
        when (operation) {
            "prev" -> {
                index -= 1
                index = index.coerceIn(0 until size)
                val p = page(index)
                event.editMessageEmbeds(p)
                    .setActionRows(controls)
                    .queue(
                        null,
                        ErrorHandler().handle(ErrorResponse.UNKNOWN_MESSAGE) { unregister((event as GenericEvent).jda) }
                    )
            }
            "next" -> {
                index += 1
                index = index.coerceIn(0 until size)
                val p = page(index)
                event.editMessageEmbeds(p)
                    .setActionRows(controls)
                    .queue(
                        null,
                        ErrorHandler().handle(ErrorResponse.UNKNOWN_MESSAGE) { unregister((event as GenericEvent).jda) }
                    )
            }
            "delete" -> {
                unregister((event as GenericEvent).jda)
                event.deferEdit().queue()
                event.hook.deleteOriginal().queue(null, ErrorHandler().ignore(ErrorResponse.UNKNOWN_MESSAGE))
            }
        }
    }

    private fun unregister(jda: JDA) {
        jda.removeEventListener(this)
    }

    internal fun register(jda: JDA): LambdaPaginator {
        jda.addEventListener(this)
        return this
    }
}

@Suppress("MagicNumber")
internal fun paginator(size: Long, lambda: (Long) -> MessageEmbed, jda: JDA): LambdaPaginator {
    val nonce = ByteArray(32)
    SecureRandom().nextBytes(nonce)
    return LambdaPaginator(Base64.encodeBase64String(nonce), TimeUnit.MINUTES.toMillis(15), lambda, size).register(jda)
}

fun TextChannel.lambdaPaginator(size: Long, lambda: (Long) -> MessageEmbed) {
    val paginator = paginator(size, lambda, jda)
    sendMessageEmbeds(lambda(0)).setActionRow(paginator.controls.components).complete()
}

fun SlashCommandInteraction.replyLambdaPaginator(size: Long, lambda: (Long) -> MessageEmbed) {
    val paginator = paginator(size, lambda, jda)
    replyEmbeds(lambda(0)).addActionRows(listOf(paginator.controls)).complete()
}
