package com.dskja.betterstreamflix.remotefind

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dskja.betterstreamflix.R
import com.dskja.betterstreamflix.databinding.ActivityRemoteFindPhoneBinding
import com.dskja.betterstreamflix.utils.ExpMotion
import kotlin.math.abs

/**
 * Phone companion: soft keyboard for TV Search + touchpad for DPAD focus.
 */
class RemoteFindPhoneActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemoteFindPhoneBinding
    private var client: RemoteFindClient? = null
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var suppressEcho = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteFindPhoneBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val ws = intent.getStringExtra(EXTRA_WS).orEmpty()
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        val code = intent.getStringExtra(EXTRA_CODE)
        if (ws.isBlank() || token.isBlank()) {
            Toast.makeText(this, R.string.remote_find_invalid_link, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        RemoteFindSession.beginPhone(token, code, ws)
        binding.tvRemoteFindTitle.text = getString(
            R.string.remote_find_phone_title_with_code,
            code ?: "······",
        )
        bindControls()
        client = RemoteFindClient(
            wsUrl = ws,
            token = token,
            onStatus = { status ->
                binding.tvRemoteFindStatus.text = when {
                    status.startsWith("welcome:") ->
                        getString(R.string.remote_find_phone_ready, status.substringAfter(':'))
                    status.startsWith("error:") ->
                        getString(R.string.remote_find_phone_error, status.substringAfter(':'))
                    else -> getString(R.string.remote_find_phone_connected)
                }
            },
            onConnected = {
                binding.tvRemoteFindStatus.setText(R.string.remote_find_phone_connected)
            },
            onDisconnected = { reason ->
                binding.tvRemoteFindStatus.text =
                    getString(R.string.remote_find_phone_disconnected, reason ?: "—")
            },
        ).also { it.connect() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindControls() {
        binding.etRemoteFindQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressEcho) return
                client?.send(RemoteFindProtocol.encodeKeySet(s?.toString().orEmpty()))
            }
        })
        binding.etRemoteFindQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                sendSearch()
                true
            } else {
                false
            }
        }
        binding.btnRemoteFindSearch.setOnClickListener {
            ExpMotion.hapticTap(it)
            sendSearch()
        }
        binding.btnRemoteFindClear.setOnClickListener {
            ExpMotion.hapticTap(it)
            suppressEcho = true
            binding.etRemoteFindQuery.setText("")
            suppressEcho = false
            client?.send(RemoteFindProtocol.encodeKeySet(""))
        }
        binding.btnRemoteFindBack.setOnClickListener {
            ExpMotion.hapticTap(it)
            client?.send(RemoteFindProtocol.encodeBack())
        }
        binding.btnRemoteFindSelect.setOnClickListener {
            ExpMotion.hapticTap(it)
            client?.send(RemoteFindProtocol.encodeTap())
        }
        binding.flRemoteFindPad.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (abs(dx) > 1f || abs(dy) > 1f) {
                        moved = true
                        client?.send(RemoteFindProtocol.encodePad(dx, dy))
                        lastX = event.x
                        lastY = event.y
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        client?.send(RemoteFindProtocol.encodeTap())
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun sendSearch() {
        val query = binding.etRemoteFindQuery.text?.toString().orEmpty()
        client?.send(RemoteFindProtocol.encodeKeySet(query))
        client?.send(RemoteFindProtocol.encodeSubmit())
    }

    override fun onDestroy() {
        client?.close()
        client = null
        if (RemoteFindSession.isPhone()) {
            RemoteFindSession.clear()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WS = "remote_find_ws"
        const val EXTRA_TOKEN = "remote_find_token"
        const val EXTRA_CODE = "remote_find_code"
    }
}
