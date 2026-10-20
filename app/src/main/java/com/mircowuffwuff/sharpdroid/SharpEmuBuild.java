package com.mircowuffwuff.sharpdroid;


import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * a SharpEmu build: a directory holding a payload, its {@code plugins/}, and a {@code meta.json}
 * that gives it an identity.
 *
 * <p><b>a build is a directory, not a file.</b> SharpEmu resolves {@code plugins/} relative to its
 * own executable -- {@code AppContext.BaseDirectory/plugins} for managed plugins and
 * {@code ffmpeg.RootPath} for the Bink decoder -- so a payload staged on its own is a payload with
 * no audio and no video. the host layer needs no part of this: {@code GuestProcFS::SetExe} is
 * {@code realpath} of the payload path it is handed, so {@code AppContext.BaseDirectory} follows
 * the build directory by itself.
 *
 * <p>everything here is the launcher's job on purpose. the host layer keeps taking a payload path
 * and stays a thing that runs an ELF; name resolution, {@code meta.json} and the contract check
 * live where the frontend's own build list will need them anyway, because two implementations of
 * one contract is one too many.
 */
// public only so that the kotlin above it can name the type in a signature: a package-private class
// cannot appear in a public declaration, and the build manager's list is made of these. every member
// stays package-private, which is the visibility that was ever doing any work here.
public final class SharpEmuBuild {

    private static final String TAG = "sharpdroid";

    /**
     * the launcher-to-payload interface generation this app speaks: which environment variables the
     * payload is expected to understand, which host window it must implement and which pad format
     * it reads.
     *
     * <p>a <b>range</b> rather than a single number, so bumping it does not silently invalidate
     * every build a user has already imported. outside the range the launch is refused and both
     * numbers are named -- because the failure this replaces is a pristine upstream build ignoring
     * {@code SHARPEMU_HOST_WINDOW=android}, constructing an {@code SdlHostWindow} and dying six
     * seconds later on "No available video device", which names the wrong component entirely.
     *
     * <p><b>every generation is in the range, and each adds to the one before it.</b> 1 implements
     * the window selector. 2 also understands the audio flag. 3 also registers a host input source,
     * which reads the contract 3 pad format. 4 reads the contract 4 pad format instead: one of four
     * ports per read, and the whole of the emulator's pad state.
     *
     * <p><b>an older generation runs without what it lacks, and nothing reports it but the launch
     * log.</b> a contract-1 payload does not know {@code SHARPEMU_HOST_AUDIO}, so it asks SDL for a
     * device, SDL names four backends android does not have, and the port degrades to
     * {@code backend=silent}: the game renders and makes no sound. a contract-2 payload does not know
     * {@code SHARPEMU_HOST_INPUT}, so it registers no input source and its pad exports report a
     * controller that is permanently connected and permanently neutral. the launch log names the
     * generation that ran, which is where either is found.
     *
     * <p><b>the host layer answers the contract 3 pad format as well as the contract 4 one, and this
     * range is what keeps it doing so</b>: that format and its rumble command are needed only while 3
     * is in here.
     *
     * <p>it is a courtesy and not a guarantee: a build that declares 1 and lies still dies inside
     * SDL. the real guarantee is knowing where a build came from, which is what the launch log is
     * for.
     */
    static final int CONTRACT_MIN = 1;
    static final int CONTRACT_MAX = 4;

    /**
     * the one build that ships inside the APK, by the folder it extracts to.
     *
     * <p><b>it is not a build with an id among others, and that is the whole point of this name.</b>
     * exactly one build ships per APK, so there is never a question of which of ours is the default
     * -- it is this one, structurally and forever, which is what removed a per-release recommendation
     * constant, a toggle and a badge. the list pins it at the top with no delete button, the way
     * Eden pins the system GPU driver.
     *
     * <p><b>a plain word rather than a derived name, and that is what makes it collision-proof.</b>
     * every other folder here is {@code <id>-<sharpemuVersion>-<packagedAt>}, so no import can
     * ever land on it and no sentinel is needed in the store: the setting holds this string like any
     * other folder name, and per-game selection later stores it the same way.
     *
     * <p><b>absent is a normal state.</b> the debug app does not bundle a build -- the dev loop keeps
     * its small APK and its staged builds -- so this directory simply does not exist there, and
     * {@link #mostRecent} is what a launch naming nothing falls back to.
     */
    static final String BUNDLED = "bundled";

