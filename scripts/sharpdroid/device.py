# everything that talks to a device, and the one place that knows how the app is named.
#
# **the debug identity is the default everywhere.** `com.mircowuffwuff.sharpdroid.debug` is a separate
# app to android -- its own internal storage, its own external files directory, its own save data --
# so a deploy loop cannot disturb a personal install. the release identity is the thing you ask for.
#
# **the activity is `<application id>/<java package>.MainActivity`.** the shorthand `<id>/.Main` that
# android accepts expands to `<id>.MainActivity`, which resolves to nothing under any renamed id --
# and resolves correctly under the unrenamed one, so it looks right until something is renamed. only
# the application id moves; the java package does not.
#
# **`adb shell` re-splits its command on the device.** passing an argument vector to `adb` settles
# the host side and nothing else: a game directory is named after its title, so it has spaces in it
# and often brackets, and the device's own shell would take that as several words and a glob. so
# every path handed to a device shell is quoted for it here, once, rather than at each call site.

import re
import shlex
import time
from pathlib import Path

from .shell import Refusal, capture, run, say

# the java package. the JNI entry points are named after it, the native link has an undefined-symbol
# reference keeping them alive, and every launch command spells it out. it does not move.
JAVA_PACKAGE = "com.mircowuffwuff.sharpdroid"

# the release application id, and what a development build becomes instead.
RELEASE_ID = "com.mircowuffwuff.sharpdroid"
DEBUG_ID = RELEASE_ID + ".debug"

# two entries in the launcher both called SharpEmu, with no way to tell which is which, is what the
# separate label avoids.
RELEASE_LABEL = "SharpDroid"
DEBUG_LABEL = "SharpDroid Debug"

# the guest activity: exported with no intent filter, because launching it by name is how a run gets
# its build, its driver and its diagnostic flags named per launch. the launcher activity is the list.
GUEST_ACTIVITY = JAVA_PACKAGE + ".MainActivity"
LIST_ACTIVITY = JAVA_PACKAGE + ".GameListActivity"

# where a build the app does not own is staged, and where the shell binary goes -- which belongs to
# no app, which is why it is the one thing with no application id.
SHELL_DIRECTORY = "/data/local/tmp/sharpdroid"

# how long the panel is given to come up before the lock screen is looked for. a device that is
# already awake is not waited for at all beyond this, which is why it is short.
WAKE_SETTLE = 0.3

# how long a dismissed lock screen is given to finish going away, and how often that is checked. the
# ceiling is only ever reached on a device that really is asking for a credential, and reaching it is
# the message rather than a delay anybody waits through twice.
WAKE_TIMEOUT = 3.0
WAKE_POLL = 0.25


def application_id(package=None, release=False):
    """which app a script is acting on, from the two arguments every script shares."""
    if package:
        return package
    return RELEASE_ID if release else DEBUG_ID


def application_label(package=None, release=False):
    identity = application_id(package, release)
    if identity == RELEASE_ID:
        return RELEASE_LABEL
    if identity == DEBUG_ID:
        return DEBUG_LABEL
    return "SharpDroid (" + identity.rsplit(".", 1)[-1] + ")"


def external_files(package):
    """the app's external files directory, as a path on the device."""
    return "/sdcard/Android/data/{}/files".format(package)


