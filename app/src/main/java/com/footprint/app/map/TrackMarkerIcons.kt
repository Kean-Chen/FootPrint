package com.footprint.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.annotation.ColorInt
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import kotlin.math.roundToInt

internal object TrackMarkerIcons {
    fun start(context: Context, scale: Float = 1f): BitmapDescriptor = create(
        context = context,
        color = 0xFF2F6B55.toInt(),
        symbol = MarkerSymbol.START_FLAG,
        scale = scale,
    )

    fun finish(context: Context, scale: Float = 1f): BitmapDescriptor = create(
        context = context,
        color = 0xFFD8433B.toInt(),
        symbol = MarkerSymbol.FINISH_FLAG,
        scale = scale,
    )

    fun moment(context: Context, scale: Float = 1f): BitmapDescriptor = create(
        context = context,
        color = 0xFF7952B8.toInt(),
        symbol = MarkerSymbol.CAMERA,
        scale = scale,
    )

    private fun create(
        context: Context,
        @ColorInt color: Int,
        symbol: MarkerSymbol,
        scale: Float,
    ): BitmapDescriptor {
        val density = context.resources.displayMetrics.density
        fun px(dp: Float) = dp * density * scale

        val bitmap = Bitmap.createBitmap(
            px(44f).roundToInt(),
            px(54f).roundToInt(),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        val centerX = bitmap.width / 2f
        val centerY = px(19f)

        val pinPath = Path().apply {
            moveTo(centerX, px(53f))
            cubicTo(px(16f), px(43f), px(2f), px(33f), px(2f), centerY)
            cubicTo(px(2f), px(7f), px(11f), px(2f), centerX, px(2f))
            cubicTo(px(33f), px(2f), px(42f), px(7f), px(42f), centerY)
            cubicTo(px(42f), px(33f), px(28f), px(43f), centerX, px(53f))
            close()
        }
        canvas.drawPath(
            pinPath,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = color
            },
        )
        canvas.drawPath(
            pinPath,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = px(2f)
                this.color = android.graphics.Color.WHITE
            },
        )

        val whiteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = android.graphics.Color.WHITE
        }
        val whiteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px(2f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = android.graphics.Color.WHITE
        }
        when (symbol) {
            MarkerSymbol.START_FLAG -> drawStartFlag(canvas, centerX, centerY, ::px, whiteFill, whiteStroke)
            MarkerSymbol.FINISH_FLAG -> drawFinishFlag(canvas, centerX, centerY, ::px, whiteFill, whiteStroke)
            MarkerSymbol.CAMERA -> drawCamera(canvas, centerX, centerY, ::px, whiteStroke)
        }
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun drawStartFlag(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        px: (Float) -> Float,
        fill: Paint,
        stroke: Paint,
    ) {
        val poleX = centerX - px(5f)
        canvas.drawLine(poleX, centerY + px(8f), poleX, centerY - px(8f), stroke)
        val flag = Path().apply {
            moveTo(poleX + px(1f), centerY - px(8f))
            lineTo(centerX + px(7f), centerY - px(8f))
            lineTo(centerX + px(3f), centerY - px(3f))
            lineTo(poleX + px(1f), centerY - px(3f))
            close()
        }
        canvas.drawPath(flag, fill)
    }

    private fun drawFinishFlag(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        px: (Float) -> Float,
        fill: Paint,
        stroke: Paint,
    ) {
        val poleX = centerX - px(7f)
        canvas.drawLine(poleX, centerY + px(8f), poleX, centerY - px(8f), stroke)
        val flag = RectF(poleX + px(1f), centerY - px(8f), centerX + px(8f), centerY + px(1f))
        canvas.drawRect(flag, fill)
        val squarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF68211D.toInt()
        }
        val cell = px(4f)
        canvas.drawRect(flag.left, flag.top, flag.left + cell, flag.top + cell, squarePaint)
        canvas.drawRect(flag.left + cell, flag.top + cell, flag.left + cell * 2f, flag.top + cell * 2f, squarePaint)
        canvas.drawRect(flag, stroke)
    }

    private fun drawCamera(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        px: (Float) -> Float,
        stroke: Paint,
    ) {
        val body = RectF(
            centerX - px(8f),
            centerY - px(4f),
            centerX + px(8f),
            centerY + px(6f),
        )
        canvas.drawRoundRect(body, px(2f), px(2f), stroke)
        canvas.drawLine(centerX - px(4f), centerY - px(4f), centerX - px(2f), centerY - px(7f), stroke)
        canvas.drawLine(centerX - px(2f), centerY - px(7f), centerX + px(3f), centerY - px(7f), stroke)
        canvas.drawCircle(centerX + px(1f), centerY + px(1f), px(3f), stroke)
    }

    private enum class MarkerSymbol {
        START_FLAG,
        FINISH_FLAG,
        CAMERA,
    }
}
