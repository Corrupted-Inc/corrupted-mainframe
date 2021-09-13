package com.github.corruptedinc.corruptedmainframe.utils

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.interactions.Interaction

val Member?.admin get() = this?.permissions?.contains(Permission.ADMINISTRATOR) == true

val Interaction.jda get() = user.jda
