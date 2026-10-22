package com.dskja.betterstreamflix.cast

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL

/**
 * DLNA/UPnP casting — the path to pre-Chromecast smart TVs (Samsung Tizen
 * 2015–2018 speak DMR, not Google Cast). SSDP discovery → AVTransport SOAP.
 */
data class DlnaRenderer(
    val name: String,
    val locationUrl: String,
    val controlUrl: String,   // absolute AVTransport control URL
    val host: String,
)

class DlnaDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val wifi get() = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** One discovery round — multicast M-SEARCH, then fetch each device desc. */
    suspend fun discover(timeoutMs: Long = 4500): List<DlnaRenderer> = withContext(Dispatchers.IO) {
        val lock = wifi.createMulticastLock("bsf-ssdp").apply {
            setReferenceCounted(true)
            acquire()
        }
        try {
            val locations = msearch(timeoutMs)
            coroutineScope {
                locations.map { loc -> async { describe(loc) } }.awaitAll().filterNotNull()
            }
        } finally {
            lock.release()
        }
    }

    private fun msearch(timeoutMs: Long): List<String> {
        val msg = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
            ).toByteArray()
        val out = mutableListOf<String>()
        DatagramSocket().use { sock ->
            sock.soTimeout = 400
            sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName("239.255.255.250"), 1900))
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length)
                    Regex("""LOCATION:\s*(\S+)""", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.get(1)?.trim()?.let { loc ->
                            if (loc !in out) out += loc
                        }
                } catch (_: java.net.SocketTimeoutException) {
                }
            }
        }
        return out
    }

    /** Fetch + parse the UPnP device description — keep only AVTransport renderers. */
    private suspend fun describe(location: String): DlnaRenderer? = withContext(Dispatchers.IO) {
        try {
            val xml = withTimeoutOrNull(5000) {
                URL(location).openStream().use { it.readBytes().decodeToString() }
            } ?: return@withContext null
            if (!xml.contains("AVTransport")) return@withContext null
            val name = Regex("<friendlyName>([^<]+)").find(xml)?.groupValues?.get(1)
                ?: Regex("<modelName>([^<]+)").find(xml)?.groupValues?.get(1) ?: "DLNA TV"
            // The AVTransport service block → its controlURL (relative to location base).
            val serviceBlocks = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            val ctrl = serviceBlocks.firstOrNull { "AVTransport" in it.groupValues[1] }?.let {
                Regex("<controlURL>([^<]+)").find(it.groupValues[1])?.groupValues?.get(1)
            } ?: return@withContext null
            val base = URL(location)
            val absolute = if (ctrl.startsWith("http")) ctrl
            else "${base.protocol}://${base.host}:${base.port}${if (ctrl.startsWith("/")) "" else "/"}$ctrl"
            DlnaRenderer(name, location, absolute, base.host)
        } catch (_: Exception) {
            null
        }
    }
}

object DlnaController {

    suspend fun setUri(renderer: DlnaRenderer, uri: String, title: String, mime: String): Boolean =
        withContext(Dispatchers.IO) {
            val meta = """
                |<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                |  xmlns:dc="http://purl.org/dc/elements/1.1/"
                |  xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
                |  <item id="0" parentID="-1" restricted="1">
                |    <dc:title>${xml(title)}</dc:title>
                |    <upnp:class>object.item.videoItem</upnp:class>
                |    <res protocolInfo="http-get:*:$mime:*">${xml(uri)}</res>
                |  </item>
                |</DIDL-Lite>""".trimMargin().replace("\n", "")
            soap(
                renderer, "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>${xml(uri)}</CurrentURI>" +
                    "<CurrentURIMetaData>${xml(meta)}</CurrentURIMetaData>",
            ) != null
        }

    suspend fun play(renderer: DlnaRenderer): Boolean = withContext(Dispatchers.IO) {
        soap(renderer, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>") != null
    }

    suspend fun stop(renderer: DlnaRenderer): Boolean = withContext(Dispatchers.IO) {
        soap(renderer, "Stop", "<InstanceID>0</InstanceID>") != null
    }

    /** Seconds the TV has consumed — used to resume locally on disconnect. */
    suspend fun positionSeconds(renderer: DlnaRenderer): Long? = withContext(Dispatchers.IO) {
        soap(renderer, "GetPositionInfo", "<InstanceID>0</InstanceID>")?.let { body ->
            Regex("<RelTime>([^<]+)").find(body)?.groupValues?.get(1)?.let(::parseHms)
        }
    }

    private fun parseHms(hms: String): Long {
        val p = hms.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (p.size) {
            3 -> p[0] * 3600 + p[1] * 60 + p[2]
            2 -> p[0] * 60 + p[1]
            else -> p.firstOrNull() ?: 0
        }
    }

    private fun soap(renderer: DlnaRenderer, action: String, inner: String): String? {
        val env = """<?xml version="1.0" encoding="utf-8"?>
            |<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
            | s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            |<s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            |$inner</u:$action></s:Body></s:Envelope>""".trimMargin()
        return try {
            val conn = URL(renderer.controlUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            conn.outputStream.use { it.write(env.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) conn.inputStream.readBytes().decodeToString() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun xml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
}
