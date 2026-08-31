# puts things on a device: a SharpEmu build, a game, the guest libraries, a GPU driver, the shell
# binary.
#
#   py scripts/stage.py --sharpemu build\builds\android-0.0.3-hotfix-2-20260808015108
#   py scripts/stage.py --sharpemu build\builds\android-0.0.3-hotfix-2-20260808015108.zip
#   py scripts/stage.py --game "Y:\games\Dreaming Sarah"
#   py scripts/stage.py --guest-libs
#   py scripts/stage.py --driver Turnip.zip --driver-name turnip
#   py scripts/stage.py --shell
#
# **each of these takes a path on this machine and only that.** staging is the act of copying
# something here onto the device, so `existing` names nothing to copy and a device path names
# something already there; both are refused in those words. the script that *launches* takes all
# the forms.
#
# **existence is not sameness.** what decides whether something is already on the device is the byte
# count of its payload, never its name -- a rebuilt artefact keeps its directory name, so "the folder
# is already there" silently runs yesterday's bytes. one stat is the whole fix, and it has caught a
# build attributed to the wrong source more than once.
#
# everything lands on the app's **external** storage, which is the one volume adb can write and the
# app can read. what runs from internal storage is the GPU driver, because the linker refuses a
# library on a volume another app could have written -- the app copies that one across at launch.

import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sharpdroid import builds, device, paths, vocabulary
from sharpdroid import toolchain as tc
from sharpdroid.shell import Refusal, fresh, main, read_text, say, size, step, tree_size, wipe
from sharpdroid.vocabulary import Parser


def entry():
    parser = Parser(description="put things on a device")
    parser.add_argument("--sharpemu", metavar="PATH", default=None,
                        help="a build directory or a zip of one, here on this machine.")
    parser.add_argument("--game", metavar="PATH", default=None,
                        help="a game dump directory here on this machine.")
    parser.add_argument("--driver", metavar="PATH", default=None,
                        help="a GPU driver package zip here on this machine.")
    parser.add_argument("--driver-name", metavar="NAME", default=None,
                        help="what to call the staged driver on the device. defaults to the "
                             "package's own name. **not `--name`**, which is the launcher label "
                             "where an APK build takes one -- no argument here means two things.")
    parser.add_argument("--guest-libs", action="store_true",
                        help="the x86-64 shared objects the guest's own linker searches. the APK "
                             "carries these, so this is the development override for a rebuilt "
                             "thunk stub rather than how they get onto a device.")
    parser.add_argument("--shell", action="store_true",
                        help="the host layer's shell binary, its guests and its libraries, into "
                             "the directory that belongs to no app.")
    parser.add_argument("--restage", action="store_true",
                        help="push even when the device already has these bytes.")
    vocabulary.add_package(parser)
    vocabulary.add_common(parser)
    arguments = parser.parse_args()

    if not any([arguments.sharpemu, arguments.game, arguments.driver,
                arguments.guest_libs, arguments.shell]):
        raise Refusal("nothing to stage. name one of --sharpemu, --game, --driver, --guest-libs, "
                      "--shell")

    toolchain = tc.resolve().require("adb")
    attached = device.Device(toolchain, arguments.serial).require()
    package = device.application_id(arguments.package, arguments.release)
    files = device.external_files(package)
    say("device {}, app {}".format(attached.serial, package))

    if arguments.sharpemu:
        stage_build(attached, files, _here(arguments.sharpemu, "--sharpemu"), arguments.restage)
    if arguments.game:
        stage_game(attached, files, _here(arguments.game, "--game"), arguments.restage)
    if arguments.guest_libs:
        stage_guest_libs(attached, files)
    if arguments.driver:
        stage_driver(attached, files, _here(arguments.driver, "--driver"), arguments.driver_name)
    if arguments.shell:
        stage_shell(attached, toolchain)


