package com.mircowuffwuff.sharpdroid

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.Keep

/**
 * the java side of the guest file layer: a game directory the user granted us, answered file by file.
 *
 * a game reached this way is not a path. Android grants an app a *tree*, and everything behind it is
 * a document reached through a content provider -- so the host layer hands the guest an invented path
 * under [MOUNT] and calls back into here for every lookup the guest makes. `host/src/guest_files.cpp`
 * is the other half and `docs/guest-files.md` describes both, including what each call costs.
 *
 * **the three native entry points are the whole interface, and they are called from guest threads.**
 * they take a path relative to the mounted directory, they never throw, and they answer with the
 * errno the syscall underneath should return. everything else here is called from the app before a
 * guest exists.
 *
 * **a document id is built by concatenation, not by walking**, which is [TreeDocument]'s rule and the
 * only reason this is fast enough to exist.
 */
@Keep
object GuestFiles {

    private const val TAG = "sharpdroid"

    /**
     * where the guest sees its own game directory.
     *
     * invented, and nothing is there: the prefix test in the host layer is then unambiguous, and no
     * real path has to be made to appear to work. it is passed down as `--saf-mount`, so both halves
     * name it from here.
     */
    const val MOUNT = "/game"

    // errno, because the caller is a syscall. bionic's values, which are linux's, which are the
    // guest's too -- the numbers agree across both architectures.
    private const val ENOENT = -2
    private const val EIO = -5

    @Volatile
    private var resolver: ContentResolver? = null

    @Volatile
    private var tree: Uri? = null

    /** the document id of the mounted directory itself. every path below is this plus a suffix. */
    @Volatile
    private var rootId: String? = null

    /**
     * points the layer at one game directory inside a granted tree, and says whether it is there.
     *
     * **[documentId] is the directory itself rather than a name to append**, because the caller
     * already holds one: a launch resolves its game to a [GameSource] before anything is mounted, and
     * a granted source carries the id it was listed under. appending a name here would be deriving a
     * second time what the caller derived once, and it would only ever work for a game sitting
     * directly inside the granted folder.
     *
     * the check is not ceremony: a game whose directory does not resolve would otherwise become a
     * guest whose every file is missing, which reads as a corrupt dump rather than as a directory
     * that was not there.
     */
    @JvmStatic
    fun mount(context: Context, treeUri: Uri, documentId: String): Boolean {
        resolver = context.applicationContext.contentResolver
        tree = treeUri
        rootId = documentId

        val probe = statOne("eboot.bin")
        if (probe == null) {
            AppLog.e(TAG, "[app] no eboot.bin under $documentId -- is that a game directory in the grant?")
            unmount()
            return false
        }
        AppLog.i(TAG, "[app] the guest's game directory is $documentId, reached through a grant rather than a path")
        return true
    }

    @JvmStatic
    fun unmount() {
        resolver = null
        tree = null
        rootId = null
    }

    private fun documentUri(relative: String): Uri? {
        val treeUri = tree ?: return null
        val id = rootId ?: return null
        return TreeDocument.uri(treeUri, TreeDocument.childId(id, relative))
    }

    /**
     * a cursor, or null for every reason there is -- including the ordinary one.
     *
     * **a query for a document that is not there throws, and that is the common case rather than the
     * exceptional one.** a fifth of this guest's opens are of files it is only checking for, so a line
     * per failure would be a hundred lines of alarm per boot describing a working run. the first one
     * is reported, once, with what it was; after that they are counted and the count is what says
     * whether something is actually wrong -- a revoked grant fails *every* lookup, and a total equal
     * to the number of lookups is what that looks like.
     */
    private fun query(uri: Uri, columns: Array<String>): Cursor? =
        try {
            resolver?.query(uri, columns, null, null, null)
        } catch (e: Exception) {
            val n = misses.incrementAndGet()
            if (n == 1L) {
                // deliberately not called normal, though it usually is: a file the guest is only
                // checking for and a grant that has been revoked produce exactly this line, and the
                // count is the only thing that tells them apart.
                AppLog.i(TAG, "[app] a lookup came back empty: $uri. said once -- this is what a file the" +
                    " guest is only checking for looks like, and it is also what a revoked grant looks" +
                    " like; use --ez tracefiles true to count them", e)
            }
            null
        }

