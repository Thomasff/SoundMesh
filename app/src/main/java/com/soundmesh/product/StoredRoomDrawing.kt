package com.soundmesh.product

import com.soundmesh.core.HostId
import com.soundmesh.core.SpatialMode
import com.soundmesh.core.SplitAxis
import java.io.File

/** Where the handsets were put, and how the rule was set, as it was when the screen went away. */
data class SavedDrawing(val placements: List<RoomIcon>, val room: RoomState)

/**
 * This room with everything a person set taken from the saved one.
 *
 * How the two screens stay the same room: the calibration screen writes the drawing when somebody
 * arranges it there, and the home screen reads it back on resuming. Without this a person would
 * arrange the phones where the measuring happens, walk back to where the music plays, and find the
 * old arrangement - with nothing on either screen saying which of the two was being played.
 *
 * Who is in the room is never taken from disk. The roster, which of them has gone quiet and every
 * measured length are live facts, and a handset is drawn where it was saved only if it is here.
 *
 * The colours are the one thing taken from disk and only while nothing live has any: the spatial
 * channel hands them out and only runs while something is playing, so between sessions the saved
 * ones are all there is, and during one the live ones are who actually holds which colour.
 */
internal fun RoomState.readBack(saved: SavedDrawing): RoomState {
    val placed = saved.placements.associateBy { it.peerId }
    return saved.room.copy(
        icons = icons.map { placed[it.peerId] ?: it },
        selfId = selfId,
        colours = colours.ifEmpty { saved.room.colours },
        silentIds = silentIds,
        measuredMetres = measuredMetres,
        listenerMetres = listenerMetres,
        otherHalfIds = SpatialRoom.reconciledOtherHalf(
            saved.room.otherHalfIds,
            icons.map { it.peerId }
        )
    )
}

/**
 * The drawing, kept past the process.
 *
 * Everything else the home screen knows survives being killed already, because everything else was
 * measured: the pair distances, the room field, how far the listener sat from each handset. The
 * drawing did not, and it is the only one of them that **cannot be measured again** - it is a
 * person's opinion about which phone is on which side of the sofa, and the phones cannot be asked.
 * Losing it costs somebody the walk round the room they did to produce it.
 *
 * Written whole and read whole, on [StoredRoomField]'s reasoning: half a drawing is a room with
 * some of its phones in last week's places and the rest in the default arc, and nothing on the
 * screen would say which were which.
 *
 * Key and value per line, and a key this build does not know is skipped rather than taken as the
 * end of the file - the same terms every other channel here reads an older or newer build on.
 */
class StoredRoomDrawing(private val directory: File) {
    /**
     * The drawing as it was last written, or null if nothing ever wrote one.
     *
     * Null rather than an empty drawing, because "nobody has arranged this room" and "somebody
     * arranged it with nothing in it" want different things from the screen: the first takes the
     * default arc, and the second would be a room that quietly refused to draw anybody.
     */
    fun read(): SavedDrawing? {
        val file = file()
        if (!file.isFile) return null
        val lines = runCatching { file.readLines() }.getOrElse { return null }
        val placements = ArrayList<RoomIcon>()
        var room = RoomState()
        for (line in lines) {
            val fields = line.trim().split(" ")
            val said = fields.drop(1)
            when (fields.firstOrNull()) {
                AT -> iconFrom(said)?.let(placements::add)
                SIDES -> said.firstOrNull()?.takeIf { HostId.isValid(it) }
                    ?.let { room = room.copy(otherHalfIds = room.otherHalfIds + it) }
                CALLED -> colourFrom(said)?.let { room = room.copy(colours = room.colours + it) }
                else -> room = withSaid(room, fields.firstOrNull(), said.firstOrNull())
            }
        }
        return SavedDrawing(placements, room)
    }