def _here(value, argument):
    """a path on this machine, and a refusal in those words for anything else.

    the vocabulary is shared, so `existing` and a device path are both meaningful words elsewhere --
    they simply name nothing this script could copy.
    """
    source = vocabulary.read(value)
    vocabulary.accept(source, (vocabulary.PC_PATH,), argument)
    path = Path(source.raw)
    if not path.exists():
        raise Refusal("{} names {}, which is not here".format(argument, path))
    return path.resolve()


# --- a build ----------------------------------------------------------------------------------------


def stage_build(attached, files, source, restage):
    """a build directory or a zip of one, under the name its own metadata derives.

    **the on-device name comes from the metadata and never from the file name.** two builds of the
    same source then coexist and nothing has to guess which is which -- and `adb push` of a directory
    naming its target after the *source* is exactly how a renamed directory would land under a name
    the assertion afterwards does not look for.
    """
    step("the SharpEmu build")
    unpacked = None
    if source.is_file() and source.suffix.lower() == ".zip":
        # the zip is the distribution format and the directory is what runs, so it is unpacked here
        # rather than on the device -- the device's own shell has no unpacker worth relying on.
        unpacked = fresh(paths.BUILD / "stage-unpack")
        with zipfile.ZipFile(str(source)) as zipped:
            zipped.extractall(str(unpacked))
        source = unpacked
        say("  unpacked the zip")

    build = builds.open_build(source).check()
    target = "{}/builds/{}".format(files, build.folder_name)

    say("  {} -- {}".format(build.field("name"), build.identity))
    say("  payload {}".format(size(build.payload_size())))
    say("  -> {}".format(target))

    landed = attached.size_of(target + "/" + build.payload.name)
    verdict = builds.compare_payload(build, landed)
    if verdict == builds.MATCH and not restage:
        say("  already there, byte for byte. pass --restage to push it over")
        return
    if verdict == builds.STALE:
        say("  the device has {} of payload and this is {} -- restaging".format(
            size(landed), size(build.payload_size())))

    # **pushed into a scratch directory and renamed into place.** the rename is what makes a restage
    # safe: the previous contents are dropped only once the new ones are completely there, so an
    # interrupted push cannot leave half a build under a name that says it is whole.
    scratch = "{}/builds/.staging".format(files)
    attached.remove(scratch)
    attached.mkdir(scratch)
    attached.push(source, scratch + "/")
    attached.remove(target)
    attached.move("{}/{}".format(scratch, source.name), target)
    attached.remove(scratch)
    attached.shell("sync")

    # **assert the payload landed, and assert its size.** the byte count is this project's control
    # for artefact identity, so it is checked here rather than left for a launch to reveal.
    on_device = attached.size_of(target + "/" + build.payload.name)
    if on_device is None:
        raise Refusal("nothing landed at {}/{}".format(target, build.payload.name))
    if on_device != build.payload_size():
        raise Refusal("the payload on the device is {} and this build's is {} -- the push did not "
                      "complete".format(size(on_device), size(build.payload_size())))
    if not attached.is_directory(target + "/plugins"):
        raise Refusal("plugins/ did not land at {}".format(target))

    if unpacked:
        wipe(unpacked)
    say("  staged, {} verified on the device".format(size(on_device)))


# --- a game ------------------------------------------------------------------------------------------


def stage_game(attached, files, source, restage):
    """a dump directory, under its own last component.

    that is the whole mapping, and it is what a launch names. a script that only launches takes the
    name instead, because by then it is the identity of something already on the device; one that
    stages takes a path, because a name would mean guessing which directory here was meant.
    """
    step("the game")
    boot = source / "eboot.bin"
    if not boot.exists():
        raise Refusal("{} has no eboot.bin -- that is not a game directory".format(source))

    target = "{}/games/{}".format(files, source.name)
    attached.mkdir("{}/games".format(files))

    local = boot.stat().st_size
    landed = attached.size_of(target + "/eboot.bin")
    if landed == local and not restage:
        say("  {} is already on the device, byte for byte".format(source.name))
        say("  {}, eboot.bin {}".format(target, size(landed)))
        say("  pass --restage to push this one over it")
        return
    if landed is not None and landed != local:
        say("  the device has a {} eboot.bin and this one is {} -- restaging".format(
            size(landed), size(local)))

    total = tree_size(source)
    say("  {} -- {}".format(source.name, size(total)))
    say("  -> {}".format(target))
    attached.remove(target)
    attached.push(source, "{}/games/".format(files))
    attached.shell("sync")

    # assert the one file the app actually opens, rather than trusting that the push returned.
    if not attached.exists(target + "/eboot.bin"):
        raise Refusal("no eboot.bin at {} after staging".format(target))
    say("  staged. a launch names it with --game \"{}\"".format(source.name))


