package app.drivebuddy.features.communication

import android.util.Log
import app.drivebuddy.features.phone.PhoneActions
import app.drivebuddy.service.Conversation
import app.drivebuddy.ui.DriveState

/** Calls and text messages: finds the contact, then dials, or takes a message and confirms before sending. */
class CommunicationHandler(private val c: Conversation, private val actions: PhoneActions) {

    private companion object { const val TAG = "DriveBuddy" }

    fun call(spoken: String) {
        if (!actions.hasContactsPermission()) {
            c.reply("I need permission to read your contacts.")
            return
        }
        when (val match = NameMatcher.match(spoken, actions.loadContacts())) {
            is MatchResult.Single -> {
                val who = match.candidate
                c.reply("Calling ${who.name}. Tap Talk to cancel.") {
                    if (!actions.placeCall(who.number)) Log.w(TAG, "call to ${who.name} could not be placed")
                }
            }
            is MatchResult.Ambiguous ->
                c.reply("I found ${match.candidates.joinToString(" and ") { it.name }}. Please say the full name.")
            MatchResult.None -> c.reply("I couldn't find $spoken in your contacts.")
        }
    }

    fun sms(spoken: String, body: String?) {
        if (!actions.hasContactsPermission()) {
            c.reply("I need permission to read your contacts.")
            return
        }
        if (!actions.hasSmsPermission()) {
            Log.w(TAG, "sms: permission missing")
            c.reply("I need permission to send text messages. Open Permissions in DriveBuddy and turn it on.")
            return
        }
        val contacts = actions.loadContacts()
        var message = body
        var match = NameMatcher.match(spoken, contacts)
        if (match == MatchResult.None && body == null) {
            // "text papa hello": there was no "saying", so the words after the contact's name are the message.
            val words = spoken.split(" ").filter { it.isNotEmpty() }
            for (k in words.size - 1 downTo 1) {
                val tryName = words.take(k).joinToString(" ")
                val m = NameMatcher.match(tryName, contacts)
                if (m != MatchResult.None) {
                    match = m
                    message = words.drop(k).joinToString(" ")
                    Log.i(TAG, "sms: read \"$tryName\" as the name and the rest as the message")
                    break
                }
            }
        }
        when (val found = match) {
            is MatchResult.Single -> {
                val who = found.candidate
                if (message.isNullOrBlank()) {
                    c.ask("What should I say to ${who.name}?", quiet = true) { answer ->
                        if (answer.isBlank()) c.reply("I didn't catch the message.") else confirm(who, SmsText.tidy(answer))
                    }
                } else {
                    confirm(who, SmsText.tidy(message))
                }
            }
            is MatchResult.Ambiguous ->
                c.reply("I found ${found.candidates.joinToString(" and ") { it.name }}. Please say the full name.")
            MatchResult.None -> c.reply("I couldn't find $spoken in your contacts.")
        }
    }

    private fun confirm(who: Candidate, body: String) {
        Log.i(TAG, "sms: confirming a ${body.length}-character message to ${who.name}")
        c.askYesNo("Send to ${who.name}: $body. Shall I send it?", quiet = true) {
            c.pauseWatchdog() // the phone's report can take a few seconds; we have our own 20 s limit
            c.publish("Sending...", DriveState.THINKING)
            actions.sendSms(who.number, body) { result ->
                c.reply(
                    when (result) {
                        PhoneActions.SmsResult.SENT -> "Message sent to ${who.name}."
                        PhoneActions.SmsResult.FAILED -> "I couldn't send that message."
                        PhoneActions.SmsResult.UNCONFIRMED ->
                            "I couldn't confirm that the message was sent. Please check your messages."
                    }
                )
            }
        }
    }
}