package com.soundmesh.core

/** Everything needed to reach one particular host, read off its screen instead of off the network. */
data class PairingCode(
    val hostId: String,
    val address: String,
    val chunkPort: Int
)

/**
 * Wire format for the code a host shows and a sink scans.
 *
 * Text in the same shape as the other codecs here, so the same thing that makes them readable in a
 * log makes this one readable in a decoder's output. A QR code holds far more than this costs.
 *
 * One version field, and it is the protocol's rather than the format's own. The code and the
 * protocol leave in the same build, so a sink that cannot read the code cannot talk to the host
 * either, and two numbers would only ever move together. Carrying it matters because a scanned
 * code arrives with none of the mDNS record around it - the version check discovery does would
 * otherwise simply be missing on this path.
 */
object PairingCodeCodec {
    const val MAGIC = "soundmesh-pairing"

    fun encode(code: PairingCode): String =
        "$MAGIC ${PeerAdvertisement.PROTOCOL_VERSION} ${code.hostId} ${code.address} ${code.chunkPort}"

    fun decode(text: String): PairingCode {
        val fields = text.trim().split(" ")
        require(fields.size == 5 && fields[0] == MAGIC) { "not a pairing code: $text" }
        require(fields[1] == PeerAdvertisement.PROTOCOL_VERSION) { "unsupported pairing code version: ${fields[1]}" }
        // Checked here rather than where it lands: this value names a file on the scanning handset,
        // and the scanner is the one boundary in the whole system a stranger can present bytes at.
        require(HostId.isValid(fields[2])) { "unreadable host id: ${fields[2]}" }
        require(fields[3].isNotEmpty()) { "pairing code carries no address" }
        val port = fields[4].toIntOrNull()
        require(port != null && port in 1..65535) { "unreadable port: ${fields[4]}" }
        return PairingCode(hostId = fields[2], address = fields[3], chunkPort = port)
    }
}
