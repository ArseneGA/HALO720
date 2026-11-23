package fr.halo720.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Niveau électronique : angle de roulis (degrés) du téléphone dans l'orientation affichée,
 * à partir du capteur de gravité. null quand le téléphone est posé à plat.
 */
@Composable
fun rememberLevel(enabled: Boolean): State<Float?> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(enabled) {
        if (!enabled) { state.value = null; return@DisposableEffect onDispose {} }
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var smooth = 0f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                val g = sqrt(x * x + y * y + z * z)
                if (g < 1f || abs(z) / g > 0.9f) { state.value = null; return }   // à plat
                @Suppress("DEPRECATION")
                val rot = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                val (sx, sy) = when (rot) {
                    Surface.ROTATION_90 -> -y to x
                    Surface.ROTATION_180 -> -x to -y
                    Surface.ROTATION_270 -> y to -x
                    else -> x to y
                }
                val a = Math.toDegrees(atan2(sx.toDouble(), sy.toDouble())).toFloat()
                smooth = smooth * 0.8f + a * 0.2f
                state.value = smooth
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sensor?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm.unregisterListener(l) }
    }
    return state
}
