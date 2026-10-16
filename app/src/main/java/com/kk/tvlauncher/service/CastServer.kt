package com.kk.tvlauncher.service

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.util.Log
import com.kk.tvlauncher.ui.CastPlayActivity
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * 局域网 9978 接收投屏。
 * 除了相片查重用的 /cast/play，还按 DLNA 媒体渲染器对外广播，其它 App 搜索投屏设备时能看到「KK tvLuncher」。
 */
object CastServer {
    const val PORT = 9978
    private const val TAG = "CastServer"
    private const val NAME = "KK tvLuncher"
    private const val UDN = "uuid:3e7c1a20-8b4f-4c1a-9d2e-000000009978"
    private const val SSDP_PORT = 1900
    private const val SSDP_ADDR = "239.255.255.250"
    private val clients = Executors.newCachedThreadPool()
    @Volatile private var started = false
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var mediaUrl = ""
    @Volatile private var mediaTitle = ""
    @Volatile private var mediaKind = "image"
    @Volatile private var transport = "STOPPED"
    @Volatile var playback: Playback? = null

    interface Playback {
        fun pause()
        fun resume()
        fun stop()
        fun seekTo(positionMs: Int)
        fun positionMs(): Int
        fun durationMs(): Int
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        holdMulticast(app)
        Thread({ serveHttp(app) }, "cast-receiver").apply { isDaemon = true }.start()
        Thread({ serveSsdp() }, "cast-broadcast").apply { isDaemon = true }.start()
    }

