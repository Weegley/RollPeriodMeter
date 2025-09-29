package com.weegley.rollperiodmeter

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.*

class MainActivity : Activity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private lateinit var rollView: RollView

    private var running = false

    // Low‑pass filtered gravity vector
    private val g = FloatArray(3)
    private val alphaLPF = 0.9f

    // Time
    private var lastTimestampNs: Long = 0L

    // Regression buffer (max size); window is adjustable
    private val MAX_N = 64
    private val tBuf = DoubleArray(MAX_N) { 0.0 }   // seconds, 0 at latest
    private val thBuf = DoubleArray(MAX_N) { 0.0 }  // radians
    private var bufCount = 0
    private var currentWindow = 32
    private val windowOptions = intArrayOf(8, 16, 32)
    private var windowIndex = 2 // default 32

    // Derived states
    private var roll = 0.0         // θ, rad
    private var rollVel = 0.0      // ω, rad/s
    private var rollAcc = 0.0      // α, rad/s^2
    private var noiseRms = 0.0     // fit residual RMS
    private var ampRms = 0.0       // angle RMS in window

    // Peak/period detection
    private var lastDir = 0        // -1 left, +1 right
    private var lastRightPeakTimeNs = 0L
    private var lastLeftPeakTimeNs = 0L

    // Zero-crossing for half-period
    private var lastZeroTimeNs = 0L
    private var lastZeroDir = 0    // +1 rising, -1 falling

    // Buffers of last periods (Peaks vs Zero×2)
    private val lastPeriodsPeaks: MutableList<Double> = mutableListOf()
    private val lastPeriodsZero2: MutableList<Double> = mutableListOf()
    private val maxPeriods = 50
    private val avgOptions = intArrayOf(5, 10, 20)
    private var avgIndex = 1 // default 10

    // EMA (kept internally if needed)
    private var emaRight: Double = Double.NaN
    private var emaLeft: Double = Double.NaN
    private var emaHalf: Double = Double.NaN
    private val emaBeta = 0.2

    // Thresholds
    private val minAmplitudeRad = Math.toRadians(1.0)   // accept peaks above ~1°
    private val minOmegaRad = Math.toRadians(0.2)       // deadband
    private val minAlphaRad = Math.toRadians(2.0)       // α threshold to validate a turning point

    // Visual scaling: real ±20° -> arc ±60°
    private val visualScale = 60.0 / 20.0

    // UI mode/prefs
    private var useHalfPeriod = false
    private var flipSign = 1.0
    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)
        val root: android.widget.FrameLayout = findViewById(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            // задаём абсолютный top-padding равный высоте статус-бара
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }
        // Load prefs
        flipSign = if (prefs.getInt("flipSign", 1) >= 0) 1.0 else -1.0
        useHalfPeriod = prefs.getBoolean("useHalfPeriod", false)
        windowIndex = prefs.getInt("windowIndex", windowIndex)
        currentWindow = windowOptions[windowIndex]
        avgIndex = prefs.getInt("avgIndex", avgIndex)

        rollView = findViewById(R.id.rollView)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val btnStartStop: Button = findViewById(R.id.btnStartStop)
        val btnReset: Button = findViewById(R.id.btnReset)
        val btnMode: Button = findViewById(R.id.btnMode)
        val btnWindow: Button = findViewById(R.id.btnWindow)
        val btnFlip: Button = findViewById(R.id.btnFlip)
        val btnAvg: Button = findViewById(R.id.btnAvg)

        // initial labels
        btnStartStop.text = if (running) "Stop" else "Start"
        btnMode.text = if (useHalfPeriod) "Mode: Zero×2" else "Mode: Peaks"
        btnWindow.text = "Window: $currentWindow"
        btnAvg.text = "Avg: ${avgOptions[avgIndex]}"

        btnStartStop.setOnClickListener {
            toggle()
            btnStartStop.text = if (running) "Stop" else "Start"
        }
        btnReset.setOnClickListener { resetAll() }
        btnMode.setOnClickListener {
            useHalfPeriod = !useHalfPeriod
            btnMode.text = if (useHalfPeriod) "Mode: Zero×2" else "Mode: Peaks"
            prefs.edit().putBoolean("useHalfPeriod", useHalfPeriod).apply()
        }
        btnWindow.setOnClickListener {
            windowIndex = (windowIndex + 1) % windowOptions.size
            currentWindow = windowOptions[windowIndex]
            btnWindow.text = "Window: $currentWindow"
            prefs.edit().putInt("windowIndex", windowIndex).apply()
        }
        btnFlip.setOnClickListener {
            flipSign *= -1.0
            prefs.edit().putInt("flipSign", if (flipSign >= 0) 1 else -1).apply()
        }
        btnAvg.setOnClickListener {
            avgIndex = (avgIndex + 1) % avgOptions.size
            btnAvg.text = "Avg: ${avgOptions[avgIndex]}"
            prefs.edit().putInt("avgIndex", avgIndex).apply()
        }
    }

    private fun toggle() {
        running = !running
        if (running) {
            accelerometer?.also {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        } else {
            sensorManager.unregisterListener(this)
        }
    }

    private fun resetAll() {
        emaRight = Double.NaN
        emaLeft = Double.NaN
        emaHalf = Double.NaN
        lastRightPeakTimeNs = 0L
        lastLeftPeakTimeNs = 0L
        lastZeroTimeNs = 0L
        lastZeroDir = 0
        lastDir = 0
        lastTimestampNs = 0L
        bufCount = 0
        for (i in 0 until MAX_N) { tBuf[i] = 0.0; thBuf[i] = 0.0 }
        roll = 0.0; rollVel = 0.0; rollAcc = 0.0; noiseRms = 0.0; ampRms = 0.0
        lastPeriodsPeaks.clear()
        lastPeriodsZero2.clear()
        rollView.update(
            rollDeg = 0.0,
            omegaDeg = 0.0,
            alphaDeg = 0.0,
            periodRight = Double.NaN,
            periodLeft = Double.NaN,
            periodAvg = Double.NaN,
            periodHalf = Double.NaN,
            snrDb = Double.NaN,
            lastZeroDir = 0,
            rightCount = 0,
            leftCount = 0,
            visualScale = visualScale,
            useHalfPeriod = useHalfPeriod
        )
    }

    override fun onPause() {
        super.onPause()
        if (running) toggle()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // LPF gravity vector
        if (g[0] == 0f && g[1] == 0f && g[2] == 0f) {
            g[0] = event.values[0]; g[1] = event.values[1]; g[2] = event.values[2]
        } else {
            g[0] = alphaLPF * g[0] + (1 - alphaLPF) * event.values[0]
            g[1] = alphaLPF * g[1] + (1 - alphaLPF) * event.values[1]
            g[2] = alphaLPF * g[2] + (1 - alphaLPF) * event.values[2]
        }

        val tNs = event.timestamp
        val dt = if (lastTimestampNs == 0L) 0.0 else (tNs - lastTimestampNs).toDouble() / 1e9
        lastTimestampNs = tNs
        if (dt <= 0.0) return

        // Universal roll around X-axis: theta = atan2(gx, sqrt(gy^2 + gz^2))
        val gx = g[0].toDouble(); val gy = g[1].toDouble(); val gz = g[2].toDouble()
        val theta = atan2(gx, sqrt(gy*gy + gz*gz)) * flipSign
        roll = theta

        // Shift buffer by dt and insert new sample at 0
        val mOld = bufCount
        for (i in mOld - 1 downTo 0) {
            if (i + 1 < MAX_N) {
                tBuf[i + 1] = tBuf[i] + dt
                thBuf[i + 1] = thBuf[i]
            }
        }
        tBuf[0] = 0.0
        thBuf[0] = theta
        if (bufCount < MAX_N) bufCount++

        val m = min(bufCount, currentWindow)

        // Quadratic fit
        if (m >= 5) {
            var S0 = 0.0; var S1 = 0.0; var S2 = 0.0; var S3 = 0.0; var S4 = 0.0
            var T0 = 0.0; var T1 = 0.0; var T2 = 0.0
            for (i in 0 until m) {
                val t = tBuf[i]; val th = thBuf[i]
                val t2 = t*t; val t3 = t2*t; val t4 = t2*t2
                S0 += 1.0; S1 += t; S2 += t2; S3 += t3; S4 += t4
                T0 += th;  T1 += t*th; T2 += t2*th
            }
            val A00=S0; val A01=S1; val A02=S2
            val A10=S1; val A11=S2; val A12=S3
            val A20=S2; val A21=S3; val A22=S4
            val B0=T0;  val B1=T1;  val B2=T2
            val det = A00*(A11*A22 - A12*A21) - A01*(A10*A22 - A12*A20) + A02*(A10*A21 - A11*A20)
            if (abs(det) > 1e-12) {
                fun det3(a00:Double,a01:Double,a02:Double,a10:Double,a11:Double,a12:Double,a20:Double,a21:Double,a22:Double)=
                    a00*(a11*a22 - a12*a21) - a01*(a10*a22 - a12*a20) + a02*(a10*a21 - a11*a20)
                val detC = det3(B0,A01,A02, B1,A11,A12, B2,A21,A22)
                val detB = det3(A00,B0,A02, A10,B1,A12, A20,B2,A22)
                val detA = det3(A00,A01,B0, A10,A11,B1, A20,A21,B2)
                val c = detC / det
                val b = detB / det
                val a = detA / det
                rollVel = b
                rollAcc = 2.0*a
                // residuals
                var rss = 0.0; var sumTh2 = 0.0; var ecount = 0
                for (i in 0 until m) {
                    val t = tBuf[i]
                    val fit = a*t*t + b*t + c
                    val e = thBuf[i] - fit
                    rss += e*e; ecount += 1
                    sumTh2 += thBuf[i]*thBuf[i]
                }
                noiseRms = sqrt(rss / max(1, ecount))
                ampRms = sqrt(sumTh2 / max(1, m))
            } else {
                val prev = if (m > 1) thBuf[1] else theta
                val dth = theta - prev
                rollVel = if (abs(dth/dt) < minOmegaRad) 0.0 else dth/dt
                rollAcc = 0.0
                noiseRms = 0.0; ampRms = abs(theta)
            }
        }

        // Direction with deadband
        val omegaEff = if (abs(rollVel) < minOmegaRad) 0.0 else rollVel
        val dir = when { omegaEff > 0 -> +1; omegaEff < 0 -> -1; else -> lastDir }

        // Peak detection
        if (lastDir != 0 && dir != lastDir) {
            val amplitudeOk = abs(roll) >= minAmplitudeRad
            val decelOk = abs(rollAcc) >= minAlphaRad && sign(rollAcc) == -lastDir.toDouble()
            if (amplitudeOk && decelOk) {
                if (lastDir > 0) {
                    val period = if (lastRightPeakTimeNs != 0L) (tNs - lastRightPeakTimeNs) / 1e9 else Double.NaN
                    lastRightPeakTimeNs = tNs
                    if (!period.isNaN()) {
                        emaRight = emaUpdate(emaRight, period)
                        pushPeriod(lastPeriodsPeaks, period)
                    }
                } else {
                    val period = if (lastLeftPeakTimeNs != 0L) (tNs - lastLeftPeakTimeNs) / 1e9 else Double.NaN
                    lastLeftPeakTimeNs = tNs
                    if (!period.isNaN()) {
                        emaLeft = emaUpdate(emaLeft, period)
                        pushPeriod(lastPeriodsPeaks, period)
                    }
                }
            }
        }

        // Zero crossing: half-period = 2*delta_t(zero->zero)
        val prevTheta = if (m > 1) thBuf[1] else theta
        if ((theta >= 0 && prevTheta < 0) || (theta <= 0 && prevTheta > 0)) {
            val dirZero = if (theta >= 0 && prevTheta < 0) +1 else -1
            if (lastZeroTimeNs != 0L) {
                val half = 2.0 * ((tNs - lastZeroTimeNs) / 1e9)
                if (half.isFinite() && half > 0) {
                    emaHalf = emaUpdate(emaHalf, half)
                    pushPeriod(lastPeriodsZero2, half)
                }
            }
            lastZeroTimeNs = tNs
            lastZeroDir = dirZero
        }

        lastDir = dir

        val snr = if (noiseRms <= 1e-9) Double.POSITIVE_INFINITY else ampRms / noiseRms
        val snrDb = if (snr.isInfinite()) 99.0 else 20.0 * log10(max(snr, 1e-9))

        val nAvg = avgOptions[avgIndex]
        val avgPeaks = tailAverage(lastPeriodsPeaks, nAvg)
        val avgZero2 = tailAverage(lastPeriodsZero2, nAvg)
        val chosenAvg = if (useHalfPeriod) avgZero2 else avgPeaks

        rollView.update(
            rollDeg = Math.toDegrees(roll),
            omegaDeg = Math.toDegrees(rollVel),
            alphaDeg = Math.toDegrees(rollAcc),
            periodRight = emaRight,
            periodLeft = emaLeft,
            periodAvg = chosenAvg,
            periodHalf = emaHalf,
            snrDb = snrDb,
            lastZeroDir = lastZeroDir,
            rightCount = if (lastRightPeakTimeNs == 0L) 0 else 1,
            leftCount = if (lastLeftPeakTimeNs == 0L) 0 else 1,
            visualScale = visualScale,
            useHalfPeriod = useHalfPeriod
        )
    }

    private fun emaUpdate(curr: Double, sample: Double): Double {
        return if (curr.isNaN()) sample else (1.0 - emaBeta) * curr + emaBeta * sample
    }

    private fun pushPeriod(list: MutableList<Double>, value: Double) {
        list.add(value)
        if (list.size > maxPeriods) list.removeAt(0)
    }

    private fun tailAverage(list: MutableList<Double>, n: Int): Double {
        if (list.isEmpty()) return Double.NaN
        var sum = 0.0
        var count = 0
        var i = list.size - 1
        while (i >= 0 && count < n) {
            sum += list[i]
            count++
            i--
        }
        return if (count == 0) Double.NaN else sum / count
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
}