    /** where the build will be run from -- wherever it already is, since nothing is copied. */
    final File dir;
    final String folder;
    final String id;
    final String name;
    final String sharpemuVersion;
    /**
     * when this build was packaged, {@code yyyyMMddHHmmss} as a number -- and the key everything is
     * ordered by, since higher is later is newer.
     *
     * <p><b>a time rather than a counter, and the field is named for what it is.</b> a counter has to
     * be bumped by whoever packages, which makes it wrong exactly when it matters: two packages of
     * one source both claiming to be the first. a packaging time assigns itself, cannot be forgotten,
     * and answers "which of these two is newer" without a repository to consult.
     *
     * <p><b>a long, because {@code 20260808013800} does not fit in 32 bits.</b> it is deliberately a
     * sortable integer rather than an ISO string or an epoch second: a person reading a directory
     * listing can date it at a glance, and a machine can compare it without parsing.
     */
    final long packagedAt;
    final int hostContract;
    final String payload;
    final String notes;
    /**
     * the fork commit this payload was built from, or empty.
     *
     * <p><b>this is the field that tells two builds of one version apart.</b> {@code sharpemuVersion}
     * is upstream's tag and our fork moves faster than upstream does, so two builds of one tag are
     * routine and are indistinguishable by every other field a person sees. the commit is what tells
     * them apart, which makes it the version of the build that ships -- whose {@code packagedAt}
     * is deliberately absent, because exactly one of it exists and there is nothing to order.
     */
    final String commit;
    /**
     * who produced this build, or empty.
     *
     * <p><b>not who wrote the emulator.</b> {@code sharpemuVersion} and {@link #commit} already say
     * what the code is; this answers the question somebody holding two zips of one version has,
     * which is whose zip each one is.
     *
     * <p><b>it is a claim and not a fact, unlike {@link #commit}.</b> a commit names something that
     * can be checked against a repository; this is a string in a zip anybody can edit. so it is
     * drawn and nothing else -- no import rule reads it, and nothing is trusted because of it.
     *
     * <p>empty on the build that ships inside the APK, which is the app's own and says so by being
     * bundled, and on any build packaged before the field existed.
     */
    final String author;
    /**
     * guest environment this build wants defaulted on. below everything but the app's own defaults,
     * which it replaces.
     */
    final Map<String, String> env;
    /**
     * true for the build that is still inside the APK: identified, and not yet a directory.
     *
     * <p><b>it exists so that one screen can be honest before one launch has happened.</b> the
     * bundled build is pinned and selected the first time the build manager is opened, and at that
     * moment nothing has extracted it -- so {@link #runnable} asking whether its payload is a file
     * would answer no, and the row would be drawn in red naming a contract that is perfectly fine.
     * the payload is in the APK, where {@code scripts/build-apk.py} checked it was before packaging.
     *
     * <p>everything read back <i>after</i> extraction is an ordinary directory again, so this is
     * false for every build a launch actually runs.
     */
    final boolean inApk;

    private SharpEmuBuild(File dir, String folder, JSONObject json, boolean inApk) {
        this.inApk = inApk;
        this.dir = dir;
        this.folder = folder;
        this.id = json.optString("id", folder);
        this.name = json.optString("name", this.id);
        this.sharpemuVersion = json.optString("sharpemuVersion", "0");
        this.packagedAt = json.optLong("packagedAt", 0);
        this.hostContract = json.optInt("hostContract", 0);
        this.payload = json.optString("payload", "SharpEmu");
        this.notes = json.optString("notes", "");
        this.commit = json.optString("commit", "");
        this.author = json.optString("author", "");
        this.env = new LinkedHashMap<>();
        JSONObject e = json.optJSONObject("env");
        if (e != null) {
            for (Iterator<String> it = e.keys(); it.hasNext(); ) {
                String key = it.next();
                this.env.put(key, e.optString(key));
            }
        }
    }

