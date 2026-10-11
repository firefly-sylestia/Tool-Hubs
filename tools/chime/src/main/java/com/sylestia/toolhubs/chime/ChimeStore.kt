package com.sylestia.toolhubs.chime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything Chime remembers, in one private preference file.
 *
 * The lists are kept as JSON rather than as a database because they hold tens of rows, not
 * thousands, and because a preference file survives an app update without a migration step. Every
 * read is defensive: a file written by an older version, or edited by hand, must not be able to
 * crash the screen that shows the alarms.
 */
class ChimeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- alarms

    fun alarms(): List<AlarmItem> {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::readAlarm)
            }
        }.getOrDefault(emptyList())
    }

    fun saveAlarm(item: AlarmItem) = writeAlarms(upsert(alarms(), item) { it.id == item.id })

    fun deleteAlarm(id: Long) = writeAlarms(alarms().filterNot { it.id == id })

    fun setAlarmEnabled(id: Long, enabled: Boolean) =
        writeAlarms(alarms().map { if (it.id == id) it.copy(enabled = enabled) else it })

    private fun writeAlarms(items: List<AlarmItem>) {
        val array = JSONArray()
        items.sortedWith(compareBy({ it.hour }, { it.minute })).forEach { alarm ->
            array.put(JSONObject().apply {
                put("id", alarm.id)
                put("hour", alarm.hour)
                put("minute", alarm.minute)
                put("label", alarm.label)
                put("days", alarm.days.joinToString(","))
                put("enabled", alarm.enabled)
                put("dismiss", alarm.dismissMode.name)
                put("scanCode", alarm.scanCode ?: JSONObject.NULL)
                put("snooze", alarm.snoozeMinutes)
                put("vibrate", alarm.vibrate)
            })
        }
        prefs.edit().putString(KEY_ALARMS, array.toString()).apply()
    }

    private fun readAlarm(json: JSONObject) = AlarmItem(
        id = json.optLong("id", 0L),
        hour = json.optInt("hour", 7).coerceIn(0, 23),
        minute = json.optInt("minute", 0).coerceIn(0, 59),
        label = json.optString("label", ""),
        days = json.optString("days", "")
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .toSet(),
        enabled = json.optBoolean("enabled", true),
        dismissMode = enumOr(json.optString("dismiss"), DismissMode.BUTTON),
        scanCode = if (json.isNull("scanCode")) null else json.optString("scanCode", "").ifBlank { null },
        snoozeMinutes = json.optInt("snooze", 5).coerceIn(1, 60),
        vibrate = json.optBoolean("vibrate", true),
    )

    // ------------------------------------------------------------- reminders

    fun reminders(): List<ReminderItem> {
        val raw = prefs.getString(KEY_REMINDERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::readReminder)
            }
        }.getOrDefault(emptyList())
    }

    fun saveReminder(item: ReminderItem) = writeReminders(upsert(reminders(), item) { it.id == item.id })

    fun deleteReminder(id: Long) = writeReminders(reminders().filterNot { it.id == id })

    fun setReminderEnabled(id: Long, enabled: Boolean) =
        writeReminders(reminders().map { if (it.id == id) it.copy(enabled = enabled) else it })

    private fun writeReminders(items: List<ReminderItem>) {
        val array = JSONArray()
        items.sortedBy { it.atMillis }.forEach { reminder ->
            array.put(JSONObject().apply {
                put("id", reminder.id)
                put("title", reminder.title)
                put("note", reminder.note)
                put("at", reminder.atMillis)
                put("repeat", reminder.repeat.name)
                put("enabled", reminder.enabled)
                put("dismiss", reminder.dismissMode.name)
                put("scanCode", reminder.scanCode ?: JSONObject.NULL)
                put("snooze", reminder.snoozeMinutes)
            })
        }
        prefs.edit().putString(KEY_REMINDERS, array.toString()).apply()
    }

    private fun readReminder(json: JSONObject) = ReminderItem(
        id = json.optLong("id", 0L),
        title = json.optString("title", "Reminder"),
        note = json.optString("note", ""),
        atMillis = json.optLong("at", 0L),
        repeat = enumOr(json.optString("repeat"), RepeatRule.ONCE),
        enabled = json.optBoolean("enabled", true),
        dismissMode = enumOr(json.optString("dismiss"), DismissMode.BUTTON),
        scanCode = if (json.isNull("scanCode")) null else json.optString("scanCode", "").ifBlank { null },
        snoozeMinutes = json.optInt("snooze", 5).coerceIn(1, 60),
    )

    // -------------------------------------------------------------- settings

    fun settings(): ChimeSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return ChimeSettings.DEFAULT
        return runCatching {
            val json = JSONObject(raw)
            val fallback = ChimeSettings.DEFAULT
            ChimeSettings(
                bellMode = enumOr(json.optString("bellMode"), fallback.bellMode),
                bellTimes = json.optJSONArray("bellTimes").toIntList(),
                bellEnabled = json.optBoolean("bell", fallback.bellEnabled),
                speakTime = json.optBoolean("speak", fallback.speakTime),
                // -1 is stored for "follow the device", which is not the same answer as either false
                // or true and must not be flattened into one of them on the way back in.
                use24Hour = when (json.optInt("use24", -1)) {
                    0 -> false
                    1 -> true
                    else -> null
                },
                quietHoursEnabled = json.optBoolean("quiet", fallback.quietHoursEnabled),
                quietFromHour = json.optInt("quietFrom", fallback.quietFromHour).coerceIn(0, 23),
                quietToHour = json.optInt("quietTo", fallback.quietToHour).coerceIn(0, 23),
                defaultSnoozeMinutes = json.optInt("snooze", fallback.defaultSnoozeMinutes).coerceIn(1, 60),
                defaultDismissMode = enumOr(json.optString("dismiss"), fallback.defaultDismissMode),
                rampSeconds = json.optInt("ramp", fallback.rampSeconds).coerceIn(0, 120),
                ringTimeoutMinutes = json.optInt("timeout", fallback.ringTimeoutMinutes).coerceIn(1, 60),
            )
        }.getOrDefault(ChimeSettings.DEFAULT)
    }

    fun saveSettings(settings: ChimeSettings) {
        val json = JSONObject().apply {
            put("bellMode", settings.bellMode.name)
            put("bellTimes", JSONArray(settings.bellTimes))
            put("bell", settings.bellEnabled)
            put("speak", settings.speakTime)
            put("use24", when (settings.use24Hour) { true -> 1; false -> 0; null -> -1 })
            put("quiet", settings.quietHoursEnabled)
            put("quietFrom", settings.quietFromHour)
            put("quietTo", settings.quietToHour)
            put("snooze", settings.defaultSnoozeMinutes)
            put("dismiss", settings.defaultDismissMode.name)
            put("ramp", settings.rampSeconds)
            put("timeout", settings.ringTimeoutMinutes)
        }
        prefs.edit().putString(KEY_SETTINGS, json.toString()).apply()
    }

    // ----------------------------------------------------------------- snooze

    /**
     * Snooze is stored per item so a snoozed alarm survives the service being killed, and the count
     * is stored with it: each snooze of the same alarm shortens the next one, which is what stops a
     * heavy sleeper from snoozing until noon at five minutes a time. The kind is part of the key
     * because an alarm and a reminder created in the same millisecond would otherwise share it.
     */
    fun snoozeUntil(kind: DueItem.Kind, id: Long): Long =
        prefs.getLong(snoozeKey(KEY_SNOOZE_UNTIL, kind, id), 0L)

    fun snoozeCount(kind: DueItem.Kind, id: Long): Int =
        prefs.getInt(snoozeKey(KEY_SNOOZE_COUNT, kind, id), 0)

    fun setSnooze(kind: DueItem.Kind, id: Long, untilMillis: Long, count: Int) {
        prefs.edit()
            .putLong(snoozeKey(KEY_SNOOZE_UNTIL, kind, id), untilMillis)
            .putInt(snoozeKey(KEY_SNOOZE_COUNT, kind, id), count)
            .apply()
    }

    fun clearSnooze(kind: DueItem.Kind, id: Long) {
        prefs.edit()
            .remove(snoozeKey(KEY_SNOOZE_UNTIL, kind, id))
            .remove(snoozeKey(KEY_SNOOZE_COUNT, kind, id))
            .apply()
    }

    /** Snooze actually offered for an item, shrunk by how often it has already been used. */
    fun effectiveSnoozeMinutes(kind: DueItem.Kind, id: Long, requested: Int): Int =
        (requested - snoozeCount(kind, id)).coerceAtLeast(1)

    private fun snoozeKey(prefix: String, kind: DueItem.Kind, id: Long) = "$prefix${kind.name}_$id"

    // ------------------------------------------------------------------ misc

    /** A code captured from a scan screen, so the alarm editor can attach the nearest barcode. */
    fun lastScannedCode(): String? = prefs.getString(KEY_LAST_SCAN, null)

    fun setLastScannedCode(code: String) {
        prefs.edit().putString(KEY_LAST_SCAN, code).apply()
    }

    private inline fun <T> upsert(items: List<T>, replacement: T, matches: (T) -> Boolean): List<T> =
        if (items.any(matches)) items.map { if (matches(it)) replacement else it } else items + replacement

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        E::class.java.enumConstants?.firstOrNull { it.name == name } ?: fallback

    private fun JSONArray?.toIntList(): List<Int> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optInt(it, -1).takeIf { value -> value >= 0 } }
    }

    companion object {
        const val PREFS_NAME = "chime_store"
        private const val KEY_ALARMS = "alarms"
        private const val KEY_REMINDERS = "reminders"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_SNOOZE_UNTIL = "snooze_until_"
        private const val KEY_SNOOZE_COUNT = "snooze_count_"
        private const val KEY_LAST_SCAN = "last_scan_code"
    }
}
