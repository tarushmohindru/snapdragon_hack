package com.yourbusiness.formfusion.network

import kotlinx.coroutines.flow.MutableStateFlow
import java.net.NetworkInterface
import java.util.UUID
import kotlin.random.Random

enum class Role {
    NONE,
    HOST,
    FOLLOWER
}

/**
 * Shared session state for the QR-pairing flow. Screens read/write this directly
 * instead of passing session data through navigation args.
 */
object SessionManager {
    var sessionId: String = ""
    var token: String = ""
    var role: Role = Role.NONE
    var hostIp: String = ""
    var port: Int = 8080

    val connectedCount = MutableStateFlow(0)
    val sessionStarted = MutableStateFlow(false)

    fun reset() {
        sessionId = ""
        token = ""
        role = Role.NONE
        hostIp = ""
        port = 8080
        connectedCount.value = 0
        sessionStarted.value = false
    }

    fun generateSession() {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        sessionId = (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        token = UUID.randomUUID().toString()
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SessionManager", "failed to resolve local IP", e)
        }
        return ""
    }
}