class Device:
    """one attached device, reached by an argument vector rather than a command line."""

    def __init__(self, toolchain, serial=None):
        self.adb = toolchain.adb
        self.serial = serial

    # --- the connection ---------------------------------------------------------------------

    def _argv(self, arguments):
        argv = [self.adb]
        if self.serial:
            argv += ["-s", self.serial]
        return argv + list(arguments)

    def require(self):
        """refuse before anything long happens, naming what is attached rather than that nothing is.

        an ambiguous answer is refused too: with two devices attached and no serial named, `adb`
        picks neither and its own message says so in a way that reads like a failure of ours.
        """
        listing = capture([self.adb, "devices"], check=False)
        attached = []
        for line in listing.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                attached.append(parts[0])
        if not attached:
            raise Refusal("no device is attached. plug one in, or check that it has accepted this "
                          "computer's adb key")
        if self.serial and self.serial not in attached:
            raise Refusal("device {} is not attached. attached: {}".format(
                self.serial, ", ".join(attached)))
        if not self.serial and len(attached) > 1:
            raise Refusal("{} devices are attached. name one with --serial: {}".format(
                len(attached), ", ".join(attached)))
        if not self.serial:
            self.serial = attached[0]
        return self

    # --- running things ---------------------------------------------------------------------

    def adb_run(self, *arguments, **kwargs):
        return run(self._argv(arguments), **kwargs)

    def adb_capture(self, *arguments, **kwargs):
        return capture(self._argv(arguments), **kwargs)

    def shell(self, command, check=True):
        """run one command in the device's own shell.

        `command` is a string because that is what the device shell receives however it is sent --
        `quote` below is how a path with a space in it survives the re-split.
        """
        return capture(self._argv(["shell", command]), check=check)

    def shell_ok(self, command):
        """true when the command exited zero, without treating a non-zero exit as a failure here."""
        output = self.shell("({}) >/dev/null 2>&1 && echo yes || echo no".format(command),
                            check=False)
        return output.strip().endswith("yes")

    # --- the file system --------------------------------------------------------------------

    def exists(self, remote):
        return self.shell_ok("test -e {}".format(quote(remote)))

    def is_directory(self, remote):
        return self.shell_ok("test -d {}".format(quote(remote)))

    def mkdir(self, remote):
        self.shell("mkdir -p {}".format(quote(remote)))

    def remove(self, remote):
        self.shell("rm -rf {}".format(quote(remote)), check=False)

    def move(self, source, target):
        self.shell("mv {} {}".format(quote(source), quote(target)))

    def list(self, remote):
        """the immediate children of a directory, by name. an empty list when it is not there."""
        if not self.is_directory(remote):
            return []
        output = self.shell("ls -1 {} 2>/dev/null".format(quote(remote)), check=False)
        return [line.strip() for line in output.splitlines() if line.strip()]

    def size_of(self, remote):
        """the byte count of a file, of a directory tree, or None when it could not be read.

        **None is a third answer and it matters.** an empty answer that meant both "they agree" and
        "I could not look" once reported silence as success for a whole round of testing, so a
        caller comparing sizes has to be able to tell "different" from "unknown".
        """
        output = self.shell(
            "if [ -d {0} ]; then du -sb {0} 2>/dev/null | cut -f1; "
            "elif [ -e {0} ]; then stat -c %s {0} 2>/dev/null; fi".format(quote(remote)),
            check=False).strip()
        match = re.search(r"^\d+$", output, re.MULTILINE)
        return int(match.group(0)) if match else None

    def push(self, local, remote):
        """put a file or a tree on the device, and assert that it arrived.

        `adb push` of a directory names the target after the *source*, so a tree is pushed into a
        scratch directory and renamed into place by whichever caller knows what it should be called
        -- the on-device name of a build comes from its own metadata, never from what the directory
        happens to be called here.
        """
        local = Path(local)
        if not local.exists():
            raise Refusal("nothing to push: {}".format(local))
        self.mkdir(_parent(remote))
        self.adb_run("push", str(local), remote)
        landed = self.size_of(remote)
        if landed is None:
            raise Refusal("push reported success and left nothing at {}".format(remote))
        return landed

    def push_quietly(self, local, remote):
        """push one file without a word unless it fails.

        `adb` reports transfer progress per file, and a staging step that moves thirty of them buries
        whatever it was run for under a page of it. the exit code is the only part that ever said
        whether a push worked, so that is what is kept.
        """
        local = Path(local)
        output = capture(self._argv(["push", str(local), str(remote)]), check=False)
        if "1 file pushed" not in output and "files pushed" not in output:
            raise Refusal("pushing {} failed:\n{}".format(local.name, output.strip()))
        return local.stat().st_size

    def pull(self, remote, local):
        Path(local).parent.mkdir(parents=True, exist_ok=True)
        self.adb_run("pull", remote, str(local))
        if not Path(local).exists():
            raise Refusal("pull reported success and left nothing at {}".format(local))
        return Path(local)

    # --- the app ------------------------------------------------------------------------------

    def installed(self, package):
        listing = self.shell("pm list packages {}".format(quote(package)), check=False)
        return any(line.strip() == "package:" + package for line in listing.splitlines())

    def install(self, apk, package):
        """install over whatever is there, keeping the app's data.

        a reinstall that has to uninstall first takes the save data with it, so a failure here is
        reported rather than worked around: the usual cause is a different signing key, and the fix
        is a decision the person running this has to make.
        """
        apk = Path(apk)
        output = capture(self._argv(["install", "-r", str(apk)]), check=False)
        say(output.strip())
        if "Success" not in output:
            if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in output:
                raise Refusal(
                    "the installed {} was signed with a different key. uninstall it first -- which "
                    "takes its save data with it -- or install the APK that matches it".format(
                        package))
            raise Refusal("install failed:\n{}".format(output.strip()))

    def force_stop(self, package):
        self.shell("am force-stop {}".format(quote(package)), check=False)

    # --- the screen ---------------------------------------------------------------------------

    def wake(self):
        """bring the panel up and take the lock screen off it, so that a launch is seen.

        **a launch onto a dozing device is a wasted run**, and it does not look like one: the app
        starts, the log fills, `screencap` returns the window drawn correctly, and the guest renders
        into a surface nobody is looking at. worse, the press that wakes such a panel is delivered to
        the panel rather than to what is under it -- so the first tap of a session goes missing, and
        that reads as a broken button rather than a sleeping screen.

        **`KEYCODE_WAKEUP` rather than `KEYCODE_POWER`, and the difference is not stylistic**: power
        toggles, so sending it to a device that is already awake puts it to sleep. wakeup is a no-op
        on a device that is awake, which is what makes this safe to do unconditionally.

        **the lock screen is only touched when there is one showing**, which is the other half of the
        same care: `wm dismiss-keyguard` is harmless, but reaching for the menu key instead -- the
        older way to dismiss one -- would deliver a menu key to whatever is in the foreground on a
        device that was never locked.

        a lock with a credential behind it is not dismissed by this and is not meant to be. it is
        said out loud and left alone.
        """
        self.shell("input keyevent KEYCODE_WAKEUP", check=False)
        time.sleep(WAKE_SETTLE)
        if not self._locked():
            return
        self.shell("wm dismiss-keyguard", check=False)
        if self._unlocked_within(WAKE_TIMEOUT):
            return
        say("  the device is locked and asks for a credential. unlock it, or this run renders "
            "into a screen nobody can see")

    def _unlocked_within(self, timeout):
        """whether the lock screen goes away, waited for rather than sampled once.

        **a lock screen dismissed is not a lock screen gone**: it animates, and reading the state
        immediately afterwards reports it still up. sampling once puts a message about a credential
        in front of somebody whose device has no credential on it, which is worse than saying
        nothing -- a warning that cries wolf is one nobody reads on the day it is right.
        """
        deadline = time.monotonic() + timeout
        while True:
            if not self._locked():
                return True
            if time.monotonic() >= deadline:
                return False
            time.sleep(WAKE_POLL)

    def _locked(self):
        """whether a lock screen is up, from whichever of the two names this platform reports.

        an answer neither name gives is read as "no lock screen", which is the reading that sends no
        input anywhere -- the cost of being wrong that way is a launch onto a locked device, and the
        cost of the other way is a stray key press into somebody's foreground app.
        """
        for name in ("isKeyguardShowing", "mDreamingLockscreen"):
            found = self.shell(
                "dumpsys window 2>/dev/null | grep -m1 -o '{}=[a-z]*'".format(name),
                check=False).strip()
            if found:
                return found.endswith("true")
        return False

    def start(self, package, activity, extras=None, wait=False, data=None):
        """launch an activity, naming it in full.

        the extras are typed the way `am` types them: a string is `--es`, a boolean `--ez`, an
        integer `--ei`. **an extra that was not asked for is not passed at all** -- a launch naming
        nothing is the argument vector it always was, and a setting nobody touched contributes
        nothing to it.

        **`data` is the other way a game can be named**, and it is the one an emulation frontend
        uses: a content uri on the intent itself rather than an extra. it is a separate parameter
        rather than another extra because `am` spells it differently and because an intent carries
        exactly one.

        **one quoted command string, and not an argument vector.** passing an argument list to `adb`
        settles this side of the wire and nothing else: the device's own shell receives one command
        line and splits it again. a game directory is named after its title and so has spaces in it,
        and a launch written the obvious way starts a game whose name is the first word of the real
        one -- which it does *quietly*, because what reaches the app is a name it simply does not
        have.

        **the screen is brought up first, here rather than in each script.** every launch goes
        through this one function and nothing else needs a display -- staging copies files, and the
        regression set drives the shell binary with no app and no window at all -- so putting it here
        is what makes "the screen is on when a run starts" true of every script without any of them
        having to remember it. `wake` is a no-op on a device that is already up.
        """
        self.wake()
        command = "am start"
        if wait:
            command += " -W"
        command += " -n {}".format(quote("{}/{}".format(package, activity)))
        # **the intent's own data, which is how an app outside this one names a game.**
        #
        # **no --grant-read-uri-permission, and it is not an omission.** a frontend attaches one
        # because it holds the grant; `adb shell` is uid 2000 and holds nothing, so asking to pass a
        # grant on is refused by the platform before the activity is even resolved -- the whole
        # launch fails with a SecurityException naming the uri. what a script can hand over is the
        # uri itself, which the app resolves against the folders it has been granted.
        if data:
            command += " -d {}".format(quote(data))
        for name, value in (extras or {}).items():
            if value is None:
                continue
            if isinstance(value, bool):
                command += " --ez {} {}".format(name, "true" if value else "false")
            elif isinstance(value, int):
                command += " --ei {} {}".format(name, value)
            else:
                command += " --es {} {}".format(name, quote(value))
        return self.shell(command, check=False)

    # --- the log --------------------------------------------------------------------------------

    def clear_log(self):
        self.adb_run("logcat", "-c", quiet=True, check=False)

    def read_log(self, *filters):
        """the log so far, dumped rather than followed."""
        arguments = ["logcat", "-d"]
        if filters:
            arguments += list(filters) + ["*:S"]
        return self.adb_capture(*arguments, check=False)


def driver_name(package_meta, fallback):
    """what a GPU driver package is called on the device.

    the package's own name, slugged, so that a name with a space in it cannot become a path with a
    space in it. one rule, shared by the step that stages a driver and the step that launches with
    one -- two of them would be two names for one directory.
    """
    text = (package_meta or {}).get("name") or fallback
    text = re.sub(r"[^a-z0-9._-]", "-", text.lower())
    return re.sub(r"-+", "-", text).strip("-")


def quote(remote):
    """quote one argument for the shell running on the device.

    posix quoting rather than anything windows: the string is being read by the device's own shell,
    and what this side of the wire thinks about backslashes has nothing to do with it.
    """
    return shlex.quote(str(remote))


def _parent(remote):
    remote = str(remote).rstrip("/")
    return remote.rsplit("/", 1)[0] if "/" in remote else "/"
