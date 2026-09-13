package com.soundmesh.probe.sync

/**
 * Which handsets in the room have stopped being heard from.
 *
 * Everything else this host knows about who is still here waits for a write to fail, and a write
 * to a handset that walked out of the network does not fail: the bytes go into the kernel's send
 * buffer and TCP retransmits for minutes before it gives up. Measured on 2026-09-11 with a sink's
 * WiFi switched off mid-session - half a megabyte queued on the audio socket, still ESTABLISHED,
 * the icon on the host still solid. Closing a connection properly does send a FIN, which is why
 * pressing stop on a sink has always been noticed; but a person who cares has usually walked out
 * of range or run out of battery, and neither of those sends anything.
 *
 * The clock channel is the reading that cannot be lied to. It is UDP, so it has no connection to
 * hold open, and a sink asks the host for the time every two seconds for the whole of a session.
 * A sink that is gone stops asking. Nothing was added to any protocol for this: the requests were
 * already arriving and nobody was writing down when.
 *
 * The one thing a clock request does not carry is a name - it is answered off its source address,
 * which is all the time service ever needed. The name comes from the control channel, which knows
 * both, and that is what [quiet] is handed. Without it the answer would be "something at
 * 192.168.1.20 has gone quiet", and the whole point of this is to say which phone.
 */
class PeerSilence(private val quietAfterMillis: Long = QUIET_AFTER_MILLIS) {
    /**
     * When each handset in the room was first seen here, which is what a handset that has never
     * been heard from is judged against.
     *
     * A sink joins the room the moment it says its name, and its first clock request arrives some
     * time after that. Judged against nothing, a handset with no clock request yet would be called
     * dropped from the instant it appeared - which is exactly when somebody is looking at it.
     */
    private val firstSeen = HashMap<String, Long>()

    /**
     * @param room what each handset in the room is called against the address it connected from.
     *   The host is not in here: it does not ask itself for the time.
     * @param heard when a clock request last arrived from each address.
     */
    @Synchronized
    fun quiet(room: Map<String, String>, heard: Map<String, Long>, now: Long): Set<String> {
        // A handset that left is forgotten, so that the same handset coming back is given its
        // grace again rather than being called dropped on arrival: it returns on a fresh
        // connection, and the last thing heard from its address is from before it went.
        firstSeen.keys.retainAll(room.keys)
        return room.filterTo(LinkedHashMap()) { (peerId, address) ->
            val since = firstSeen.getOrPut(peerId) { now }
            now - maxOf(heard[address] ?: 0L, since) >= quietAfterMillis
        }.keys
    }

    companion object {
        /**
         * How long a handset can say nothing before it is called dropped.
         *
         * Three missed clock requests at the session's two-second cadence. Shorter would report a
         * handset whose packet was lost, which on a busy WiFi is an ordinary evening; much longer
         * and a person who has already noticed the music stop is still looking at a solid icon.
         */
        const val QUIET_AFTER_MILLIS = 6_000L
    }
}
