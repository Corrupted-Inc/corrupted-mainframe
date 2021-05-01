package core.db

import javax.persistence.*

@Entity
@Table(name = "users")
class User private constructor(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long?,

    @Column
    val discordId: Long
) {
    constructor(discordId: Long) : this(null, discordId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as User

        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun toString(): String {
        return "User(id=$id, discordId=$discordId)"
    }
}
