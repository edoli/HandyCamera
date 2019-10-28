package kr.edoli.simplecameera

import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import kotlin.math.ln
import kotlin.math.log


fun SeekBar.bind(min: Int, max: Int, logScale: Boolean = false, onChange: (Int) -> Unit) {
    bind(min.toDouble(), max.toDouble(), logScale) { value ->
        onChange(value.toInt())
    }
}

fun SeekBar.bind(min: Float, max: Float, logScale: Boolean = false, onChange: (Float) -> Unit) {
    bind(min.toDouble(), max.toDouble(), logScale) { value ->
        onChange(value.toFloat())
    }
}

fun SeekBar.bind(min: Long, max: Long, logScale: Boolean = false, onChange: (Long) -> Unit) {
    bind(min.toDouble(), max.toDouble(), logScale) { value ->
        onChange(value.toLong())
    }
}

fun SeekBar.bind(min: Double, max: Double, logScale: Boolean = false, onChange: (Double) -> Unit) {
    setMax(10000)
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            var f = progress / 10000.0
            if (logScale) {
                f = ln(f + 1) / ln(2.0)
            }
            val value = f * (max - min) + min
            onChange(value)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
        }

    })
}


fun <T> Spinner.fromList(list: List<T>, onSelect: ((T) -> Unit)? = null) {
    adapter = ArrayAdapter<T>(this.context, R.layout.spinner_item, list)
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) {
        }

        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            val item = list[position]
            item?.let {
                onSelect?.invoke(list[position])
            }
        }
    }
}