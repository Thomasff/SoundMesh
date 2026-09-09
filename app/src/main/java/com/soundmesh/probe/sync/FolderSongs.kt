package com.soundmesh.probe.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * The songs in a folder the listener granted, in the order they will play.
 *
 * Read fresh every time rather than remembered, and that is the point: what is stored when a
 * folder is chosen is the folder, so a song added to it this afternoon is in tonight's playlist
 * without anybody re-choosing anything. The cost is that the count on the screen and the list that
 * actually plays are two readings, and a folder that changed between them differs - which is the
 * truth about the folder rather than a defect.
 *
 * One level deep. A sub-folder is not audio and [SongOrder] drops it, so an album inside an album
 * is not found. That is a limit worth having on purpose rather than a recursion worth writing:
 * "play this folder" is a sentence about one folder.
 */
object FolderSongs {
    /** Empty if the grant is gone, the folder was deleted, or nothing in it is a song. */
    fun of(context: Context, folder: Uri): List<Song> = SongOrder.of(entries(context, folder))

    private fun entries(context: Context, folder: Uri): List<Song> {
        val children = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                folder,
                DocumentsContract.getTreeDocumentId(folder)
            )
        }.getOrNull() ?: return emptyList()
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val found = ArrayList<Song>()
        runCatching {
            context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    // A provider is allowed to answer null here; the id is not a name a person
                    // would recognise, but it is the one thing that is always there, and a song
                    // ordered under it is better than a song left out of the folder.
                    val name = cursor.getString(1) ?: id
                    found += Song(
                        uri = DocumentsContract.buildDocumentUriUsingTree(folder, id).toString(),
                        name = name,
                        mimeType = cursor.getString(2)
                    )
                }
            }
        }
        return found
    }
}