    /** every lookup that came back with nothing, of any cause. see [query]. */
    private val misses = java.util.concurrent.atomic.AtomicLong()

    /** what [misses] has reached, for a caller that wants to report it. */
    @JvmStatic
    fun missCount(): Long = misses.get()

    /**
     * the mounted game's `param.json`, for the launcher rather than for the guest.
     *
     * **not [openFd], and the difference is who owns the result.** that hands a detached descriptor
     * to a guest thread and answers in errno because a syscall is waiting on it; this is called from
     * the app before a guest exists, by the code that has to name a per-title directory before the
     * emulator starts. a stream is what that caller can read and close.
     *
     * the mount is the whole reason this belongs here: a granted game has no path, so the only way
     * to reach a file of it is the tree and document id this object is already holding. it answers
     * [GameSource.openParam]'s contract for the one kind of game that has no [GameSource] -- the
     * search order is that contract's, and is spelled here for the same reason `Granted` spells it.
     */
    @JvmStatic
    fun openMountedParam(): java.io.InputStream? =
        openMounted(Game.PARAM) ?: openMounted(Game.PARAM_BESIDE_EBOOT)

    private fun openMounted(relative: String): java.io.InputStream? {
        val uri = documentUri(relative) ?: return null
        return try {
            resolver?.openInputStream(uri)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * an open file descriptor, already ours, or a negative errno.
     *
     * `detachFd` rather than `use`: the descriptor is handed to the guest and outlives every object
     * here, so ownership has to leave with it. closing the wrapper afterwards would close the fd the
     * guest is about to read from.
     */
    @Keep
    @JvmStatic
    fun openFd(relative: String): Int {
        val uri = documentUri(relative) ?: return EIO
        return try {
            val descriptor = resolver?.openFileDescriptor(uri, "r") ?: return ENOENT
            descriptor.detachFd()
        } catch (e: Exception) {
            // absent is the common case and is not worth a stack trace per miss -- a fifth of the
            // guest's opens are of files it is only checking for.
            ENOENT
        }
    }

    /**
     * size, kind and modification time in one query, or null when there is no such document.
     *
     * three values in an array rather than bit-packed into one long. Dolphin packs size and
     * is-a-directory together so that one binder call does the work of two, and that reasoning does
     * not apply here: the cursor below already returns all three columns in a single round trip, so
     * packing would buy nothing and cost legibility.
     */
    @Keep
    @JvmStatic
    fun statOne(relative: String): LongArray? {
        val uri = documentUri(relative) ?: return null
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        query(uri, columns)?.use {
            if (!it.moveToFirst()) {
                return null
            }
            // a directory carries no size, and a provider is allowed to leave the column null for a
            // file it does not know the size of either.
            val size = if (it.isNull(0)) 0L else it.getLong(0)
            val directory = it.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR
            val modified = if (it.isNull(2)) 0L else it.getLong(2)
            return longArrayOf(size, if (directory) 1L else 0L, modified)
        }
        return null
    }

    /**
     * the children of one directory, each as its kind then its name -- `dsce_sys`, `feboot.bin`.
     *
     * one array to walk and one local reference to release per entry, rather than a names array and
     * a kinds array that have to be kept in step. the guest enumerates exactly one directory per
     * boot, so this is about correctness rather than speed.
     */
    @Keep
    @JvmStatic
    fun listChildren(relative: String): Array<String>? {
        val treeUri = tree ?: return null
        val id = rootId ?: return null
        val uri = TreeDocument.childrenUri(treeUri, TreeDocument.childId(id, relative))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = ArrayList<String>()
        query(uri, columns)?.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                val kind = if (it.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) "d" else "f"
                out.add(kind + name)
            }
        } ?: return null
        return out.toTypedArray()
    }
}
