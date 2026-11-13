package com.openyt.data

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.openyt.data.db.OpenYtDatabase

fun createOpenYtDriver(
    context: Context,
    databaseName: String = "openyt.db",
): SqlDriver {
    return AndroidSqliteDriver(OpenYtDatabase.Schema, context, databaseName)
}

fun createOpenYtDatabase(
    context: Context,
    databaseName: String = "openyt.db",
): OpenYtDatabase {
    val driver = createOpenYtDriver(context, databaseName)
    return OpenYtDatabase(driver)
}
