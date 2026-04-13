package com.localcam.stream.streaming

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView
import kotlin.math.roundToInt

class AspectRatioTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs) {

    enum class ScaleMode {
        FIT,
        FILL
    }

    private var ratioWidth = 16
    private var ratioHeight = 9
    private var scaleMode = ScaleMode.FIT

    fun setAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    fun setScaleMode(mode: ScaleMode) {
        if (scaleMode == mode) return
        scaleMode = mode
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (scaleMode == ScaleMode.FILL) {
            setMeasuredDimension(width, height)
            return
        }
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height)
            return
        }
        val desiredHeight = (width.toFloat() * ratioHeight / ratioWidth).roundToInt()
        if (desiredHeight <= height) {
            setMeasuredDimension(width, desiredHeight)
        } else {
            val desiredWidth = (height.toFloat() * ratioWidth / ratioHeight).roundToInt()
            setMeasuredDimension(desiredWidth, height)
        }
    }
}