    File payloadFile() {
        return new File(dir, payload);
    }

    /** id, version, build number and contract -- what the launch log has to say. */
    String identity() {
        return name + " (" + id + " " + sharpemuVersion
                + (commit.isEmpty() ? " " + packagedAt : " " + shortCommit())
                + ", contract " + hostContract + ")";
    }

    /**
     * the commit, cut to what a person quotes in a bug report. empty when there is none.
     *
     * <p><b>a marker after the hash survives the cut.</b> a build published from a checkout with
     * uncommitted changes in it records {@code <hash>-dirty}, and that suffix is the whole of what
     * this build's provenance has to say -- truncating it to seven characters would print the
     * confident wrong answer the marker exists to prevent, in the launch log, on the build list and
     * on the About screen at once. a hash carries no hyphen, so the first one is where it ends.
     *
     * <p><b>what the marker carries after a dot does not survive it</b>, and that asymmetry is the
     * point: the packaging step fingerprints the uncommitted changes so that two of them can be told
     * apart by a comparison, and a person reading a screen is not that comparison. what they need is
     * that this build is not a commit, which is the word.
     */
    String shortCommit() {
        int mark = commit.indexOf('-');
        String hash = mark < 0 ? commit : commit.substring(0, mark);
        String marker = mark < 0 ? "" : commit.substring(mark);
        int detail = marker.indexOf('.');
        if (detail >= 0) {
            marker = marker.substring(0, detail);
        }
        return (hash.length() > 7 ? hash.substring(0, 7) : hash) + marker;
    }

    /**
     * the most recently staged build, or the most recently installed one if nothing is staged.
     * returns null and says why if there is nothing at all.
     *
     * <p><b>this is what "no {@code --es sharpemu}" means</b>, and it is the same rule the scripts
     * follow when you omit the flag: whatever the device already has. in a deploy loop "the one I
     * last put there" is what is meant, and the alternative -- the newest {@code packagedAt} -- is
     * exactly the footgun that answering by id carries, since a freshly staged build loses to a
     * later-stamped one that is still lying around.
     *
     * <p>Staged wins over installed wholesale rather than by date. {@code adb} writes the staged
     * directory, so it is the one that moves; an installed copy's timestamp says when it was copied
     * and not when its bytes were chosen.
     *
     * <p>it logs the directory it picked and how many it picked from. a run attributed to the wrong
     * artefact is this project's oldest failure, and the cure has always been saying which.
     */
    static SharpEmuBuild mostRecent(File staged, File internal) {
        SharpEmuBuild best = mostRecentIn(staged);
        String where = "staged";
        if (best == null) {
            best = mostRecentIn(internal);
            where = "installed";
        }
        if (best == null) {
            AppLog.e(TAG, "[app] no build in " + staged + " or " + internal
                    + ": stage one with scripts/stage.py, or name one with --es sharpemu <path>");
            return null;
        }
        AppLog.i(TAG, "[app] no build named, so the most recently " + where + " one: " + best.dir);
        if (!accept(best)) {
            return null;
        }
        return best;
    }

    private static SharpEmuBuild mostRecentIn(File root) {
        File[] entries = root.listFiles();
        if (entries == null) {
            return null;
        }
        SharpEmuBuild best = null;
        long bestAt = Long.MIN_VALUE;
        for (File entry : entries) {
            if (!entry.isDirectory() || isReserved(entry)) {
                continue;
            }
            SharpEmuBuild build = read(entry);
            if (build != null && entry.lastModified() > bestAt) {
                bestAt = entry.lastModified();
                best = build;
            }
        }
        return best;
    }

