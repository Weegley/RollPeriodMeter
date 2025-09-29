package com.weegley.rollperiodmeter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class RollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f }
    private val paintSub  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 32f }
    private val paintArrow= Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 10f; style = Paint.Style.STROKE }
    private val paintScale= Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; strokeWidth = 4f; style = Paint.Style.STROKE }
    private val paintAccel= Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; strokeWidth = 6f; style = Paint.Style.STROKE }
    private val paintZero = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; strokeWidth = 6f; style = Paint.Style.STROKE }

    private var rollDeg = 0.0
    private var omegaDeg = 0.0
    private var alphaDeg = 0.0
    private var periodRight = Double.NaN
    private var periodLeft = Double.NaN
    private var periodAvg = Double.NaN
    private var periodHalf = Double.NaN
    private var snrDb = Double.NaN
    private var lastZeroDir = 0
    private var rightCount = 0
    private var leftCount = 0
    private var visualScale = 60.0 / 20.0
    private var useHalfPeriod = false

    fun update(
        rollDeg: Double,
        omegaDeg: Double,
        alphaDeg: Double,
        periodRight: Double,
        periodLeft: Double,
        periodAvg: Double,
        periodHalf: Double,
        snrDb: Double,
        lastZeroDir: Int,
        rightCount: Int,
        leftCount: Int,
        visualScale: Double,
        useHalfPeriod: Boolean
    ) {
        this.rollDeg = rollDeg
        this.omegaDeg = omegaDeg
        this.alphaDeg = alphaDeg
        this.periodRight = periodRight
        this.periodLeft = periodLeft
        this.periodAvg = periodAvg
        this.periodHalf = periodHalf
        this.snrDb = snrDb
        this.lastZeroDir = lastZeroDir
        this.rightCount = rightCount
        this.leftCount = leftCount
        this.visualScale = visualScale
        this.useHalfPeriod = useHalfPeriod
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()

        val cx = w / 2f
        val cy = h * 0.55f
        val r = min(w, h) * 0.35f

        for (deg in listOf(-60, -30, 0, 30, 60)) {
            val rad = Math.toRadians(deg.toDouble())
            val x = cx + (r * sin(rad)).toFloat()
            val y = cy - (r * cos(rad)).toFloat()
            c.drawCircle(x, y, 5f, paintScale)
        }

        // Visual scaling: real ±20° -> arc ±60°
        val arrowLen = r * 0.9f
        val arrowAng = Math.toRadians(rollDeg * visualScale)
        val tipX = cx + (arrowLen * sin(arrowAng)).toFloat()
        val tipY = cy - (arrowLen * cos(arrowAng)).toFloat()
        val baseX = cx - (arrowLen * 0.2f * sin(arrowAng)).toFloat()
        val baseY = cy + (arrowLen * 0.2f * cos(arrowAng)).toFloat()

        c.drawLine(baseX, baseY, tipX, tipY, paintArrow)

        val headSize = 30f
        val normal = (Math.PI / 2.0 + arrowAng)
        val hx1 = tipX + (headSize * cos(normal - 0.5)).toFloat()
        val hy1 = tipY + (headSize * sin(normal - 0.5)).toFloat()
        val hx2 = tipX + (headSize * cos(normal + 0.5)).toFloat()
        val hy2 = tipY + (headSize * sin(normal + 0.5)).toFloat()
        c.drawLine(tipX, tipY, hx1, hy1, paintArrow)
        c.drawLine(tipX, tipY, hx2, hy2, paintArrow)

        val accSign = alphaDeg.sign.toFloat()
        if (accSign != 0f) {
            val n = (Math.PI / 2.0 + arrowAng) * accSign
            val ax = tipX + (20f * cos(n)).toFloat()
            val ay = tipY + (20f * sin(n)).toFloat()
            c.drawLine(tipX, tipY, ax, ay, paintAccel)
        }

        val zeroStr = when (lastZeroDir) { +1 -> "Zero ↑"; -1 -> "Zero ↓"; else -> "" }
        if (zeroStr.isNotEmpty()) c.drawText(zeroStr, cx - 60f, cy + r + 40f, paintSub)

        var y = 80f
        c.drawText("Roll: % .1f°".format(rollDeg), 24f, y, paintText); y += 46f
        c.drawText("dRoll/dt: % .1f°/s".format(omegaDeg), 24f, y, paintText); y += 46f
        c.drawText("d²Roll/dt²: % .1f°/s²".format(alphaDeg), 24f, y, paintText); y += 46f
        val labelAvg = if (useHalfPeriod) "Period (avg, Zero×2)" else "Period (avg, Peaks)"
        c.drawText("$labelAvg: ${fmtS(periodAvg)}", 24f, y, paintText); y += 40f
        c.drawText("Period (Zero×2 raw): ${fmtS(periodHalf)}", 24f, y, paintSub); y += 36f
        c.drawText("Right side: ${fmtS(periodRight)}", 24f, y, paintSub); y += 36f
        c.drawText("Left side : ${fmtS(periodLeft)}", 24f, y, paintSub); y += 36f
        val dirStr = when { omegaDeg > 0 -> "→ starboard"; omegaDeg < 0 -> "← port"; else -> "·" }
        val accelStr = when { alphaDeg > 0 -> "accelerating →"; alphaDeg < 0 -> "accelerating ←"; else -> "accel ·" }
        val snrStr = if (snrDb.isNaN()) "—" else "%.1f dB".format(snrDb)
        c.drawText("Direction: $dirStr   Accel: $accelStr   SNR: $snrStr", 24f, y, paintSub)
    }

    private fun fmtS(v: Double): String = if (v.isNaN()) "—" else "%.2f s".format(v)
}
