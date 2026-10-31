package com.sylestia.toolhubs.chime.ring

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Counts shakes to dismiss an alarm.
 *
 * A single jerk above a threshold would be dismissed by the phone being picked up off a table, so a
 * dismissal needs a *streak*: several distinct shakes, each separated by a short gap, inside a short
 * window. The gravity vector is low-pass filtered out first — otherwise holding the phone at an
 * angle reads as a permanent jolt — which leaves the linear acceleration of an actual shake.
 *
 * The streak is dropped if the shakes stop, so shaking, putting the phone down and picking it up
 * again does not add up to a dismissal.
 */
class ShakeWatcher(
    context: Context,
    private val requiredShakes: Int = DEFAULT_REQUIRED_SHAKES,
    private val onShaken: () -> Unit,
) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gravity = FloatArray(3)
    private var shakes = 0
    private var lastShakeAt = 0L
    private var streakStartedAt = 0L

    fun start() {
        val sensor = accelerometer ?: return
        runCatching { sensors?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        runCatching { sensors?.unregisterListener(this) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val alpha = 0.8f
        gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

        val x = event.values[0] - gravity[0]
        val y = event.values[1] - gravity[1]
        val z = event.values[2] - gravity[2]
        val force = sqrt(x * x + y * y + z * z)
        if (force < SHAKE_THRESHOLD) return

        val now = System.currentTimeMillis()
        if (now - lastShakeAt < SHAKE_GAP_MS) return
        lastShakeAt = now
        if (now - streakStartedAt > STREAK_WINDOW_MS) {
            streakStartedAt = now
            shakes = 0
        }
        shakes++
        if (shakes >= requiredShakes) {
            shakes = 0
            streakStartedAt = 0L
            onShaken()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Shakes completed so far towards the requirement, for the "3 of 6" hint on screen. */
    fun progress(): Int = shakes

    companion object {
        /** Roughly 1.9 g of linear acceleration — an unmistakable shake, not a jostle. */
        private const val SHAKE_THRESHOLD = 18.5f
        private const val SHAKE_GAP_MS = 180L
        private const val STREAK_WINDOW_MS = 4000L
        const val DEFAULT_REQUIRED_SHAKES = 6
    }
}