    private fun holdMulticast(context: Context) {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("kk-cast")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        } catch (error: Exception) {
            Log.w(TAG, "多播锁没拿上", error)
        }
    }

    private fun serveHttp(context: Context) {
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("0.0.0.0", PORT))
            Log.i(TAG, "投屏接收已开 $PORT")
            while (true) {
                val socket = server.accept()
                clients.execute {
                    try {
                        handle(context, socket)
                    } catch (error: Exception) {
                        Log.w(TAG, "投屏请求失败", error)
                    } finally {
                        try { socket.close() } catch (_: Exception) { }
                    }
                }
            }
        } catch (error: Exception) {
            started = false
            Log.e(TAG, "投屏接收没能打开", error)
        }
    }

    private fun serveSsdp() {
        val group = InetAddress.getByName(SSDP_ADDR)
        while (true) {
            var socket: MulticastSocket? = null
            try {
                socket = MulticastSocket(null)
                socket.reuseAddress = true
                socket.timeToLive = 4
                socket.bind(InetSocketAddress(SSDP_PORT))
                val nif = lanInterface()
                if (nif != null) {
                    socket.joinGroup(InetSocketAddress(group, SSDP_PORT), nif)
                    socket.setNetworkInterface(nif)
                } else {
                    socket.joinGroup(group)
                }
                socket.soTimeout = 5000
                Log.i(TAG, "投屏广播已开")
                announce(socket, "ssdp:alive")
                var nextAnnounce = System.currentTimeMillis() + 30_000
                val buf = ByteArray(2048)
                while (true) {
                    if (System.currentTimeMillis() >= nextAnnounce) {
                        announce(socket, "ssdp:alive")
                        nextAnnounce = System.currentTimeMillis() + 30_000
                    }
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, StandardCharsets.ISO_8859_1)
                    if (!text.startsWith("M-SEARCH", ignoreCase = true)) continue
                    val st = header(text, "ST")
                    if (!wanted(st)) continue
                    val ip = lanIp(packet.address) ?: continue
                    val location = "http://$ip:$PORT/cast/desc.xml"
                    val replies = if (st.equals("ssdp:all", ignoreCase = true)) {
                        listOf(
                            "upnp:rootdevice" to "$UDN::upnp:rootdevice",
                            UDN to UDN,
                            "urn:schemas-upnp-org:device:MediaRenderer:1" to "$UDN::urn:schemas-upnp-org:device:MediaRenderer:1"
                        )
                    } else {
                        listOf(st to usnFor(st))
                    }
                    for ((searchTarget, usn) in replies) {
                        val body = searchResponse(location, searchTarget, usn)
                        val bytes = body.toByteArray(StandardCharsets.US_ASCII)
                        socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "投屏广播中断，稍后重试", error)
                try { socket?.close() } catch (_: Exception) { }
                try { Thread.sleep(8000) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun announce(socket: MulticastSocket, nts: String) {
        val ip = lanIp(null) ?: return
        val location = "http://$ip:$PORT/cast/desc.xml"
        val notices = listOf(
            "upnp:rootdevice" to "$UDN::upnp:rootdevice",
            UDN to UDN,
            "urn:schemas-upnp-org:device:MediaRenderer:1" to "$UDN::urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1" to "$UDN::urn:schemas-upnp-org:service:AVTransport:1"
        )
        for ((nt, usn) in notices) {
            val body = "NOTIFY * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "LOCATION: $location\r\n" +
                "NT: $nt\r\n" +
                "NTS: $nts\r\n" +
                "SERVER: Android UPnP/1.0 KK/1.0\r\n" +
                "USN: $usn\r\n\r\n"
            val bytes = body.toByteArray(StandardCharsets.US_ASCII)
            try {
                socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(SSDP_ADDR), SSDP_PORT))
            } catch (error: Exception) {
                Log.w(TAG, "投屏广播没发出去", error)
            }
        }
    }

    private fun searchResponse(location: String, st: String, usn: String): String {
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date())
        return "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "DATE: $date\r\n" +
            "EXT:\r\n" +
            "LOCATION: $location\r\n" +
            "SERVER: Android UPnP/1.0 KK/1.0\r\n" +
            "ST: $st\r\n" +
            "USN: $usn\r\n\r\n"
    }

    private fun wanted(st: String): Boolean {
        val value = st.trim()
        return value.equals("ssdp:all", true) ||
            value.equals("upnp:rootdevice", true) ||
            value.contains("MediaRenderer", true) ||
            value.contains("AVTransport", true) ||
            value.contains("RenderingControl", true) ||
            value.contains("ConnectionManager", true) ||
            value.contains(UDN, true)
    }

    private fun usnFor(st: String): String {
        val value = st.trim()
        return when {
            value.equals("upnp:rootdevice", true) -> "$UDN::upnp:rootdevice"
            value.equals(UDN, true) -> UDN
            else -> "$UDN::$value"
        }
    }

    private fun handle(context: Context, socket: java.net.Socket) {
        socket.soTimeout = 4000
        val input = BufferedInputStream(socket.getInputStream())
        val header = readHeader(input)
        val text = header.toString(StandardCharsets.ISO_8859_1)
        val line = text.lineSequence().firstOrNull().orEmpty()
        val parts = line.split(" ")
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1)?.substringBefore("?").orEmpty()
        if (Regex("(?i)expect:\\s*100-continue").containsMatchIn(text)) {
            val early = socket.getOutputStream()
            early.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            early.flush()
        }
        val length = Regex("(?i)content-length:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val chunked = Regex("(?i)transfer-encoding:\\s*chunked").containsMatchIn(text)
        val body = when {
            length in 1..1_048_576 -> readBody(input, length)
            chunked -> readChunked(input)
            method == "POST" && input.available() > 0 -> readBody(input, input.available().coerceAtMost(1_048_576))
            else -> ""
        }
        Log.i(TAG, "$method $path (${body.length})")
        when {
            method == "GET" && path == "/cast/info" -> {
                val info = JSONObject().put("ok", true).put("name", NAME).put("package", context.packageName)
                write(socket, 200, "application/json; charset=utf-8", info.toString())
            }
            method == "POST" && path == "/cast/play" -> {
                val json = JSONObject(body.ifBlank { "{}" })
                val url = json.optString("url")
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    write(socket, 400, "application/json; charset=utf-8", """{"ok":false}""")
                    return
                }
                open(context, url, json.optString("kind", "image"), json.optString("title", ""))
                write(socket, 200, "application/json; charset=utf-8", """{"ok":true}""")
            }
            method == "GET" && path == "/cast/desc.xml" -> {
                val ip = lanIp(socket.inetAddress) ?: "127.0.0.1"
                write(socket, 200, "text/xml; charset=\"utf-8\"", description())
            }
            method == "GET" && path == "/cast/avtransport.xml" -> write(socket, 200, "text/xml; charset=\"utf-8\"", avScpd())
            method == "GET" && path == "/cast/connection.xml" -> write(socket, 200, "text/xml; charset=\"utf-8\"", connectionScpd())
            method == "GET" && path == "/cast/rendering.xml" -> write(socket, 200, "text/xml; charset=\"utf-8\"", renderScpd())
            method == "SUBSCRIBE" -> subscribe(socket, text, path)
            method == "POST" && path == "/cast/avtransport" -> avTransport(context, socket, text, body)
            method == "POST" && path == "/cast/connection" -> connection(socket, text, body)
            method == "POST" && path == "/cast/rendering" -> rendering(context, socket, text, body)
            else -> write(socket, 404, "application/json; charset=utf-8", """{"ok":false}""")
        }
    }

    private fun subscribe(socket: java.net.Socket, header: String, path: String) {
        val sid = header(header, "SID").ifBlank { "uuid:6f1c0a31-5c2e-4a77-9b10-000000009978" }
        writeHeaders(socket, 200, "text/xml; charset=\"utf-8\"", "", "SID: $sid\r\nTIMEOUT: Second-1800\r\n")
        val callback = Regex("(?i)<(https?://[^>]+)>").find(header(header, "CALLBACK"))?.groupValues?.get(1).orEmpty()
        if (callback.isBlank()) return
        val body = when {
            path.contains("connection") -> eventSet("<SinkProtocolInfo>${xml(SINK)}</SinkProtocolInfo><CurrentConnectionIDs>0</CurrentConnectionIDs>")
            path.contains("rendering") -> eventSet("<Volume>50</Volume><Mute>0</Mute>")
            else -> eventSet("<LastChange>${xml(lastChange())}</LastChange>")
        }
        clients.execute { sendEvent(callback, sid, body) }
    }

    private fun lastChange(): String {
        return """<Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/"><InstanceID val="0"><TransportState val="$transport"/><AVTransportURI val="${xml(mediaUrl)}"/></InstanceID></Event>"""
    }

    private fun eventSet(inner: String): String {
        return """<?xml version="1.0" encoding="utf-8"?><e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0"><e:property>$inner</e:property></e:propertyset>"""
    }

    private fun sendEvent(callback: String, sid: String, body: String) {
        try {
            val url = java.net.URL(callback)
            val port = if (url.port > 0) url.port else 80
            val target = url.path.ifBlank { "/" } + if (url.query != null) "?${url.query}" else ""
            java.net.Socket(url.host, port).use { eventSocket ->
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                val head = "NOTIFY $target HTTP/1.1\r\nHOST: ${url.host}:$port\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: $sid\r\nSEQ: 0\r\nCONTENT-LENGTH: ${bytes.size}\r\n\r\n"
                val output = BufferedOutputStream(eventSocket.getOutputStream())
                output.write(head.toByteArray(StandardCharsets.US_ASCII))
                output.write(bytes)
                output.flush()
            }
        } catch (error: Exception) {
            Log.w(TAG, "事件通知没送到", error)
        }
    }

    private fun avTransport(context: Context, socket: java.net.Socket, header: String, body: String) {
        val action = actionName(header, body)
        Log.i(TAG, "AVTransport $action")
        when (action) {
            "SetAVTransportURI" -> {
                val url = tag(body, "CurrentURI").trim().trim('"')
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    write(socket, 500, "text/xml; charset=\"utf-8\"", fault("716", "Resource not found"))
                    return
                }
                val meta = decodeXml(tag(body, "CurrentURIMetaData"))
                mediaUrl = url
                mediaTitle = tag(meta, "title").ifBlank { url.substringAfterLast('/').substringBefore('?') }
                mediaKind = kindOf(url, meta)
                transport = "STOPPED"
                Log.i(TAG, "收到 ${url.substringBefore('?').substringAfterLast('.').substringAfterLast('/')} $mediaKind")
                write(socket, 200, "text/xml; charset=\"utf-8\"", soapOk("AVTransport", action))
            }
            "Play" -> {
                val url = mediaUrl
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    write(socket, 500, "text/xml; charset=\"utf-8\"", fault("701", "Transition not available"))
                    return
                }
                transport = "PLAYING"
                open(context, url, mediaKind, mediaTitle)
                write(socket, 200, "text/xml; charset=\"utf-8\"", soapOk("AVTransport", action))
            }
            "Pause" -> {
                transport = "PAUSED_PLAYBACK"
                playback?.pause()
                write(socket, 200, "text/xml; charset=\"utf-8\"", soapOk("AVTransport", action))
            }
            "Stop" -> {
                transport = "STOPPED"
                playback?.stop()
                write(socket, 200, "text/xml; charset=\"utf-8\"", soapOk("AVTransport", action))
            }
            "Seek" -> {
                playback?.seekTo(parseTime(tag(body, "Target")))
                write(socket, 200, "text/xml; charset=\"utf-8\"", soapOk("AVTransport", action))
            }
            "GetTransportInfo" -> write(socket, 200, "text/xml; charset=\"utf-8\"", soapOk("AVTransport", action,
                "<CurrentTransportState>$transport</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>"))
            "GetPositionInfo" -> {
                val pos = formatTime(playback?.positionMs() ?: 0)
                val dur = formatTime(playback?.durationMs() ?: 0)
                write(socket, 200, "text/xml; charset=\"utf-8\"", soapOk("AVTransport", action,
                    "<Track>1</Track><TrackDuration>$dur</TrackDuration><TrackMetaData></TrackMetaData>" +
                        "<TrackURI>${xml(mediaUrl)}</TrackURI><RelTime>$pos</RelTime><AbsTime>$pos</AbsTime>" +
                        "<RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount>"))
            }
            "GetMediaInfo" -> write(socket, 200, "text/xml; charset=\"utf-8\"", soapOk("AVTransport", action,
                "<NrTracks>1</NrTracks><MediaDuration>${formatTime(playback?.durationMs() ?: 0)}</MediaDuration>" +
                    "<CurrentURI>${xml(mediaUrl)}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>" +
                    "<NextURI></NextURI><NextURIMetaData></NextURIMetaData><PlayMedium>NETWORK</PlayMedium>" +
                    "<RecordMedium>NOT_IMPLEMENTED</RecordMedium><WriteStatus>NOT_IMPLEMENTED</WriteStatus>"))
            else -> write(socket, 200, "text/xml; charset=\"utf-8\"", soapOk("AVTransport", action.ifBlank { "Action" }))
        }
    }

    private fun connection(socket: java.net.Socket, header: String, body: String) {
        val action = actionName(header, body).ifBlank { "GetProtocolInfo" }
        val inner = when (action) {
            "GetProtocolInfo" -> "<Source></Source><Sink>${xml(SINK)}</Sink>"
            "GetCurrentConnectionIDs" -> "<ConnectionIDs>0</ConnectionIDs>"
            "GetCurrentConnectionInfo" -> "<RcsID>0</RcsID><AVTransportID>0</AVTransportID><ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager><PeerConnectionID>-1</PeerConnectionID><Direction>Input</Direction><Status>OK</Status>"
            else -> ""
        }
        write(socket, 200, "text/xml; charset=\"utf-8\"", soapOk("ConnectionManager", action, inner))
    }

    private fun rendering(context: Context, socket: java.net.Socket, header: String, body: String) {
        val action = actionName(header, body).ifBlank { "GetVolume" }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        when (action) {
            "SetVolume" -> {
                val percent = tag(body, "DesiredVolume").toIntOrNull()?.coerceIn(0, 100) ?: 50
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, percent * max / 100, 0)
            }
        }
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        val inner = when (action) {
            "GetVolume" -> "<CurrentVolume>$volume</CurrentVolume>"
            "GetMute" -> "<CurrentMute>0</CurrentMute>"
            else -> ""
        }
        write(socket, 200, "text/xml; charset=\"utf-8\"", soapOk("RenderingControl", action, inner))
    }

    private fun open(context: Context, url: String, kind: String, title: String) {
        val intent = Intent(context, CastPlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("url", url)
            putExtra("kind", kind)
            putExtra("title", title)
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                context.startActivity(intent)
            } catch (error: Exception) {
                Log.e(TAG, "打不开播放页", error)
            }
        }
    }

    private fun kindOf(url: String, meta: String): String {
        val mime = Regex("(?i)http-get:\\*:([^:]+):").find(meta)?.groupValues?.get(1).orEmpty()
        val lower = (mime + " " + url.substringBefore('?')).lowercase()
        val image = listOf("image/", ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic")
        return if (image.any { lower.contains(it) }) "image" else "video"
    }

    private fun actionName(header: String, body: String): String {
        val fromHeader = Regex("(?i)SOAPAction:\\s*\"?[^\"]*#([A-Za-z0-9]+)").find(header)?.groupValues?.get(1)
        if (!fromHeader.isNullOrBlank()) return fromHeader
        return Regex("<(?:\\w+:)?([A-Za-z0-9]+)[^>]*xmlns:(?:\\w+)=\"urn:schemas-upnp-org:service:").find(body)?.groupValues?.get(1)
            ?: Regex("<u:([A-Za-z0-9]+)").find(body)?.groupValues?.get(1).orEmpty()
    }

    private fun tag(xml: String, name: String): String {
        val match = Regex("<(?:\\w+:)?$name[^>]*>([\\s\\S]*?)</(?:\\w+:)?$name>", RegexOption.IGNORE_CASE).find(xml)
        return decodeXml(match?.groupValues?.get(1)?.trim().orEmpty())
    }

    private fun decodeXml(value: String): String {
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
    }

    private fun xml(value: String): String {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    private fun parseTime(text: String): Int {
        val parts = text.trim().removePrefix("+").split(":").map { it.toIntOrNull() ?: return 0 }
        if (parts.size < 3) return 0
        return ((parts[parts.size - 3] * 3600) + (parts[parts.size - 2] * 60) + parts[parts.size - 1]) * 1000
    }

    private fun formatTime(ms: Int): String {
        val total = (ms.coerceAtLeast(0)) / 1000
        return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    private fun soapOk(service: String, action: String, inner: String = ""): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$service:1">$inner</u:${action}Response></s:Body>
</s:Envelope>"""
    }

    private fun fault(code: String, text: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>
<detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode><errorDescription>${xml(text)}</errorDescription></UPnPError></detail>
</s:Fault></s:Body></s:Envelope>"""
    }

    private fun description(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<device>
<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
<friendlyName>$NAME</friendlyName>
<manufacturer>KK</manufacturer>
<modelName>KK TV Launcher</modelName>
<UDN>$UDN</UDN>
<dlna:X_DLNADOC xmlns:dlna="urn:schemas-dlna-org:device-1-0">DMR-1.50</dlna:X_DLNADOC>
<serviceList>
<service>
<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
<SCPDURL>/cast/avtransport.xml</SCPDURL>
<controlURL>/cast/avtransport</controlURL>
<eventSubURL>/cast/avtransport</eventSubURL>
</service>
<service>
<serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
<serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
<SCPDURL>/cast/connection.xml</SCPDURL>
<controlURL>/cast/connection</controlURL>
<eventSubURL>/cast/connection</eventSubURL>
</service>
<service>
<serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
<SCPDURL>/cast/rendering.xml</SCPDURL>
<controlURL>/cast/rendering</controlURL>
<eventSubURL>/cast/rendering</eventSubURL>
</service>
</serviceList>
</device>
</root>"""
    }

    private fun avScpd(): String = scpd("""
        <action><name>SetAVTransportURI</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
        <argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
        </argumentList></action>
        <action><name>Play</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
        </argumentList></action>
        <action><name>Pause</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument></argumentList></action>
        <action><name>Stop</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument></argumentList></action>
        <action><name>Seek</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>
        <argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>
        </argumentList></action>
        <action><name>GetTransportInfo</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>
        <argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>
        <argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
        </argumentList></action>
        <action><name>GetPositionInfo</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>
        <argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>
        <argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument>
        <argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>CurrentTrackURI</relatedStateVariable></argument>
        <argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument>
        <argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument>
        <argument><name>RelCount</name><direction>out</direction><relatedStateVariable>RelativeCounterPosition</relatedStateVariable></argument>
        <argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable></argument>
        </argumentList></action>
        <action><name>GetMediaInfo</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>NrTracks</name><direction>out</direction><relatedStateVariable>NumberOfTracks</relatedStateVariable></argument>
        <argument><name>MediaDuration</name><direction>out</direction><relatedStateVariable>CurrentMediaDuration</relatedStateVariable></argument>
        <argument><name>CurrentURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
        <argument><name>CurrentURIMetaData</name><direction>out</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
        <argument><name>NextURI</name><direction>out</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument>
        <argument><name>NextURIMetaData</name><direction>out</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument>
        <argument><name>PlayMedium</name><direction>out</direction><relatedStateVariable>PlaybackStorageMedium</relatedStateVariable></argument>
        <argument><name>RecordMedium</name><direction>out</direction><relatedStateVariable>RecordStorageMedium</relatedStateVariable></argument>
        <argument><name>WriteStatus</name><direction>out</direction><relatedStateVariable>RecordMediumWriteStatus</relatedStateVariable></argument>
        </argumentList></action>
    """.trimIndent(), """
        <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>TransportPlaySpeed</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="yes"><name>LastChange</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>TransportState</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>TransportStatus</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>CurrentTrackURI</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>RelativeCounterPosition</name><dataType>i4</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>AbsoluteCounterPosition</name><dataType>i4</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>NumberOfTracks</name><dataType>ui4</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>CurrentMediaDuration</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>NextAVTransportURI</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>NextAVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>PlaybackStorageMedium</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>RecordStorageMedium</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>RecordMediumWriteStatus</name><dataType>string</dataType></stateVariable>
    """.trimIndent())

    private fun connectionScpd(): String = scpd("""
        <action><name>GetProtocolInfo</name><argumentList>
        <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
        <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
        </argumentList></action>
        <action><name>GetCurrentConnectionIDs</name><argumentList>
        <argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>
        </argumentList></action>
        <action><name>GetCurrentConnectionInfo</name><argumentList>
        <argument><name>ConnectionID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>
        <argument><name>RcsID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_RcsID</relatedStateVariable></argument>
        <argument><name>AVTransportID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_AVTransportID</relatedStateVariable></argument>
        <argument><name>ProtocolInfo</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ProtocolInfo</relatedStateVariable></argument>
        <argument><name>PeerConnectionManager</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionManager</relatedStateVariable></argument>
        <argument><name>PeerConnectionID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>
        <argument><name>Direction</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Direction</relatedStateVariable></argument>
        <argument><name>Status</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionStatus</relatedStateVariable></argument>
        </argumentList></action>
    """.trimIndent(), """
        <stateVariable sendEvents="no"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="yes"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="yes"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionID</name><dataType>i4</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>A_ARG_TYPE_RcsID</name><dataType>i4</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>A_ARG_TYPE_AVTransportID</name><dataType>i4</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>A_ARG_TYPE_ProtocolInfo</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionManager</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>A_ARG_TYPE_Direction</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionStatus</name><dataType>string</dataType></stateVariable>
    """.trimIndent())

    private fun renderScpd(): String = scpd("""
        <action><name>GetVolume</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
        </argumentList></action>
        <action><name>SetVolume</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
        </argumentList></action>
        <action><name>GetMute</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>CurrentMute</name><direction>out</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
        </argumentList></action>
    """.trimIndent(), """
        <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
        <stateVariable sendEvents="no"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType></stateVariable>
        <stateVariable sendEvents="yes"><name>Volume</name><dataType>ui2</dataType></stateVariable>
        <stateVariable sendEvents="yes"><name>Mute</name><dataType>boolean</dataType></stateVariable>
    """.trimIndent())

    private fun scpd(actions: String, variables: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
<actionList>$actions</actionList><serviceStateTable>$variables</serviceStateTable></scpd>"""
    }

    private fun header(text: String, name: String): String {
        return Regex("(?im)^$name:\\s*(.+)\\s*$").find(text)?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun lanInterface(): NetworkInterface? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().firstOrNull { nif ->
                nif.isUp && !nif.isLoopback && nif.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun lanIp(toward: InetAddress?): String? {
        val addresses = try {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .mapNotNull { it.hostAddress }
        } catch (_: Exception) {
            emptyList()
        }
        val remote = (toward as? Inet4Address)?.hostAddress?.substringBeforeLast('.')
        if (!remote.isNullOrBlank()) {
            addresses.firstOrNull { it.substringBeforeLast('.') == remote }?.let { return it }
        }
        return addresses.firstOrNull()
    }

    private fun readHeader(input: BufferedInputStream): ByteArray {
        val data = ArrayList<Byte>()
        while (data.size < 16384) {
            val value = input.read()
            if (value < 0) break
            data.add(value.toByte())
            val n = data.size
            if (n >= 4 && data[n - 4] == 13.toByte() && data[n - 3] == 10.toByte() && data[n - 2] == 13.toByte() && data[n - 1] == 10.toByte()) break
        }
        return data.toByteArray()
    }

    private fun readChunked(input: BufferedInputStream): String {
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < 1_048_576) {
            val size = readAsciiLine(input).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val chunk = ByteArray(size)
            var got = 0
            while (got < size) {
                val n = input.read(chunk, got, size - got)
                if (n < 0) break
                got += n
            }
            out.write(chunk, 0, got)
            readAsciiLine(input)
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun readAsciiLine(input: BufferedInputStream): String {
        val data = ArrayList<Byte>()
        while (data.size < 512) {
            val value = input.read()
            if (value < 0 || value == 10) break
            if (value != 13) data.add(value.toByte())
        }
        return String(data.toByteArray(), StandardCharsets.US_ASCII)
    }

    private fun readBody(input: BufferedInputStream, length: Int): String {
        val rest = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = input.read(rest, got, length - got)
            if (n < 0) break
            got += n
        }
        return String(rest, 0, got, StandardCharsets.UTF_8)
    }

    private fun write(socket: java.net.Socket, code: Int, type: String, body: String) {
        writeHeaders(socket, code, type, body, "")
    }

    private fun writeHeaders(socket: java.net.Socket, code: Int, type: String, body: String, extra: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = if (code == 200) "OK" else "ERR"
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n$extra" +
            "Connection: close\r\n\r\n"
        val output = BufferedOutputStream(socket.getOutputStream())
        output.write(head.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private const val SINK =
        "http-get:*:image/jpeg:*,http-get:*:image/png:*,http-get:*:image/webp:*,http-get:*:video/mp4:*," +
            "http-get:*:video/mpeg:*,http-get:*:video/quicktime:*,http-get:*:video/x-flv:*,http-get:*:audio/mpeg:*,http-get:*:*:*"
}
