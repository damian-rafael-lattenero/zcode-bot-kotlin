package dev.lattenero.bot.daemon.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import dev.lattenero.bot.daemon.sq.BotDb
import java.sql.Connection

/**
 * The typed seam over the SAME read-only connection the caller opened. The
 * driver is a pass-through borrower: it never owns or closes connections —
 * the surrounding `use` does — so every read keeps the house pattern
 * (short-lived xerial connection, open_mode=1, zero writes).
 */
internal fun <A> Connection.typed(block: (BotDb) -> A): A =
    block(BotDb(BorrowedConnectionDriver(this)))

/**
 * A JDBC driver over a caller-owned connection: [getConnection] answers the
 * same handle and [closeConnection] is a no-op (the caller's `use` closes it).
 */
private class BorrowedConnectionDriver(private val connection: Connection) : JdbcDriver() {
    override fun getConnection(): Connection = connection

    override fun closeConnection(connection: Connection) = Unit

    // Query observation is a no-op on the JDBC driver family.
    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun notifyListeners(vararg queryKeys: String) = Unit
}
