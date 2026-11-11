package com.openyt.core.application

import com.openyt.core.domain.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemLanguageTest {
    @Test
    fun detectsOperatingSystemLanguageAndHasADefinedFallback() {
        for (tag in listOf("pt-BR", "pt-PT", "PT_br", "pt")) {
            assertEquals(AppLanguage.PT_BR, resolveLanguage(AppLanguage.SYSTEM, tag))
        }
        for (tag in listOf("en-US", "en", "de-DE", "", "ja-JP")) {
            assertEquals(AppLanguage.EN, resolveLanguage(AppLanguage.SYSTEM, tag))
        }
    }

    @Test
    fun preservesAnExplicitUserPreference() {
        assertEquals(AppLanguage.EN, resolveLanguage(AppLanguage.EN, "pt-BR"))
        assertEquals(AppLanguage.PT_BR, resolveLanguage(AppLanguage.PT_BR, "en-US"))
    }
}
