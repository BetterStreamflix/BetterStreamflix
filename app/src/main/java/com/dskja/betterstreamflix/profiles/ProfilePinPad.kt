package com.dskja.betterstreamflix.profiles

import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.utils.ExpMotion

/** Numeric lounge PIN pad bound onto [R.layout.dialog_profile_pin]. */
object ProfilePinPad {

    fun bind(
        root: View,
        onSubmit: (String) -> Boolean,
        onCancel: () -> Unit,
    ) {
        val input = root.findViewById<EditText>(R.id.et_profile_pin)
        val dots = listOf(
            R.id.v_profile_pin_dot_1,
            R.id.v_profile_pin_dot_2,
            R.id.v_profile_pin_dot_3,
            R.id.v_profile_pin_dot_4,
            R.id.v_profile_pin_dot_5,
            R.id.v_profile_pin_dot_6,
            R.id.v_profile_pin_dot_7,
            R.id.v_profile_pin_dot_8,
        ).mapNotNull { root.findViewById<View>(it) }

        fun buffer(): String = input?.text?.toString().orEmpty()

        fun refreshDots() {
            val len = buffer().length
            dots.forEachIndexed { index, dot ->
                val on = index < len
                dot.setBackgroundResource(
                    if (on) R.drawable.bg_profile_pin_dot_on else R.drawable.bg_profile_pin_dot_off,
                )
                dot.alpha = if (on) 1f else 0.45f
            }
            val extra = root.findViewById<View>(R.id.ll_profile_pin_dots_extra)
            extra?.visibility = if (len > 4) View.VISIBLE else View.GONE
        }

        fun setBuffer(value: String) {
            input?.setText(value)
            input?.setSelection(value.length)
            refreshDots()
        }

        fun append(digit: String) {
            val current = buffer()
            if (current.length >= ProfileManager.PIN_MAX_LENGTH) return
            ExpMotion.hapticTap(root)
            setBuffer(current + digit)
        }

        fun backspace() {
            val current = buffer()
            if (current.isEmpty()) return
            ExpMotion.hapticTap(root)
            setBuffer(current.dropLast(1))
        }

        val keys = mapOf(
            R.id.btn_pin_1 to "1",
            R.id.btn_pin_2 to "2",
            R.id.btn_pin_3 to "3",
            R.id.btn_pin_4 to "4",
            R.id.btn_pin_5 to "5",
            R.id.btn_pin_6 to "6",
            R.id.btn_pin_7 to "7",
            R.id.btn_pin_8 to "8",
            R.id.btn_pin_9 to "9",
            R.id.btn_pin_0 to "0",
        )
        keys.forEach { (id, digit) ->
            root.findViewById<TextView>(id)?.setOnClickListener { append(digit) }
        }
        root.findViewById<View>(R.id.btn_pin_back)?.setOnClickListener { backspace() }
        root.findViewById<View>(R.id.btn_pin_ok)?.setOnClickListener {
            ExpMotion.hapticTap(it)
            if (!onSubmit(buffer())) {
                ExpMotion.shake(root.findViewById(R.id.ll_profile_pin_dots))
                setBuffer("")
            }
        }
        root.findViewById<View>(R.id.btn_profile_pin_ok)?.setOnClickListener {
            ExpMotion.hapticTap(it)
            if (!onSubmit(buffer())) {
                ExpMotion.shake(root.findViewById(R.id.ll_profile_pin_dots))
                setBuffer("")
            }
        }
        root.findViewById<View>(R.id.btn_profile_pin_cancel)?.setOnClickListener {
            ExpMotion.hapticTap(it)
            onCancel()
        }
        refreshDots()
    }
}
