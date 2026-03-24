package com.sbfs.ai.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        val driver: SqlDriver = JdbcSqliteDriver("jdbc:sqlite:ai.db", Properties(), AiChallengeDb.Schema)
        AiChallengeDb.Schema.create(driver)
        return driver
    }
}