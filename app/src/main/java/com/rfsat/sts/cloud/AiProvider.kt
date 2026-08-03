package com.rfsat.sts.cloud

/**
 * Which service is asked to look at the card.
 *
 * The two are interchangeable from the app's side: both are sent the same
 * rectified picture and the same question, and both answer against the same
 * schema. Nothing downstream knows or cares which replied.
 */
enum class AiProvider(val label: String, val keyHint: String, val console: String) {
    ANTHROPIC("Claude (Anthropic)", "sk-ant-\u2026", "console.anthropic.com"),
    OPENAI("OpenAI", "sk-\u2026", "platform.openai.com")
}
