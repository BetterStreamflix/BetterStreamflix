package com.dskja.betterstreamflix.notifications

import com.dskja.betterstreamflix.utils.UserPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Prefs-backed snooze / hourly fire ledger for Live reminders. */
object LiveReminderStore {

    fun snoozedUntil(identityKey: String): Long {
        val raw = UserPreferences.notifyLiveSnoozeJson
        if (raw.isBlank()) return 0L
        return runCatching { JSONObject(raw).optLong(identityKey, 0L) }.getOrDefault(0L)
    }

    fun setSnooze(identityKey: String, untilMs: Long) {
        val json = readObject(UserPreferences.notifyLiveSnoozeJson)
        json.put(identityKey, untilMs)
        trim(json, max = 60)
        UserPreferences.notifyLiveSnoozeJson = json.toString()
    }

    fun clearSnooze(identityKey: String) {
        val json = readObject(UserPreferences.notifyLiveSnoozeJson)
        json.remove(identityKey)
        UserPreferences.notifyLiveSnoozeJson = if (json.length() == 0) "" else json.toString()
    }

    fun lastNotifiedMs(identityKey: String): Long {
        val raw = UserPreferences.notifyLiveCooldownJson
        if (raw.isBlank()) return 0L
        return runCatching { JSONObject(raw).optLong(identityKey, 0L) }.getOrDefault(0L)
    }

    fun markNotified(identityKey: String, atMs: Long) {
        val cool = readObject(UserPreferences.notifyLiveCooldownJson)
        cool.put(identityKey, atMs)
        trim(cool, max = 80)
        UserPreferences.notifyLiveCooldownJson = cool.toString()

        val recent = recentFires().toMutableList()
        recent += atMs
        UserPreferences.notifyLiveRecentFiresJson =
            JSONArray(LiveReminderPolicy.pruneRecentFires(recent, atMs)).toString()
    }

    fun recentFires(): List<Long> {
        val raw = UserPreferences.notifyLiveRecentFiresJson
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.optLong(i))
                }
            }
        }.getOrElse { emptyList() }
    }

    fun kickoffFingerprint(identityKey: String): String? {
        val raw = UserPreferences.notifyLiveKickoffFpJson
        if (raw.isBlank()) return null
        return runCatching {
            JSONObject(raw).optString(identityKey, "").ifBlank { null }
        }.getOrNull()
    }

    fun setKickoffFingerprint(identityKey: String, fingerprint: String) {
        val json = readObject(UserPreferences.notifyLiveKickoffFpJson)
        json.put(identityKey, fingerprint)
        trim(json, max = 80)
        UserPreferences.notifyLiveKickoffFpJson = json.toString()
    }

    private fun readObject(raw: String): JSONObject {
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    /** Drop oldest entries by timestamp value (lastNotified / snooze-until / kickoff epoch). */
    private fun trim(json: JSONObject, max: Int) {
        if (json.length() <= max) return
        val overflow = json.length() - max
        val oldestFirst = json.keys().asSequence().toList().sortedBy { key ->
            when (val v = json.opt(key)) {
                is Number -> v.toLong()
                is String -> v.toLongOrNull() ?: 0L
                else -> 0L
            }
        }
        oldestFirst.take(overflow).forEach { json.remove(it) }
    }
}
