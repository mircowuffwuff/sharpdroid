# turning one of the shared vocabulary's values into a thing on a device.
#
# **this is where the vocabulary meets a phone, and it lives in one place on purpose.** every script
# that launches something goes through these, so what `existing` means, what a path here means and
# what a device path means cannot drift between the script that stages and the script that runs.
#
#   existing        use what is already on the device, and say which it took
#   <a path here>   staged if the device has not got those bytes, reused if it has
#   /storage/...    used where it lies. nothing is staged and nothing is copied. a game may be
#                   named by its directory or by the eboot.bin inside it
#   none, omitted   nothing is named, and whatever is downstream answers
#
# **a byte count decides whether something is already there, never a name.** a rebuilt artefact keeps
# its name, so "it is already on the device" is a question about bytes; `--restage` is the escape
# hatch for the one case a byte count cannot see, which is two different artefacts of exactly the
# same length.

import subprocess
import sys
from pathlib import Path

from . import builds, device, paths, vocabulary
from .shell import Refusal, say


def game(attached, package, value, restage=False):
    """which game a launch names, or None when it names none.

    what comes back is a *name* for a staged game and an absolute path for one used where it lies.
    the app takes either, and which of the two it was is worth saying in the log.

    **a device path may name the game's directory or the `eboot.bin` inside it**, which is what the
    app accepts and what an emulation frontend is most likely to be holding. it is reduced to the
    directory here so that one game named the two ways is one value downstream.
    """
    source = vocabulary.read(value)
    vocabulary.accept(source, (vocabulary.EXISTING, vocabulary.PC_PATH, vocabulary.DEVICE_PATH,
                               vocabulary.OMITTED), "--game")
    if source.kind == vocabulary.OMITTED:
        return None

    files = device.external_files(package)
    if source.kind == vocabulary.EXISTING:
        staged = [name for name in attached.list(files + "/games")
                  if attached.exists("{}/games/{}/eboot.bin".format(files, name))]
        if not staged:
            raise Refusal(
                "--game existing, and no game is staged for {}.\n"
                "  --game <a dump directory here>    stages one\n"
                "  --game /storage/emulated/0/...    runs one where it lies".format(package))
        if len(staged) > 1:
            say("  {} games are staged; taking {}".format(len(staged), staged[0]))
        return staged[0]

    if source.kind == vocabulary.DEVICE_PATH:
        # **the directory or the eboot inside it, because the app takes either.** a library that
        # scanned a device for games holds the file it found rather than the folder around it, so
        # refusing the file here would refuse the shape most callers have -- and would refuse it in a
        # script while the app it launches accepts it.
        path = source.raw[:-len("/eboot.bin")] if source.raw.endswith("/eboot.bin") else source.raw
        if not attached.exists(path + "/eboot.bin"):
            raise Refusal("no eboot.bin at {} -- that is not a game directory on the device".format(
                path))
        return path

    _stage(["--game", source.raw], attached, package, restage)
    return Path(source.raw).name


def build(attached, package, value, restage=False):
    """which SharpEmu build a launch names, as an absolute path on the device, or None.

    a path rather than a name, because a build runs where it is. **and never an id**: an id names a
    family rather than an artefact, so it would answer with the newest of that id -- and a freshly
    staged build would lose to an older one sitting beside it.
    """
    source = vocabulary.read(value)
    vocabulary.accept(source, (vocabulary.EXISTING, vocabulary.BUILD, vocabulary.NONE,
                               vocabulary.PC_PATH, vocabulary.DEVICE_PATH, vocabulary.OMITTED),
                      "--sharpemu")
    if source.names_nothing:
        return None

    files = device.external_files(package)
    if source.kind == vocabulary.EXISTING:
        staged = attached.list(files + "/builds")
        staged = [name for name in staged if not name.startswith(".")]
        if not staged:
            raise Refusal(
                "--sharpemu existing, and no SharpEmu build is staged for {}.\n"
                "  --sharpemu build              publishes and packages the fork, which takes "
                "minutes\n"
                "  --sharpemu <directory|zip>    stages one you already have\n"
                "  --sharpemu none               lets the app run what its build manager settled "
                "on".format(package))
        if len(staged) > 1:
            say("  {} builds are staged; taking {}".format(len(staged), staged[-1]))
        return "{}/builds/{}".format(files, staged[-1])

    if source.kind == vocabulary.DEVICE_PATH:
        if not attached.exists(source.raw + "/meta.json"):
            raise Refusal("no meta.json at {} -- that is not a build on the device".format(
                source.raw))
        return source.raw

    here = Path(source.raw)
    # **a bare publish tree has no identity, so give it one rather than refusing.** the packaging
    # script owns the metadata format, so it does the work: duplicating it here is how two
    # definitions of one format start.
    if here.is_dir() and not (here / "meta.json").exists():
        say("  {} has no meta.json, so it is packaged as a development build first".format(here))
        _script(["package-build.py", "--from-archive", str(here.resolve()),
                 "--id", "dev", "--sharpemu-version", "dev"])
        here = builds.newest().directory

    _stage(["--sharpemu", str(here)], attached, package, restage)
    return "{}/builds/{}".format(files, builds.Build(here).folder_name)


