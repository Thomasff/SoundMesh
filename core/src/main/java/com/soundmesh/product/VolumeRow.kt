package com.soundmesh.product

/**
 * One handset's answer to being told what volume to be.
 *
 * [percent] is worked out from [index] and [max] rather than echoed from what was asked, and the
 * three are shown together on purpose: setStreamVolume has been seen on these handsets to take a
 * value and move nothing, and under do-not-disturb it throws. A row that disagrees with the
 * slider is the whole reason this list exists.
 */
data class VolumeRow(
    /** Which handset this is, which is what a control for it alone has to be addressed to. */
    val peerId: String,
    val name: String,
    val percent: Int,
    val index: Int,
    val max: Int,
    val stream: String,
    /**
     * What this handset was last told to be, or null if nobody has told it anything.
     *
     * Kept apart from [percent] because they answer different questions, and until they were
     * kept apart this control did not work: the thumb was drawn from [percent], which is what
     * the handset reported, so letting go of it put the thumb back where the handset last was
     * and left it there until a report arrived. A handset that is slow to report, or not
     * reporting at all, was a handset with no working control - and the fault was invisible,
     * because a thumb sitting still looks like a thumb that has been obeyed.
     */
    val asked: Int? = null,
    /** What is wrong with this row, if anything. See [volumeComplaint].  */
    val complaint: VolumeComplaint = VolumeComplaint.NONE
)

/**
 * The two ways a handset can fail to be where it was told to be, which want different sentences.
 *
 * They look the same on screen - a row that disagrees with the thumb above it - and they are
 * nothing alike underneath. One is a stream that took a value and did not move, which is a fault
 * on that handset and has been seen on these ones. The other is a handset that is doing as it is
 * told and not saying so, which means the number beside it is simply old.
 */
enum class VolumeComplaint { NONE, NOT_SAID, REFUSED }
