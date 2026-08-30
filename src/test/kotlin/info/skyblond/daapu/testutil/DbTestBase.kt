package info.skyblond.daapu.testutil

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach

/**
 * Base class for DB-backed tests: starts the testcontainers PostgreSQL
 * once per JVM ([TestDb.init]) and wipes every table before EACH test, so
 * tests are order-independent without a per-test container. Pure-logic
 * tests (no DB) do not extend this — the reset is real SQL and costs a few
 * round trips.
 */
abstract class DbTestBase {
    companion object {
        @JvmStatic
        @BeforeAll
        fun initDb() = TestDb.init()
    }

    @BeforeEach
    fun resetDb() = TestDb.resetAll()
}