def driver(attached, package, value, restage=False):
    """which GPU driver a launch names, as the name the app selects it by, or None.

    **the platform's own is the absence of a name rather than a name**, which is why there is no
    sentinel here to spell: naming none passes no extra at all, and the app loads what it has stored.
    `none` is the one that pins the platform's own over whatever that is.
    """
    source = vocabulary.read(value)
    vocabulary.accept(source, (vocabulary.EXISTING, vocabulary.NONE, vocabulary.PC_PATH,
                               vocabulary.DEVICE_PATH, vocabulary.OMITTED), "--driver")
    if source.kind == vocabulary.OMITTED:
        return None
    if source.kind == vocabulary.NONE:
        return "none"

    files = device.external_files(package)
    if source.kind == vocabulary.EXISTING:
        staged = attached.list(files + "/gpu-drivers")
        if not staged:
            raise Refusal(
                "--driver existing, and no driver package is staged for {}.\n"
                "  --driver <package.zip>    stages one\n"
                "  --driver none             pins the platform's own".format(package))
        if len(staged) > 1:
            say("  {} drivers are staged; taking {}".format(len(staged), staged[0]))
        return staged[0]

    if source.kind == vocabulary.DEVICE_PATH:
        return source.raw.rstrip("/").rsplit("/", 1)[-1]

    _stage(["--driver", source.raw], attached, package, restage)
    # the same naming rule the staging step used, from the package's own metadata rather than from
    # what the zip happens to be called.
    import json
    import zipfile
    with zipfile.ZipFile(source.raw) as zipped:
        meta = json.loads(zipped.read("meta.json").decode("utf-8"))
    return device.driver_name(meta, Path(source.raw).stem)


# --- how a build compares to the fork checkout ------------------------------------------------------


def build_staleness(attached, toolchain, build_path):
    """whether a staged build was cut from the commit the fork is checked out at.

    **a check that could not run is not a check that passed**, so this answers with one of three
    words. an empty answer that meant both "they agree" and "I could not look" has reported silence
    as success here before, and a clean arm hid it for a whole round of testing.

    **what this reads is the device**, and everything it then decides is `builds.compare_commit`,
    which the bundling step asks the same question of about a build on this machine.
    """
    if not build_path:
        return builds.UNKNOWN, "no build to compare"
    meta = attached.shell("cat {}/meta.json 2>/dev/null".format(device.quote(build_path)),
                          check=False)
    if not meta.strip():
        return builds.UNKNOWN, "the staged build has no readable meta.json"
    import json
    try:
        commit = (json.loads(meta) or {}).get("commit") or ""
    except ValueError:
        return builds.UNKNOWN, "the staged build's meta.json is unreadable"
    if not commit:
        return builds.UNKNOWN, "the staged build records no commit, so it was packaged from an archive"
    try:
        fork = toolchain.fork
    except Refusal as why:
        return builds.UNKNOWN, str(why)
    head = builds.checkout_commit(fork)
    if not head:
        return builds.UNKNOWN, "the fork checkout would not say what commit it is at"

    return builds.compare_commit(commit, head, fork)


# --- running the other scripts ------------------------------------------------------------------------


def _stage(arguments, attached, package, restage):
    passed = list(arguments) + ["--package", package]
    if attached.serial:
        passed += ["--serial", attached.serial]
    if restage:
        passed.append("--restage")
    _script(["stage.py"] + passed)


def _script(arguments):
    """run one of the scripts beside this package, as the command a person would type."""
    entry = paths.SCRIPTS / arguments[0]
    say("  $ py scripts\\{}".format(" ".join(arguments)))
    code = subprocess.run([sys.executable, str(entry)] + arguments[1:],
                          cwd=str(paths.ROOT)).returncode
    if code != 0:
        raise Refusal("{} exited {}".format(arguments[0], code))