# --- the guest libraries -------------------------------------------------------------------------------


def stage_guest_libs(attached, files):
    """the x86-64 set, including the two generated ones.

    the thunk guest halves live in the same directory and are found the same way, so they go across
    with it rather than being a staging step of their own.

    **this is an override rather than the way the set reaches a device.** the APK carries it and the
    app unpacks it to internal storage, which is what lets an install nobody has ever plugged in run
    a game; what this writes is on external storage, and the app prefers it whenever it is there. so
    it is the fast loop for a rebuilt thunk stub, and nothing else needs it.

    **it never expires, and that is the hazard to hold on to.** a set staged weeks ago keeps winning
    after an app update ships newer stubs, and the failure then is a guest resolving an old
    `libvulkan.so.1` against a new host thunk -- a version skew across the thunk boundary that will
    not present as "the staged libraries are old". the launch log names which of the two tiers
    answered, every run, and `--restage` is the fix.
    """
    step("the guest libraries")
    local = sorted(p for p in paths.GUEST_LIBS_X86_64.iterdir() if p.is_file())
    if not local:
        raise Refusal("no guest libraries at {}. run: py scripts/fetch-guest-libs.py".format(
            paths.relative(paths.GUEST_LIBS_X86_64)))

    target = "{}/guest-libs".format(files)
    attached.mkdir(target)
    say("  {} libraries -> {}".format(len(local), target))
    for path in local:
        attached.push_quietly(path, target + "/")

    # **the license directory goes too, and it is named rather than swept up.** the loop above takes
    # files, so a directory beside them is silently not staged -- and what is in this one is the
    # terms these binaries are redistributed under, which is the last thing that should go missing
    # by omission. it is a directory rather than loose files because keeping the linker's search
    # path to libraries is worth one explicit push.
    licenses = paths.GUEST_LIBS_X86_64 / "licenses"
    if licenses.is_dir():
        attached.remove(target + "/licenses")
        attached.push(licenses, target + "/")
    attached.shell("sync")

    # assert the count, and assert the two the guest cannot start without: the interpreter, and what
    # everything else needs.
    on_device = attached.list(target)
    if len(on_device) < len(local):
        raise Refusal("{} of {} libraries landed at {}".format(
            len(on_device), len(local), target))
    for needed in ("ld-linux-x86-64.so.2", "libc.so.6"):
        if needed not in on_device:
            raise Refusal("{} is not on the device".format(needed))
    if licenses.is_dir() and not attached.is_directory(target + "/licenses"):
        raise Refusal("licenses/ did not land at {}".format(target))
    say("  staged, {} verified".format(len(on_device)))


# --- a GPU driver ------------------------------------------------------------------------------------


