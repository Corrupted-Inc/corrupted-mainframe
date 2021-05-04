package core.db

import org.hibernate.cfg.Configuration
import java.io.File
import java.util.logging.Logger
import javax.persistence.EntityManager

class Database(config: Configuration.() -> Unit = {}) {
    private val configuration = Configuration().configure(File("hibernate.cfg.xml")).apply(config)
    private val sessionFactory = configuration.buildSessionFactory()
    val entityManager = sessionFactory.createEntityManager()
    val logger: Logger = Logger.getLogger("database logger")

    /**
     * Begins a transaction and runs [block].  If it should throw an exception, the transaction is rolled back and the
     * exception is re-thrown.  Otherwise, it is committed.  [T] being [Unit] will cause no issues syntactically or
     * logically.
     */
    inline fun <T> transaction(block: EntityManager.() -> T): T {
        entityManager.transaction.begin()
        try {
            val t = entityManager.block()
            entityManager.transaction.commit()
            return t
        } catch (e: Exception) {
            try {
                entityManager.transaction.rollback()
            } finally {
                throw e
            }
        }
    }
}
