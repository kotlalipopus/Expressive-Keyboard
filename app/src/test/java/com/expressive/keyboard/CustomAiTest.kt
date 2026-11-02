package com.expressive.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomAiTest {
    private fun base(url: String, format: String = "openai") = CustomAi("x", "", url, format = format).base()

    @Test
    fun bareDomainGetsV1() {
        assertEquals("https://api.deepseek.com/v1", base("api.deepseek.com"))
        assertEquals("https://api.mistral.ai/v1", base("https://api.mistral.ai/"))
    }

    @Test
    fun keepsPathAndStripsEndpoint() {
        assertEquals("https://api.x.ai/v1", base("https://api.x.ai/v1/chat/completions"))
        assertEquals("https://openrouter.ai/api/v1", base("https://openrouter.ai/api/v1/"))
        assertEquals("https://api.anthropic.com/v1", base("https://api.anthropic.com/v1/messages", "anthropic"))
        assertEquals("http://192.168.1.2:11434/v1", base("http://192.168.1.2:11434"))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            base("https://generativelanguage.googleapis.com/v1beta/openai")
        )
    }

    @Test
    fun providerIdParsing() {
        assertEquals(AiProvider.CUSTOM, AiProvider.from("CUSTOM:abc"))
        assertEquals(AiProvider.GROQ, AiProvider.from("GROQ"))
        assertEquals(AiProvider.AUTO, AiProvider.from("???"))
    }
}
