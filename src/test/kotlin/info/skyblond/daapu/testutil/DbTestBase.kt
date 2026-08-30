package info.skyblond.daapu.testutil

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach

/**
 * Base class for DB-backed tests: starts the testcontainers PostgreSQL
 * once per JVM ([TestDb.init]) and wipes every table before EACH test, so
 * tests are order-independent without a per-test container. Pure-logic
 * tests (no DB) do not extend this — the reset is real SQL and costs a few
 * round trips.
 *
 * Also tears down the Koin containers [testKoinApp] created during the
 * test ([closeTestKoinApps]): their `onClose` callbacks release the real
 * resources the graphs hold (the advisory-lock manager's connection pool,
 * the hand/MCP cleanup), which would otherwise accumulate across the whole
 * test JVM.
 */
abstract class DbTestBase {
    companion object {
        @JvmStatic
        @BeforeAll
        fun initDb() = TestDb.init()
    }

    @BeforeEach
    fun resetDb() = TestDb.resetAll()

    @AfterEach
    fun closeKoinApps() = closeTestKoinApps()
}
