package com.openyt.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.openyt.data.db.OpenYtDatabase

fun createOpenYtDatabase(
    context: Context,
    databaseName: String = "openyt.db",
): OpenYtDatabase {
    val driver = AndroidSqliteDriver(OpenYtDatabase.Schema, context, databaseName)
    return OpenYtDatabase(driver)
}