    /**
     * resolves the folder name the build manager stored, and runs it where it is.
     *
     * <p><b>a folder name is the concrete identity, and that is why the setting holds one.</b> it is
     * derived from {@code meta.json} -- {@code <id>-<sharpemuVersion>-<packagedAt>} -- so it names
     * one build and not a family. {@link #BUNDLED} is the one folder that is not derived from
     * anything, and it needs no sentinel beside it precisely because no derived name can collide
     * with a plain word.
     *
     * <p><b>nothing is copied.</b> a build runs where it is: a staged one from external storage,
     * where adb put it, and an imported or bundled one from the app's own directory, because that is
     * where they had to land. copying one onto internal storage would buy durability against
     * re-staging, which is a thing only a developer can do and exactly the thing they mean to do --
     * and {@code docs/build-format.md} records that the volume costs nothing measurable:
     * 874-902 ms from external FUSE against 879-907 ms from internal.
     *
     * <p>null when it is gone -- deleted from outside the app, or wiped with the external volume.
     */
    static SharpEmuBuild resolveFolder(String folder, File internal, File staged) {
        File inInternal = new File(internal, folder);
        if (inInternal.isDirectory()) {
            return resolvePath(inInternal);
        }
        File inStaged = new File(staged, folder);
        if (inStaged.isDirectory()) {
            return resolvePath(inStaged);
        }
        AppLog.e(TAG, "[app] the chosen build '" + folder + "' is in neither " + internal
                + " nor " + staged);
        return null;
    }

    /**
     * runs a build directory where it lies, checking its contract and its payload first.
     *
     * <p><b>this is what everything reaches now.</b> {@code --es sharpemu} hands it a path, the
     * settings store hands it a folder resolved to one, and the bundled build is a directory like any
     * other. nothing is copied on the way: a build runs where it is.
     */
    static SharpEmuBuild resolvePath(File dir) {
        if (!dir.isDirectory()) {
            AppLog.e(TAG, "[app] no build directory at " + dir
                    + " -- stage one with scripts/stage.py");
            return null;
        }
        SharpEmuBuild build = read(dir);
        if (build == null) {
            AppLog.e(TAG, "[app] " + dir + " has no readable meta.json, so it has no identity"
                    + " -- package it with scripts/package-build.py");
            return null;
        }
        if (!accept(build)) {
            return null;
        }
        return build;
    }

    /** the build that shipped inside this APK, or null -- which is the normal state in a debug app. */
    static SharpEmuBuild bundled(File internal) {
        File dir = new File(internal, BUNDLED);
        if (!dir.isDirectory()) {
            return null;
        }
        return read(dir);
    }

    /**
     * true for a directory this list must not draw, which today is only the bundled one.
     *
     * <p>it is pinned above the list rather than in it, so without this it would appear twice -- and
     * {@link #mostRecent} would be able to answer with it, which would make "no build was named" mean
     * something different the moment an APK started shipping one.
     */
    private static boolean isReserved(File dir) {
        return BUNDLED.equals(dir.getName()) || dir.getName().endsWith(".partial");
    }

    /**
     * every readable build on the device except the bundled one, one entry per identity.
     *
     * <p><b>a build in both places is one entry and the app's own copy is the one returned.</b> which
     * volume it came from is not a property of the build, and a list showing the same identity twice
     * would be asking the user to choose between two spellings of one thing.
     *
     * <p>ordering, badges and grouping are {@code BuildLibrary}'s: this answers what is there.
     */
    static List<SharpEmuBuild> list(File internal, File staged) {
        Map<String, SharpEmuBuild> byFolder = new LinkedHashMap<>();
        collect(staged, byFolder);
        // installed second, so it replaces the staged copy of the same folder rather than losing to
        // it. same identity either way -- the folder name is derived from meta.json.
        collect(internal, byFolder);
        return new ArrayList<>(byFolder.values());
    }

