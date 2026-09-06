package info.skyblond.daapu.db

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the SQLState recognition behind the write paths' fail-fast
 * conversion: expected constraint violations must be detected through the
 * cause chain so the service converts them to non-SQL exceptions inside
 * the transaction (instead of letting Exposed retry the block).
 */
class SqlErrorsTest {

    @Test
    fun `unique and foreign-key violations are recognized through the cause chain`() {
        assertTrue(SQLException("dup", "23505").isUniqueViolation())
        assertFalse(SQLException("fk", "23503").isUniqueViolation())
        assertTrue(SQLException("fk", "23503").isForeignKeyViolation())
        assertFalse(SQLException("dup", "23505").isForeignKeyViolation())
        // wrapped (the driver usually nests the state-carrying exception)
        assertTrue(RuntimeException("wrap", SQLException("fk", "23503")).isForeignKeyViolation())
        assertTrue(RuntimeException("wrap", SQLException("dup", "23505")).isUniqueViolation())
        // unrelated states and plain exceptions match neither
        assertFalse(SQLException("syntax", "42601").isUniqueViolation())
        assertFalse(SQLException("syntax", "42601").isForeignKeyViolation())
        assertFalse(RuntimeException("plain").isUniqueViolation())
        assertFalse(RuntimeException("plain").isForeignKeyViolation())
    }
}
