package com.multistore.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SupportedLanguageTest {

    @Test
    fun `simplified Chinese system variants resolve to the app language`() {
        assertThat(SupportedLanguage.fromBcp47OrNull("zh-CN"))
            .isEqualTo(SupportedLanguage.CHINESE_SIMPLIFIED)
        assertThat(SupportedLanguage.fromBcp47OrNull("zh-Hans-CN"))
            .isEqualTo(SupportedLanguage.CHINESE_SIMPLIFIED)
        assertThat(SupportedLanguage.fromBcp47OrNull("zh"))
            .isEqualTo(SupportedLanguage.CHINESE_SIMPLIFIED)
    }
}
