package com.dskja.betterstreamflix.remotefind

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * UDP LAN discovery so a phone can join with only the 6-char TV code.
 *
 * Phone broadcasts `remotefind:discover` or `remotefind:discover:<code>`.
 * TV replies `remotefind:offer:<code>:<wsUrl>` when codes match (token is never in UDP —
 * phone must complete WebSocket `hello:<code>` first; see [RemoteFindProtocol]).
 */
object RemoteFindDiscovery {
    const val PORT = 47_821
    private const val TAG = "RemoteFindDisco"
    const val DISCOVER = "remotefind:discover"
    private const val OFFER_PREFIX = "remotefind:offer:"

    data class Offer(
        val code: String,
        val wsUrl: String,
        val fromHost: String,
    )

    class Responder(
        private val code: String,
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
                            val wanted = when {
                                text.equals(DISCOVER, ignoreCase = true) -> ""
                                text.startsWith("$DISCOVER:", ignoreCase = true) ->
                                    text.substring(DISCOVER.length + 1).trim()
                                else -> continue
                            }
                            if (wanted.isNotEmpty() &&
                                RemoteFindCode.normalize(wanted) != RemoteFindCode.normalize(code)
                            ) {
                                continue
                            }
                            val offer = "$OFFER_PREFIX$code:$wsUrl"
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
                val destinations = directedBroadcasts()
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

    /**
     * Parse `remotefind:offer:<code>:<wsUrl>` (token-less).
     * Legacy `remotefind:offer:<code>:<token>:<wsUrl>` is also accepted by treating
     * the middle segment as disposable when the last segment looks like a ws URL.
     */
    fun parseOffer(raw: String, fromHost: String = ""): Offer? {
        if (!raw.startsWith(OFFER_PREFIX)) return null
        val body = raw.removePrefix(OFFER_PREFIX)
        val first = body.indexOf(':')
        if (first <= 0) return null
        val code = RemoteFindCode.normalize(body.substring(0, first)) ?: return null
        val rest = body.substring(first + 1).trim()
        // Prefer last `ws://` / `wss://` segment so legacy token:wsUrl still works.
        val wsIdx = rest.indexOf("ws://").takeIf { it >= 0 }
            ?: rest.indexOf("wss://").takeIf { it >= 0 }
            ?: return null
        val wsUrl = rest.substring(wsIdx).trim()
        if (!wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) return null
        return Offer(code = code, wsUrl = wsUrl, fromHost = fromHost)
    }

    /** Wanted-code extraction used by unit tests (mirrors Responder). */
    fun parseDiscoverWanted(text: String): String? {
        val trimmed = text.trim()
        return when {
            trimmed.equals(DISCOVER, ignoreCase = true) -> ""
            trimmed.startsWith("$DISCOVER:", ignoreCase = true) ->
                trimmed.substring(DISCOVER.length + 1).trim()
            else -> null
        }
    }

    fun directedBroadcasts(): List<InetAddress> {
        val out = linkedSetOf<InetAddress>()
        runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .asSequence()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
                .flatMap { ni -> ni.interfaceAddresses.asSequence() }
                .forEach { ia ->
                    val addr = ia.address as? Inet4Address ?: return@forEach
                    val prefix = ia.networkPrefixLength.toInt()
                    if (prefix !in 1..30) return@forEach
                    val ip = addr.address
                    if (ip.size != 4) return@forEach
                    val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
                    val hostInt = ((ip[0].toInt() and 0xff) shl 24) or
                        ((ip[1].toInt() and 0xff) shl 16) or
                        ((ip[2].toInt() and 0xff) shl 8) or
                        (ip[3].toInt() and 0xff)
                    val bcast = (hostInt and mask) or mask.inv()
                    val bytes = byteArrayOf(
                        ((bcast ushr 24) and 0xff).toByte(),
                        ((bcast ushr 16) and 0xff).toByte(),
                        ((bcast ushr 8) and 0xff).toByte(),
                        (bcast and 0xff).toByte(),
                    )
                    runCatching { out.add(InetAddress.getByAddress(bytes)) }
                }
        }
        // Common directed fallbacks when interface enumeration is sparse.
        runCatching { out.add(InetAddress.getByName("192.168.255.255")) }
        runCatching { out.add(InetAddress.getByName("10.255.255.255")) }
        runCatching { out.add(InetAddress.getByName("172.31.255.255")) }
        return out.toList()
    }
}
