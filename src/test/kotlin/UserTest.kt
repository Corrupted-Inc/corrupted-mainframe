import core.db.Database
import core.db.DatabaseWrapper
import core.db.GuildM
import core.db.UserM
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class UserTest {
    companion object {
        init {
            File("test.db").delete()
        }
        val database = Database { setProperty("hibernate.connection.url", "jdbc:sqlite:test.db") }
        val wrapper = DatabaseWrapper(database)
    }

    @Test
    fun testSaving() {
        val persisted = database.transaction {
            val user = UserM(456L)
            val guild = GuildM(1938754L)
            user.servers.add(guild)
            guild.users.add(user)
            persist(user)
            persist(guild)
            return@transaction user
        }

        database.transaction {
            val found = find(UserM::class.java, persisted.id)
            assertEquals(found, persisted)
            assertEquals(found.servers, persisted.servers)
        }
//        println(wrapper.listGuilds())
//        println(wrapper.listUsers())
    }
}
