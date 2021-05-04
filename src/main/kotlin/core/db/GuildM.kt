package core.db

import javax.persistence.*

@Entity
@Table(name = "guilds")
class GuildM(@get:Column(name = "discordId") var discordId: Long) {
    @get:Id
    @get:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @get:ManyToMany(mappedBy = "servers")
    var users: MutableList<UserM> = mutableListOf()

    var prefix: String = "!"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GuildM

        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }

    override fun toString(): String {
        return "Guild(id=$id, discordId=$discordId, prefix=$prefix)"
    }
}