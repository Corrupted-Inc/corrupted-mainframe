package utils

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member

val Member?.admin get() = this?.permissions?.contains(Permission.ADMINISTRATOR) == true
