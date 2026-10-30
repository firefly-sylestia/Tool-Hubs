package com.sylestia.toolhubs.wallpaperstudio

enum class WallpaperTrigger(val label: String, val detail: String) {
    UNLOCKED("When you unlock", "Apply after the lock screen is dismissed"),
    CHARGING("When charging", "Apply as soon as power is connected"),
    UNPLUGGED("When unplugged", "Apply when the charger is removed"),
    WIFI_CONNECTED("When Wi-Fi connects", "Apply when the phone joins Wi-Fi"),
    WIFI_DISCONNECTED("When Wi-Fi disconnects", "Apply when Wi-Fi is no longer active"),
    LOW_BATTERY("Low battery", "Apply when Android reports low battery"),
    TIME("At a time", "Apply once a day at a chosen time"),
}

enum class WallpaperTarget(val label: String) {
    HOME("Home"),
    LOCK("Lock screen"),
    BOTH("Both"),
}

enum class WallpaperEvent {
    UNLOCKED,
    CHARGING,
    UNPLUGGED,
    WIFI_CONNECTED,
    WIFI_DISCONNECTED,
    LOW_BATTERY,
}

data class WallpaperImage(
    val id: String,
    val uri: String,
    val name: String,
)

data class WallpaperScenario(
    val id: String,
    val name: String,
    val imageId: String,
    val trigger: WallpaperTrigger,
    val target: WallpaperTarget = WallpaperTarget.BOTH,
    val enabled: Boolean = true,
    val priority: Int = 3,
    val time: String = "21:00",
)
