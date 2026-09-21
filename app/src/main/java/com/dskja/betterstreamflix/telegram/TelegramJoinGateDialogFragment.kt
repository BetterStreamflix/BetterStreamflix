package com.dskja.betterstreamflix.telegram

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.DialogTelegramJoinGateMobileBinding

/**
 * DialogFragment community invite host (fallback). Prefer [TelegramJoinGateOverlay].
 */
class TelegramJoinGateDialogFragment : DialogFragment() {

    private var _binding: DialogTelegramJoinGateMobileBinding? = null
    private var binder: TelegramJoinGateUiBinder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.TelegramJoinGateTheme)
        isCancelable = true
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return object : Dialog(requireContext(), theme) {
            @Deprecated("Deprecated in Java")
            override fun onBackPressed() {
                binder?.dismiss() ?: dismissAllowingStateLoss()
            }
        }.apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setCanceledOnTouchOutside(false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        if (!TelegramJoinGatePolicy.shouldShowGate()) {
            dismissAllowingStateLoss()
            return View(requireContext())
        }
        TelegramJoinGateController.onGateActivityStarted()
        _binding = DialogTelegramJoinGateMobileBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = _binding ?: return

        dialog?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes?.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }

        binder = TelegramJoinGateUiBinder(
            binding = binding,
            host = object : TelegramJoinGateUiBinder.Host {
                override fun context(): Context = requireContext()
                override fun lifecycleOwner(): LifecycleOwner = viewLifecycleOwner
                override fun isHostAlive(): Boolean = isAdded && _binding != null
                override fun onDismissed() {
                    val host = activity
                    dismissAllowingStateLoss()
                    if (host is FragmentActivity) {
                        TelegramJoinGateController.onGateFinished(host)
                    }
                }
            },
        ).also { it.bind() }

        Log.i(TAG, "community invite dialog shown")
    }

    override fun onStart() {
        super.onStart()
        TelegramJoinGateController.onGateActivityStarted()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    override fun onResume() {
        super.onResume()
        binder?.onResume()
    }

    override fun onStop() {
        TelegramJoinGateController.onGateActivityStopped()
        super.onStop()
    }

    override fun onDestroyView() {
        binder?.destroy()
        binder = null
        TelegramJoinGateController.onGateActivityDestroyed()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "TelegramJoinGateDialog"
        private const val FRAGMENT_TAG = "telegram_join_gate_dialog"

        fun show(activity: FragmentActivity): Boolean {
            // Prefer the hard overlay — DialogFragment is legacy fallback only.
            return TelegramJoinGateOverlay.show(activity)
        }
    }
}
