package com.openyt.core.application

import com.openyt.core.domain.AppLanguage

/** Unsupported OS languages use English while an explicit choice always wins. */
fun resolveLanguage(preference: AppLanguage, systemLanguageTag: String): AppLanguage {
    if (preference != AppLanguage.SYSTEM) return preference
    return if (systemLanguageTag.substringBefore('-').substringBefore('_').lowercase() == "pt") {
        AppLanguage.PT_BR
    } else {
        AppLanguage.EN
    }
}
