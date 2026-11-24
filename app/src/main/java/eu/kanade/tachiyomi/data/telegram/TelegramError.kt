package eu.kanade.tachiyomi.data.telegram

object TelegramError {

    @Suppress("CyclomaticComplexMethod")
    fun explain(raw: String): String {
        val code = raw.trim()
        val u = code.uppercase()
        return when {
            u.contains("API_ID_INVALID") || u.contains("API_ID_PUBLISHED_FLOOD") ->
                "Your api_id / api_hash was rejected by Telegram. Copy both values from the same app on my.telegram.org."
            u.contains("PHONE_NUMBER_INVALID") ->
                "Telegram does not recognize that phone number. Include the country code, e.g. +91 98512 27864."
            u.contains("PHONE_NUMBER_BANNED") ->
                "Telegram reports this phone number is banned."
            u.contains("PHONE_NUMBER_FLOOD") || u.startsWith("FLOOD_WAIT") ->
                "Telegram asked to wait before trying again (" + code + ")."
            u.contains("PHONE_CODE_INVALID") ->
                "That code does not match the one Telegram sent. Check the digits and try again."
            u.contains("PHONE_CODE_EXPIRED") ->
                "That code has expired. Tap \"Use a different number\" to request a fresh code."
            u.contains("PASSWORD_HASH_INVALID") ->
                "Wrong two-step verification password."
            u.contains("PHONE_PASSWORD_FLOOD") ->
                "Too many wrong passwords — Telegram asked to wait before trying again."
            u.contains("UPDATE_APP_TO_LOGIN") ->
                "This Telegram library build is too old to sign in. Updating TDLib will restore login."
            u.contains("NETWORK") || u.contains("TIMEOUT") ->
                "Could not reach Telegram. Check your connection and try again."
            code.isBlank() ->
                "Telegram refused the request without saying why."
            else ->
                "Telegram said: " + code
        }
    }
}
