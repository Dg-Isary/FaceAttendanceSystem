package com.facedemo.app.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 手机姿态监测：判断「是不是举起来对着人了」。
 *
 * 为什么需要它：相机一直满速采集 + 逐帧推理是整机最费电、最发热的部分。
 * 老师把手机随手放在桌上（屏幕朝上、摄像头对着天花板）时继续跑毫无意义 ——
 * 这时候把相机彻底停掉（连预览一起释放），举起来自动恢复。
 *
 * 判定方法：用重力传感器算**重力沿手机 Y 轴（屏幕上方向）的分量占比**
 *   * 竖直举起 → 重力几乎全在 Y 轴 → 占比 ≈ 1
 *   * 屏幕朝上平放在桌上 → 重力全在 Z 轴 → 占比 ≈ 0
 * 用两个阈值做迟滞（放下用 0.40、拿起用 0.55），避免临界角度反复启停。
 *
 * 重力传感器是低功耗硬件传感器，监听它的耗电可以忽略。
 */
class TiltMonitor(
    context: Context,
    private val onFlatChanged: (flat: Boolean) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "TiltMonitor"

        /** 低于这个占比认为“放平了”（约 24° 以下） */
        private const val FLAT_THRESHOLD = 0.40f

        /** 高于这个占比认为“举起来了”（约 33° 以上） */
        private const val UPRIGHT_THRESHOLD = 0.55f
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gravitySensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** 用户手动关掉自动暂停时置 false */
    var enabled: Boolean = true

    private var flat = false
    var isFlat: Boolean = false
        private set

    /** 最近一次算出的“举起程度” 0~1，给界面显示用 */
    var uprightRatio: Float = 1f
        private set

    val available: Boolean get() = gravitySensor != null

    fun start() {
        val sensor = gravitySensor
        if (sensor == null) {
            Log.w(TAG, "没有重力/加速度传感器，自动暂停不可用")
            return
        }
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        runCatching { sensorManager?.unregisterListener(this) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val g = sqrt(x * x + y * y + z * z)
        if (g < 1f) return
        uprightRatio = abs(y) / g
        if (!enabled) {
            if (flat) {
                flat = false
                isFlat = false
                onFlatChanged(false)
            }
            return
        }
        // 迟滞：已经平放时要明显举起来才恢复；已经举起时要明显放下才暂停
        val next = if (flat) uprightRatio < UPRIGHT_THRESHOLD else uprightRatio < FLAT_THRESHOLD
        if (next != flat) {
            flat = next
            isFlat = next
            Log.i(TAG, "姿态变化: " + (if (next) "放平→暂停相机" else "举起→恢复相机") +
                String.format(" (举起度 %.2f)", uprightRatio))
            onFlatChanged(next)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
