package com.mircowuffwuff.sharpdroid

import android.content.Context
import android.content.Intent

/**
 * the intent that starts a guest, built in one place because more than one screen starts one.
 *
 * **which extra carries the game is the whole difference between the two sources**, and getting it
 * wrong is not a crash but a launch that reads the wrong directory -- so it is written once. a screen
 * that assembled its own would be a second answer to that question, wrong the day either kind of
 * source grows a second way in.
 *
 * **the name and the cover a launch shows are not sent, and that is not an omission.** the loading
 * screen reads both from the dump, out of the `param.json` [MainActivity] opens anyway to key this
 * run's settings -- so sending them would be two extras describing a file that is parsed regardless,
 * and a launch from outside this app would be the one that had neither. one read, one answer, and a
 * game started by another app arrives looking exactly like a tap on the list.
 *
 * **every other extra is left absent on purpose, and a settings scene existing does not change
 * that.** absent is a real answer everywhere [MainActivity] reads one: no `sharpemu` means the most
 * recently staged build, no `driver` means the platform's own. what the user chose is merged by
 * MainActivity, which is the one place that can see a build's environment, the app's store, a game's
 * store and an overriding extra at once. a screen that put the stored values into the intent would
 * make itself and `am start` two different mergers of the same sources, and the second one would be
 * wrong the moment the first grew a row.
 */
object GameLaunch {

    private const val TAG = "sharpdroid"

    /**
     * where a guest run is started from, for the line that says one is starting.
     *
     * it names the gesture rather than the class, because what a reader of a log wants to know is
     * which of the two ways in was taken -- and a screen may be renamed without that changing.
     */
    enum class From(val label: String) {
        LIST("the game list"),
        GAME("the game's own scene"),
    }

    /**
     * the intent for [source], and the line saying so.
     *
     * **a staged game is `game`, a name under the app's own `games/`; a granted one is `safgame`
     * beside `saftree`**, naming a directory and the tree it is in. the host layer then mounts the
     * provider and hands the guest an invented path -- `docs/guest-files.md` -- instead of opening a
     * real one.
     *
     * **a granted game has a third way in, and it is this one that is the branch.** with all-files
     * access held, the same directory is an ordinary path, so it goes as `game` and the file layer
     * is never registered -- which is not a third mode of anything but the staged one, reached from
     * a folder the user picked instead of from the tooling. [AllFiles] decides, per launch, and
     * answers null for every reason including the ordinary one.
     */
    fun intent(context: Context, source: GameSource, name: String, from: From): Intent {
        val intent = Intent(context, MainActivity::class.java)
        val how = when (source) {
            is GameSource.Staged -> {
                intent.putExtra("game", source.folder)
                "staged"
            }
            is GameSource.Granted -> {
                val direct = AllFiles.pathTo(source.documentId)
                if (direct != null) {
                    intent.putExtra("game", direct.absolutePath)
                    "by path, with all-files access"
                } else {
                    intent.putExtra("safgame", source.folder)
                    // **the tree travels with it, and that is what replaces the placeholder.** a
                    // launch naming no tree takes whichever persisted grant comes first, which is
                    // exact with one granted library and a coin toss with two.
                    intent.putExtra("saftree", source.tree.toString())
                    "through " + GameLibrary.label(source.tree)
                }
            }
        }
        // the folder, because that is what the intent carries and what the host layer will name in
        // its own lines. the display name is beside it so a log and a screen can be read together,
        // and where the run was started from, since two screens now start one.
        AppLog.i(
            TAG,
            "[app] launching " + source.folder + " (" + name + ", " + how + ", from " +
                from.label + ")"
        )
        return intent
    }
}