    /**
     * The room as it stands, replacing whatever was there.
     *
     * [placements] is every handset this phone has drawn, not only the ones in the room just now:
     * a handset that is switched off is the one whose place is most worth remembering, and the
     * screen keeps them for exactly that reason.
     *
     * Nothing about who was present is written. Who is in the room, which of them went quiet and
     * every measured distance are re-read from something live within a fifth of a second of this
     * screen opening, and writing them down would mean a room that draws and names phones that
     * are not there.
     *
     * The colours are the exception. A colour is half of what a handset is called, and the thing
     * that hands them out is the spatial channel, which is only up while a session is playing -
     * so between sessions nothing would assign one, and two screens drawing the same room would
     * disagree about what to call every phone in it. What is remembered is what was last handed
     * out, and the moment a session starts the channel replaces the lot.
     */
    fun write(placements: List<RoomIcon>, room: RoomState) {
        val lines = ArrayList<String>()
        lines += "$MODE ${room.mode.name}"
        lines += "$PAN ${room.pan}"
        lines += "$SEPARATION ${room.separation}"
        lines += "$AXIS ${room.splitAxis.name}"
        lines += "$CROSSOVER ${room.crossoverHz}"
        lines += "$ENVELOPMENT ${room.envelopment}"
        lines += "$PERIOD ${room.periodSeconds}"
        lines += "$DELAY ${room.delayCompensation}"
        lines += "$SCALE ${room.metresPerUnit}"
        lines += "$FITTED ${room.fitted}"
        for (peerId in room.otherHalfIds) if (HostId.isValid(peerId)) lines += "$SIDES $peerId"
        for ((peerId, place) in room.colours) {
            if (HostId.isValid(peerId)) lines += "$CALLED $peerId $place"
        }
        for (icon in placements) {
            if (!HostId.isValid(icon.peerId) || !onTheDrawing(icon.x, icon.y)) continue
            lines += "$AT ${icon.peerId} ${icon.x} ${icon.y}"
        }
        runCatching { file().writeText(lines.joinToString("\n")) }
    }

    private fun colourFrom(said: List<String>): Pair<String, Int>? {
        if (said.size != 2) return null
        val peerId = said[0].takeIf { HostId.isValid(it) } ?: return null
        val place = said[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return peerId to place
    }

    private fun iconFrom(said: List<String>): RoomIcon? {
        if (said.size != 3) return null
        val peerId = said[0].takeIf { HostId.isValid(it) } ?: return null
        val x = said[1].toFloatOrNull() ?: return null
        val y = said[2].toFloatOrNull() ?: return null
        return if (onTheDrawing(x, y)) RoomIcon(peerId, x, y) else null
    }

    /**
     * A place is only a place if it is on the drawing.
     *
     * Not a tidiness check: the drawing is drawn in fractions of its own side, so anything outside
     * this is an icon nobody could have dragged there - and it would be drawn off the edge of the
     * room, where the only way to get it back is to know it is there.
     */
    private fun onTheDrawing(x: Float, y: Float): Boolean =
        x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f

    private fun withSaid(room: RoomState, key: String?, said: String?): RoomState {
        if (said == null) return room
        return when (key) {
            MODE -> room.copy(mode = enumOrNull<SpatialMode>(said) ?: room.mode)
            PAN -> room.copy(pan = said.toFloatOrNull()?.takeIf { it.isFinite() } ?: room.pan)
            SEPARATION ->
                room.copy(separation = said.toFloatOrNull()?.takeIf { it.isFinite() } ?: room.separation)
            AXIS -> room.copy(splitAxis = enumOrNull<SplitAxis>(said) ?: room.splitAxis)
            CROSSOVER ->
                room.copy(crossoverHz = said.toFloatOrNull()?.takeIf { it.isFinite() } ?: room.crossoverHz)
            ENVELOPMENT ->
                room.copy(envelopment = said.toFloatOrNull()?.takeIf { it.isFinite() } ?: room.envelopment)
            PERIOD -> room.copy(periodSeconds = said.toIntOrNull() ?: room.periodSeconds)
            DELAY -> room.copy(delayCompensation = said.toBooleanStrictOrNull() ?: room.delayCompensation)
            SCALE ->
                room.copy(metresPerUnit = said.toDoubleOrNull()?.takeIf { it.isFinite() } ?: room.metresPerUnit)
            FITTED -> room.copy(fitted = said.toBooleanStrictOrNull() ?: room.fitted)
            // Anything else is a later build's business, and skipping it costs this one nothing.
            else -> room
        }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(said: String): T? =
        enumValues<T>().firstOrNull { it.name == said }

    private fun file() = File(directory, FILE_NAME)

    companion object {
        const val FILE_NAME = "room-drawing"

        private const val AT = "at"
        private const val SIDES = "sides"
        private const val CALLED = "called"
        private const val MODE = "mode"
        private const val PAN = "pan"
        private const val SEPARATION = "separation"
        private const val AXIS = "axis"
        private const val CROSSOVER = "crossover-hz"
        private const val ENVELOPMENT = "envelopment"
        private const val PERIOD = "period-s"
        private const val DELAY = "delay-compensation"
        private const val SCALE = "metres-per-unit"
        private const val FITTED = "fitted"
    }
}
