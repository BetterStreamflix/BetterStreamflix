package com.dskja.betterstreamflix.remotefind

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * UDP LAN discovery so a phone can join with only the 6-char TV code.
 *
 * Phone broadcasts `remotefind:discover`.
 * TV replies `remotefind:offer:<code>:<token>:<wsUrl>` when codes match (or always for offer).
 */
object RemoteFindDiscovery {
    const val PORT = 47_821
    private const val TAG = "RemoteFindDisco"
    private const val DISCOVER = "remotefind:discover"
    private const val OFFER_PREFIX = "remotefind:offer:"

    data class Offer(
        val code: String,
        val token: String,
        val wsUrl: String,
        val fromHost: String,
    )

    class Responder(
        private val code: String,
        private val token: String,
        private val wsUrl: String,
    ) {
        private val running = AtomicBoolean(false)
        private var socket: DatagramSocket? = null
        private var worker: Thread? = null

        fun start() {
            if (!running.compareAndSet(false, true)) return
            worker = thread(name = "RemoteFindDisco-TV", isDaemon = true) {
                runCatching {
                    val sock = DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(PORT))
                        soTimeout = 1_000
                    }
                    socket = sock
                    val buffer = ByteArray(512)
                    while (running.get()) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            sock.receive(packet)
                            val text = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                                .trim()
                            if (!text.equals(DISCOVER, ignoreCase = true) &&
                                !text.startsWith("$DISCOVER:")
                            ) {
                                continue
                            }
                            val wanted = text.substringAfter(':', "").trim().uppercase()
                            if (wanted.isNotEmpty() &&
                                RemoteFindCode.normalize(wanted) != RemoteFindCode.normalize(code)
                            ) {
                                continue
                            }
                            val offer = "$OFFER_PREFIX$code:$token:$wsUrl"
                            val bytes = offer.toByteArray(StandardCharsets.UTF_8)
                            sock.send(
                                DatagramPacket(
                                    bytes,
                                    bytes.size,
                                    packet.address,
                                    packet.port,
                                ),
                            )
                        } catch (_: SocketTimeoutException) {
                            // keep listening
                        } catch (t: Throwable) {
                            if (running.get()) Log.w(TAG, "responder loop", t)
                        }
                    }
                }.onFailure {
                    Log.e(TAG, "responder failed", it)
                }
                runCatching { socket?.close() }
                socket = null
                running.set(false)
            }
        }

        fun stop() {
            running.set(false)
            runCatching { socket?.close() }
            worker = null
        }
    }

    fun discover(
        code: String,
        timeoutMs: Long = 3_500,
    ): Offer? {
        val wanted = RemoteFindCode.normalize(code) ?: return null
        return runCatching {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 700
                val payload = "$DISCOVER:$wanted".toByteArray(StandardCharsets.UTF_8)
                val destinations = listOf(
                    InetAddress.getByName("255.255.255.255"),
                    InetAddress.getByName("192.168.255.255"),
                )
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    for (dest in destinations) {
                        runCatching {
                            sock.send(DatagramPacket(payload, payload.size, dest, PORT))
                        }
                    }
                    val buffer = ByteArray(1024)
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        sock.receive(packet)
                        val text = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                            .trim()
                        val offer = parseOffer(text, packet.address.hostAddress.orEmpty())
                        if (offer != null &&
                            RemoteFindCode.normalize(offer.code) == wanted
                        ) {
                            return offer
                        }
                    } catch (_: SocketTimeoutException) {
                        // retry broadcast
                    }
                }
                null
            }
        }.onFailure {
            Log.e(TAG, "discover failed", it)
        }.getOrNull()
    }

    fun parseOffer(raw: String, fromHost: String = ""): Offer? {
        if (!raw.startsWith(OFFER_PREFIX)) return null
        val body = raw.removePrefix(OFFER_PREFIX)
        // code:token:wsUrl — wsUrl may contain ':'
        val first = body.indexOf(':')
        val second = body.indexOf(':', startIndex = first + 1)
        if (first <= 0 || second <= first) return null
        val code = RemoteFindCode.normalize(body.substring(0, first)) ?: return null
        val token = body.substring(first + 1, second).trim()
        val wsUrl = body.substring(second + 1).trim()
        if (!RemoteFindLinks.isValidToken(token)) return null
        if (!wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) return null
        return Offer(code = code, token = token, wsUrl = wsUrl, fromHost = fromHost)
    }
}
