package com.limelight.nvstream.mdns

import java.net.Inet6Address
import java.net.InetAddress

class MdnsComputer(
    val name: String,
    val localAddress: InetAddress?,
    val ipv6Address: Inet6Address?,
    val port: Int
) {
    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is MdnsComputer) {
            if (other.name != name || other.port != port) {
                return false
            }
            if (other.localAddress != localAddress) {
                return false
            }
            if (other.ipv6Address != ipv6Address) {
                return false
            }
            return true
        }
        return false
    }

    override fun toString(): String {
        return "[$name - $localAddress - $ipv6Address]"
    }
}
