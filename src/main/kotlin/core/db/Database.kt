package core.db

import org.hibernate.SessionFactory
import org.hibernate.cfg.Configuration
import java.io.File
import java.util.logging.Logger

class Database(config: Configuration.() -> Unit = {}) {
    private val configuration = Configuration().configure(File("hibernate.cfg.xml")).apply(config)
    val sessionFactory: SessionFactory = configuration.buildSessionFactory()
    val logger = Logger.getLogger("database logger")
}
