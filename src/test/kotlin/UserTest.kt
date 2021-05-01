import core.db.Database
import core.db.Guild
import core.db.User
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class UserTest {
    companion object {
        init {
            File("test.db").delete()
        }
        val database = Database { setProperty("hibernate.connection.url", "jdbc:sqlite:test.db") }
    }

    @Test
    fun testSaving() {
        val persisted = database.transaction {
            val user = User(456L)
            val guild = Guild(1938754L)
            user.servers.add(guild)
            guild.users.add(user)
            persist(user)
            persist(guild)
            return@transaction user
        }

        database.transaction {
            val found = find(User::class.java, persisted.id)
            assertEquals(found, persisted)
            assertEquals(found.servers, persisted.servers)
        }
    }
}
