package com.calebms.openflix.desktop

import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeviceDiscovery {

    private var jmdns: JmDNS? = null

    suspend fun discoverPhone(onFound: (host: String, port: Int) -> Unit) = withContext(Dispatchers.IO) {
        try {
            val localhost = InetAddress.getLocalHost()
            jmdns = JmDNS.create(localhost).apply {
                addServiceListener("_openflix._tcp.local.", object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent?) {}
                    override fun serviceRemoved(event: ServiceEvent?) {}
                    override fun serviceResolved(event: ServiceEvent) {
                        val host = event.info.inet4Addresses.firstOrNull()?.hostAddress
                        val port = event.info.port
                        if (host != null) {
                            onFound(host, port)
                        }
                    }
                })
            }
        } catch (e: Exception) {
            println("Auto-discovery listener error: ${e.message}")
        }
    }

    fun stop() {
        jmdns?.close()
        jmdns = null
    }
}