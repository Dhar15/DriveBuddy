package app.drivebuddy.core

import app.drivebuddy.features.chat.BuddyChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuddyChatTest {

    @Test fun messagesStartWithSystemPromptThenConversation() {
        val chat = BuddyChat()
        chat.addUser("tell me a joke")
        chat.addAssistant("Why did the car stop? It ran out of gas, ha.")
        chat.addUser("another one")
        val m = chat.messages()
        assertEquals("system", m[0].role)
        assertEquals(listOf("user", "assistant", "user"), m.drop(1).map { it.role })
    }

    @Test fun oldMessagesAreForgottenAndHistoryStartsOnUser() {
        val chat = BuddyChat(maxMessages = 4)
        repeat(5) { chat.addUser("q$it"); chat.addAssistant("a$it") }
        val m = chat.messages().drop(1)
        assertTrue(m.size <= 4)
        assertEquals("user", m.first().role)
    }

    @Test fun conversationEndsAfterTheExchangeCap() {
        val chat = BuddyChat(maxExchanges = 2)
        assertTrue(chat.canContinue)
        chat.addUser("a"); chat.addAssistant("b")
        assertTrue(chat.canContinue)
        chat.addUser("c"); chat.addAssistant("d")
        assertFalse(chat.canContinue)
        chat.reset()
        assertTrue(chat.canContinue)
    }

    @Test fun goodbyes() {
        assertTrue(BuddyChat.isGoodbye("thanks"))
        assertTrue(BuddyChat.isGoodbye("Okay, thanks!"))
        assertTrue(BuddyChat.isGoodbye("that's all"))
        assertTrue(BuddyChat.isGoodbye("no thanks"))
        assertFalse(BuddyChat.isGoodbye("tell me why the sky is blue, thanks to the sun"))
        assertFalse(BuddyChat.isGoodbye("what's a good song"))
        assertFalse(BuddyChat.isGoodbye(""))
    }

    @Test fun tidyRemovesMarkdownEmojiAndNewlines() {
        val raw = "**Sure!** Here you go:\n- first thing\n- second 😀"
        assertEquals("Sure! Here you go: first thing second", BuddyChat.tidy(raw))
    }

    @Test fun tidyCutsLongRepliesAtASentenceEnd() {
        val sentence = "This is a fairly long sentence about driving. "
        val out = BuddyChat.tidy(sentence.repeat(20))
        assertTrue(out.length <= 350)
        assertTrue(out.endsWith("."))
    }
}