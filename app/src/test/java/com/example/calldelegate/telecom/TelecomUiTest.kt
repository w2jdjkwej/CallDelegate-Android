package com.example.calldelegate.telecom

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TelecomUiTest {
    @Test
    fun maskCallerNumberKeepsShortServiceNumbersReadable() {
        assertThat(maskCallerNumber("10086")).isEqualTo("10086")
    }

    @Test
    fun maskCallerNumberHidesMiddleDigits() {
        assertThat(maskCallerNumber("13812349527")).isEqualTo("138 •••• 9527")
    }

    @Test
    fun maskCallerNumberHandlesMissingNumber() {
        assertThat(maskCallerNumber(null)).isEqualTo("未知号码")
    }
}
