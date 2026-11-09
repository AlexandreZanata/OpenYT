package com.openyt.desktop

import java.util.Properties

object BuildInfo {
    val version: String by lazy {
        val properties = Properties()
        BuildInfo::class.java.getResourceAsStream("/openyt-version.properties")?.use {
            properties.load(it)
        }
        properties.getProperty("version") ?: "unknown"
    }
}