    private static void collect(File root, Map<String, SharpEmuBuild> into) {
        File[] entries = root.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            // **{@link #isReserved} and not just {@code .partial}**, or the bundled build is drawn
            // twice the moment it has been unpacked -- once pinned above the list and once in the
            // group its id and version put it in. it was invisible until something bundled a build,
            // because until then the directory it names does not exist.
            if (!entry.isDirectory() || isReserved(entry)) {
                continue;
            }
            SharpEmuBuild build = read(entry);
            if (build != null) {
                into.put(build.folder, build);
            }
        }
    }

    /** true once this build is on internal storage, which is where a selected build is run from. */
    boolean isInstalled(File internal) {
        return dir.getAbsolutePath().startsWith(internal.getAbsolutePath() + File.separator);
    }

    /**
     * whether this build could be launched at all: a contract this app speaks, and its payload
     * present. the build manager draws an entry that fails this rather than hiding it, because a
     * build somebody imported and cannot run should say why on the screen it was imported from.
     */
    boolean runnable() {
        return hostContract >= CONTRACT_MIN && hostContract <= CONTRACT_MAX
                && (inApk || payloadFile().isFile());
    }

    /** removes a build directory outright. nothing here is recoverable and nothing here is unique. */
    static boolean delete(File dir) {
        deleteTree(dir);
        boolean gone = !dir.exists();
        AppLog.i(TAG, "[app] " + (gone ? "deleted " : "could not delete ") + dir);
        return gone;
    }

    /** the two things that make a build runnable: a contract this app speaks, and its payload. */
    private static boolean accept(SharpEmuBuild build) {
        if (build.hostContract < CONTRACT_MIN || build.hostContract > CONTRACT_MAX) {
            AppLog.e(TAG, "[app] " + build.identity() + " declares host contract " + build.hostContract
                    + " and this app speaks " + CONTRACT_MIN + ".." + CONTRACT_MAX
                    + " -- refusing to launch it");
            return false;
        }
        if (!build.payloadFile().isFile()) {
            AppLog.e(TAG, "[app] " + build.identity() + " names payload '" + build.payload
                    + "' and it is not in " + build.dir);
            return false;
        }
        return true;
    }

    /**
     * the identity of a build that is still inside the APK, named by where it will extract to.
     *
     * <p>{@code BundledBuild} reads the asset's {@code meta.json} and calls this; nothing else has
     * any business constructing a build whose directory does not exist yet.
     */
    static SharpEmuBuild fromAsset(File target, JSONObject json) {
        return new SharpEmuBuild(target, target.getName(), json, true);
    }

    /** a build directory's identity, or null if it has no readable {@code meta.json}. */
    static SharpEmuBuild read(File dir) {
        File meta = new File(dir, "meta.json");
        if (!meta.isFile()) {
            return null;
        }
        try {
            byte[] raw = new byte[(int) meta.length()];
            try (InputStream in = new FileInputStream(meta)) {
                int read = 0;
                while (read < raw.length) {
                    int n = in.read(raw, read, raw.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
            }
            return new SharpEmuBuild(dir, dir.getName(),
                    new JSONObject(new String(raw, StandardCharsets.UTF_8)), false);
        } catch (Exception e) {
            AppLog.e(TAG, "[app] could not read " + meta, e);
            return null;
        }
    }

    /** orders two builds: their SharpEmu versions first, then the build number within one. */
    static int compare(SharpEmuBuild a, SharpEmuBuild b) {
        int v = compareVersions(a.sharpemuVersion, b.sharpemuVersion);
        if (v != 0) {
            return v;
        }
        return Long.compare(a.packagedAt, b.packagedAt);
    }

    /**
     * orders two {@code sharpemuVersion} strings.
     *
     * <p>the rule is {@link Versions}, which is a file of its own because it is not semver and reads
     * like a mistake to anybody who expects it to be.
     */
    static int compareVersions(String a, String b) {
        return Versions.compare(a, b);
    }

    private static void deleteTree(File file) {
        File[] entries = file.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                deleteTree(entry);
            }
        }
        file.delete();
    }
}
