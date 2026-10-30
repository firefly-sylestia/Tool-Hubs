package com.sylestia.toolhubs.wallpaperstudio

enum class WallpaperTrigger(val label: String, val detail: String) {
    UNLOCKED("When you unlock", "Apply after the lock screen is dismissed"),
    CHARGING("When charging", "Apply as soon as power is connected"),
    UNPLUGGED("When unplugged", "Apply when the charger is removed"),
    WIFI_CONNECTED("When Wi-Fi connects", "Apply when the phone joins Wi-Fi"),
    WIFI_DISCONNECTED("When Wi-Fi disconnects", "Apply when Wi-Fi is no longer active"),
    LOW_BATTERY("Low battery", "Apply when Android reports low battery"),
    BATTERY_OKAY("Battery recovered", "Apply when the low-battery warning clears"),
    TIME("At a time", "Apply once a day at a chosen time"),
    SCREEN_ON("When screen turns on", "Apply when the display wakes"),
    SCREEN_OFF("When screen turns off", "Apply when the display sleeps"),
    AIRPLANE_ON("Airplane mode on", "Apply when airplane mode is enabled"),
    AIRPLANE_OFF("Airplane mode off", "Apply when airplane mode is disabled"),
    BATTERY_SAVER_ON("Battery saver on", "Apply when power saving starts"),
    BATTERY_SAVER_OFF("Battery saver off", "Apply when power saving ends"),
    HEADPHONES_CONNECTED("Headphones connected", "Apply when wired headphones connect"),
    HEADPHONES_DISCONNECTED("Headphones disconnected", "Apply when wired headphones disconnect"),
}

enum class WallpaperFit(val label: String, val detail: String) {
    FILL("Fill", "Fill the screen and crop the edges"),
    FIT("Fit", "Show the whole image with empty space"),
    STRETCH("Stretch", "Fill the screen without cropping"),
    CENTER("Original size", "Keep the image at its natural pixel size"),
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
    BATTERY_OKAY,
    SCREEN_ON,
    SCREEN_OFF,
    AIRPLANE_ON,
    AIRPLANE_OFF,
    BATTERY_SAVER_ON,
    BATTERY_SAVER_OFF,
    HEADPHONES_CONNECTED,
    HEADPHONES_DISCONNECTED,
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
    val enabled: Boolean = false,
    val priority: Int = 3,
    val time: String = "21:00",
    val fit: WallpaperFit = WallpaperFit.FIT,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val zoom: Float = 1f,
    val rotation: Float = 0f,
)