def stage_driver(attached, files, source, name):
    """a driver package, unpacked here and pushed as its two files.

    **this is not where the driver is loaded from.** it lands on external storage, which is the one
    place both a shell and the app can reach without a picker -- and the driver loader will not touch
    it there, because it opens the library and the linker refuses one sitting where another app could
    have written it. the app copies it onto internal storage at launch, which is the same two-step
    the reference implementation takes and for the same reason.
    """
    step("the GPU driver")
    staging = fresh(paths.BUILD / "driver")
    with zipfile.ZipFile(str(source)) as zipped:
        zipped.extractall(str(staging))

    meta_path = staging / "meta.json"
    if not meta_path.exists():
        raise Refusal("{} has no meta.json -- is it a driver package?".format(source.name))
    import json
    meta = json.loads(read_text(meta_path))
    # the metadata is what names the library, so a package whose library is called something else
    # works without this script knowing anything about a particular driver.
    library = meta.get("libraryName")
    if not library:
        raise Refusal("{}'s meta.json has no libraryName".format(source.name))
    if not (staging / library).exists():
        raise Refusal("{} names {} and the package does not contain it".format(
            source.name, library))

    name = name or device.driver_name(meta, source.stem)
    say("  {} -- {}, {}".format(meta.get("name", name), meta.get("driverVersion", "?"), library))

    target = "{}/gpu-drivers/{}".format(files, name)
    attached.mkdir(target)
    for leaf in ("meta.json", library):
        attached.push_quietly(staging / leaf, "{}/{}".format(target, leaf))
    attached.shell("sync")

    if not attached.exists("{}/{}".format(target, library)):
        raise Refusal("{} did not land at {}".format(library, target))
    say("  staged to {}".format(target))
    say("  a launch names it with --driver {}".format(name))


# --- the shell binary ------------------------------------------------------------------------------


def stage_shell(attached, toolchain):
    """the host layer as an executable, into the directory that belongs to no app.

    **the shell binary is not a legacy path.** the host layer builds twice from one set of objects:
    a library the APK loads, and an executable. the regression modes run the executable, and it is a
    far better place to bisect a translation problem from than an activity is.

    it is a push from the build tree rather than a copy from somewhere else on the device, so what
    lands is always what was just built. no application id is involved, because the directory it
    goes to belongs to no app -- which is the whole reason it is used.
    """
    step("the shell binary")
    if not paths.HOST_SHELL.exists():
        raise Refusal("no shell binary at {}. run: py scripts/build-host.py".format(
            paths.relative(paths.HOST_SHELL)))

    # the C++ runtime has to sit beside it: the host layer links it dynamically and out here there
    # is no app library directory to find it in.
    stl = toolchain.ndk_sysroot / "usr" / "lib" / "aarch64-linux-android" / "libc++_shared.so"
    if not stl.exists():
        raise Refusal("libc++_shared.so is not where the NDK should have it: {}".format(stl))

    target = device.SHELL_DIRECTORY
    attached.mkdir(target + "/guest-libs")

    pushed = 0
    for path in (paths.HOST_SHELL, stl, paths.HOST / "regression.sh"):
        if not Path(path).exists():
            raise Refusal("missing {}".format(path))
        pushed += attached.push_quietly(path, target + "/")

    if not paths.BUILD_GUESTS.is_dir():
        raise Refusal("no test guests at {}. run: py scripts/build-guests.py".format(
            paths.relative(paths.BUILD_GUESTS)))
    guests = [p for p in sorted(paths.BUILD_GUESTS.iterdir()) if p.is_file()]
    for path in guests:
        pushed += attached.push_quietly(path, target + "/")

    libraries = [p for p in sorted(paths.GUEST_LIBS_X86_64.iterdir()) if p.is_file()]
    for path in libraries:
        pushed += attached.push_quietly(path, target + "/guest-libs/")
    # one regression mode runs a staged glibc program, which lives beside the libraries it needs.
    binaries = paths.GUEST_LIBS / "bin"
    if binaries.is_dir():
        for path in sorted(binaries.iterdir()):
            if path.is_file():
                pushed += attached.push_quietly(path, target + "/")

    attached.shell("chmod 755 {0}/sharpdroid-host-layer {0}/regression.sh".format(target))
    attached.shell("sync")

    if attached.size_of(target + "/sharpdroid-host-layer") != paths.HOST_SHELL.stat().st_size:
        raise Refusal("the shell binary on the device is not the one that was just built")
    say("  {} guests, {} libraries, {} in all -> {}".format(
        len(guests), len(libraries), size(pushed), target))


if __name__ == "__main__":
    main(entry)
