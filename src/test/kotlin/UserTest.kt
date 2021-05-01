import core.db.Database
import core.db.User
import org.hibernate.Session
import org.hibernate.testing.transaction.TransactionUtil.doInHibernate
import org.junit.Test
import java.io.File
import java.util.function.Consumer
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
        doInHibernate(database::sessionFactory, Consumer { session: Session ->
            val user = User(456L)
            session.persist(user)

            val found = session.find(User::class.java, user.id)
            println("Found $found")

            assertEquals(user, found)
        })
    }
}
