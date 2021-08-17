package com.github.corruptedinc.corruptedmainframe.discord

import net.dv8tion.jda.api.events.GenericEvent
import net.dv8tion.jda.api.hooks.EventListener
import java.util.*

class MultiListener : EventListener, MutableList<EventListener> by Collections.synchronizedList(mutableListOf()) {
    override fun onEvent(event: GenericEvent) {
        forEach { it.onEvent(event) }
    }
}
