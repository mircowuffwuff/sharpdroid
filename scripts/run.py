# build it, put it on the device, start it, and show you the log. one command.
#
#   py scripts/run.py                                       the app's own game list, no guest
#   py scripts/run.py --game existing                       whatever game is on the device
#   py scripts/run.py --game "Y:\games\Dead Cells [PPSA15552]"
#   py scripts/run.py --game "/storage/emulated/0/roms/ps5/Dead Cells [PPSA15552]"
#   py scripts/run.py --game existing --sharpemu build      publish and package the fork first
#   py scripts/run.py --game existing --driver Turnip.zip --turbo
#   py scripts/run.py --game existing --seconds 90 --no-logs
#
# **it installs under its own application id.** the debug app is a different app to android -- its
# own internal storage, its own external files directory, its own save data -- so a deploy loop
# cannot disturb a personal install on the same phone.
#
# **omitting an argument is not "pick one for me".** it names nothing and lets the app answer:
#
#   no --game       the app opens its game list and no guest runs. this is the frontend run, and it
#                   is what running this with nothing else said gets you
#   no --sharpemu   no build is named, so the app runs what its build manager settled on -- which on
#                   an untouched install is the build it ships with. **that is a choice the app holds
#                   across runs**, so a run naming no build is not necessarily a run on the bundled
#                   one. name one when it has to be a particular build
#   no --driver     the app loads what its settings hold, which on an untouched install is the
#                   platform's own. `--driver none` pins that regardless of what is stored
#
# **this stages nothing the APK already carries.** the guest's x86-64 libraries ship inside it and
# the app unpacks them on the launch that needs them, so a deploy is the install and nothing else.
# staging them was once done here automatically, whenever fewer than twenty of the twenty-eight were
# on the device -- and that is precisely why nobody noticed for months that the APK carried none at
# all: the one machine able to see the gap re-created the missing set on every run, so the unpack
# path a user takes was never executed where a failure in it could be observed.
# `py scripts/stage.py --guest-libs` is still there, and is now only ever run deliberately.
#
# **the SharpEmu build is held constant unless you say otherwise**, and that is deliberate rather
# than lazy. rebuilding the emulator on every change to the host layer moves two variables per
# iteration and hands you a different payload from the one your last measurement used, which is the
# single mistake this project has recorded most often.
#
# **but a build that no longer matches the fork checkout is said out loud**, because the other half
# of that mistake is editing the fork and testing yesterday's payload with nothing erroring. a build
# records the commit it was cut from, marker and all, and the checkout is asked the same question, so
# an edit that has not been committed is a difference like any other -- which is the case this exists
# for -- and two trees that both have edits in them are a difference nothing can resolve rather than
# a match. naming a build prints the difference and carries on -- you chose it, it is yours to own --
# and a run that named none at all **stops**, because nobody chose and the two answers are not
# equivalent. it is never a question:
# stdin here is a pipe under anything driving this, and a prompt nobody can answer is a script
# nobody can automate.

import re
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sharpdroid import builds, device, paths, resolve, vocabulary
from sharpdroid import toolchain as tc
from sharpdroid.shell import Refusal, ensure, main, read_text, say, size, step, write_text
from sharpdroid.vocabulary import Parser

HERE = Path(__file__).resolve().parent

# the log tags a run is followed by: this project's own, plus the three the platform reports a crash
# through. anything else is the system's, and burying the emulator's output in it helps nobody.
LOG_TAGS = ["sharpdroid:V", "AndroidRuntime:E", "DEBUG:E", "libc:E", "*:S"]


def entry():
    parser = Parser(description="build, stage, launch and follow one run")
    vocabulary.add_game(parser)
    vocabulary.add_sharpemu(parser)
    vocabulary.add_driver(parser)
    vocabulary.add_package(parser)
    vocabulary.add_common(parser)
    parser.add_argument("--game-uri", metavar="URI", default=None,
                        help="hand the app a content:// tree uri on the intent itself, the way an "
                             "emulation frontend does, instead of naming a game with --game.")
    parser.add_argument("--turbo", action="store_true", help="the guest's turbo flag.")
    parser.add_argument("--audio-watchdog", action="store_true",
                        help="report the audio stream's state once a second whether or not the guest is "
                             "submitting. without it a run that stops submitting says nothing at all.")
    parser.add_argument("--log-tids", action="store_true",
                        help="stamp every guest log line with the host thread that wrote it. "
                             "for tying a thread the emulator names to a counter that names a thread id.")
    parser.add_argument("--guest-env", metavar="NAME=VALUE", default=None,
                        help="extra guest environment, comma separated.")
    parser.add_argument("--smc", choices=("none", "mtrack", "full"), default=None,
                        help="how self-modifying code is tracked.")
    parser.add_argument("--fex-preset",
                        choices=("stability", "compatibility", "intermediate", "performance",
                                 "none"),
                        default=None,
                        help="the JIT preset. every rung names every knob, so naming one takes the "
                             "whole configuration with it and the rows the settings scene overrides "
                             "are dropped. none passes no --fex at all and lets FEXCore and the "
                             "host layer decide, which is the vector this project's older figures "
                             "were taken on. omitted leaves whatever that scene stored.")
    parser.add_argument("--fex", metavar="NAME=VALUE", default=None,
                        help="extra FEXCore options, comma separated, appended after the preset and "
                             "after any row set in the settings scene, so this wins over both. "
                             "the host layer refuses a name FEXCore does not have.")
    parser.add_argument("--host-features", choices=("probe", "minimal"), default=None,
                        help="how FEXCore is told what this CPU can do. probe reads the ID "
                             "registers and is what a launch does when nothing is said; minimal is "
                             "the conservative set, and is here so the probe has an arm to be "
                             "measured against without a second APK.")
    parser.add_argument("--profile", action="store_true",
                        help="the vulkan profile. every 300 frames it prints where a frame went -- "
                             "how much of it was spent inside vulkan and how much outside, which is "
                             "what separates a slow driver from slow guest code. it is not free: it "
                             "times every command, which on a title submitting some 27 times a "
                             "frame is several times the cost of the calls themselves, so it "
                             "answers which side of the boundary a frame went and never how fast "
                             "something is.")
    parser.add_argument("--check", action="store_true",
                        help="run the regression set before deploying.")
    parser.add_argument("--seconds", metavar="N", type=int, default=0,
                        help="stop after this many seconds and summarise. otherwise the log is "
                             "followed until the run ends.")
    parser.add_argument("--no-logs", action="store_true", help="do not print the log.")
    parser.add_argument("--no-build", action="store_true",
                        help="launch what is installed rather than building first.")
    parser.add_argument("--restage", action="store_true",
                        help="push over what the device has, whatever the byte counts say.")
    arguments = parser.parse_args()

    # **no game is the list run**, and it is the whole of how one is asked for. there is nothing
    # left for a flag to say: a run naming no game is a run with no guest.
    #
    # **--game-uri names one too**, on the intent rather than in an extra, which is how an app
    # outside this one names a game. it stages nothing -- a uri points at a dump that is already on
    # the device -- so it is a guest run that skips the step --game would have taken.
    if arguments.game and arguments.game_uri:
        raise Refusal("--game and --game-uri both name a game, and an intent carries one.\n"
                      "  --game <value>     the extra the game list sends\n"
                      "  --game-uri <uri>   the intent data an emulation frontend sends")
    runs_guest = bool(arguments.game) or bool(arguments.game_uri)

    # **refused rather than dropped.** each of these is an extra only the guest activity reads, and
    # the game list neither receives nor honours any of them -- so accepting one would start a
    # screen that quietly is not the run that was asked for. the build and the driver are absent
    # from this list on purpose: naming one stages it, and staging is worth doing either way.
    if not runs_guest:
        guest_only = [name for name, given in (
            ("--turbo", arguments.turbo), ("--guest-env", arguments.guest_env),
            ("--log-tids", arguments.log_tids), ("--audio-watchdog", arguments.audio_watchdog),
            ("--smc", arguments.smc), ("--fex-preset", arguments.fex_preset),
            ("--fex", arguments.fex), ("--profile", arguments.profile),
            ("--host-features", arguments.host_features)) if given]
        if guest_only:
            raise Refusal(
                "no --game, so no guest runs and {} would have no effect. pass --game existing to "
                "run one, or drop the flag".format(", ".join(guest_only)))

    toolchain = tc.resolve().require("adb")
    attached = device.Device(toolchain, arguments.serial).require()
    package = device.application_id(arguments.package, arguments.release)
    activity = device.GUEST_ACTIVITY if runs_guest else device.LIST_ACTIVITY

    say("device {}, app {}".format(attached.serial, package))

    # **the fork is packaged before the APK and not after it**, because the APK bundles a build:
    # packaged afterwards, asking for `build` would ship the build *before* the one it just made,
    # which is the wrong-artefact failure wearing a fresh coat of paint.
    sharpemu = arguments.sharpemu
    if vocabulary.read(sharpemu).kind == vocabulary.BUILD:
        step("packaging the fork")
        _script(["package-build.py"])
        sharpemu = str(builds.newest().directory)

    if not arguments.no_build:
        step("building")
        passed = ["build.py", "--package", package, "--install"]
        if sharpemu and Path(str(sharpemu)).exists():
            # **the APK bundles the build this run is about, when this run has one here to bundle.**
            # `existing` and a device path name something only the phone has, so there is nothing
            # here to pack and the APK step falls back to the newest packaged build, which it says.
            passed += ["--sharpemu", str(sharpemu)]
        _script(passed)
    else:
        say("skipping the build")

    if not attached.installed(package):
        raise Refusal("{} is not installed. drop --no-build so the APK is built and installed"
                      .format(package))

    if arguments.check:
        step("the regression set")
        _script(["regression.py"])

    game = None
    if arguments.game:
        step("the game")
        game = resolve.game(attached, package, arguments.game, arguments.restage)

    step("the SharpEmu build")
    build_path = resolve.build(attached, package, sharpemu, arguments.restage)
    if build_path is None:
        say("  none named -- the app runs what its build manager settled on")

    if runs_guest:
        check_staleness(attached, toolchain, package, build_path, bool(sharpemu))

    driver = None
    if arguments.driver:
        step("the driver")
        driver = resolve.driver(attached, package, arguments.driver, arguments.restage)

    launch(attached, package, activity, runs_guest, game, build_path, driver, arguments)
    follow(attached, package, arguments)


# --- is the staged build still the fork checkout? -------------------------------------------------------


def check_staleness(attached, toolchain, package, build_path, named):
    """**the other half of holding a build constant is knowing when you did not mean to.**

    who gets told and who gets stopped follows from who chose. naming a build is a choice, so a
    difference is reported and the run carries on. naming nothing leaves the app to pick and nobody
    chose at all -- so a checkout that has moved is a run about to attribute your work to a payload
    that does not contain it, and it stops.
    """
    against = build_path
    if against is None:
        # with no build named there is no commit to read, so the most recently staged one is what
        # this can see. **it is not what the app will pick**: with nothing chosen that is the build
        # inside the APK, on internal storage, and the APK step compares that one against the
        # checkout as it seals it in. so this covers the tier `adb` can write, the message says it
        # is a guess, and the case it cannot see is answered where the mistake is actually made.
        files = device.external_files(package)
        staged = [name for name in attached.list(files + "/builds") if not name.startswith(".")]
        if staged:
            against = "{}/builds/{}".format(files, staged[-1])

    verdict, why = resolve.build_staleness(attached, toolchain, against)
    if verdict == builds.STALE:
        if named:
            say("")
            say("  ** {}".format(why))
            say("     --sharpemu build rebuilds it; this run uses the build named above **")
        else:
            raise Refusal(
                "{}.\n"
                "  no --sharpemu was given, so the app picks -- and nobody chose which of the two "
                "you meant.\n"
                "  --sharpemu build      publish and package the checkout, then run that\n"
                "  --sharpemu existing   run what is already staged, knowing it is behind\n"
                "  --sharpemu none       say explicitly that the app should pick".format(why))
    elif verdict == builds.UNKNOWN and named:
        # **said rather than swallowed.** a check that could not run is not a check that passed, and
        # a run printing nothing here would look exactly like one where the build matched.
        say("  fork    not compared -- {}".format(why))


# --- the launch ------------------------------------------------------------------------------------------


def launch(attached, package, activity, runs_guest, game, build_path, driver, arguments):
    step("launch")
    # **what a guest was given, and what it was not.** the "none named" lines are worth as much as
    # the others: a run whose build was left to the app is a run whose build is not in this output,
    # and the next reader of a log needs to know which of the two it was.
    if runs_guest:
        say("  game    {}".format(arguments.game_uri or game))
        if arguments.game_uri:
            say("          named on the intent, the way another app names one")
        if build_path:
            say("  build   {}".format(build_path))
            payload = attached.size_of(build_path + "/SharpEmu")
            say("  payload {}".format(size(payload) if payload is not None else "unknown"))
        else:
            say("  build   none named -- the app runs what its build manager settled on")
        say("  driver  {}".format(driver or "none named -- the app loads what its settings hold"))
    else:
        say("  screen  the game list -- no guest runs")
    if arguments.turbo:
        say("  turbo   on")
    if arguments.guest_env:
        say("  env     {}".format(arguments.guest_env))
    if arguments.fex_preset:
        say("  fex     {}".format(arguments.fex_preset))
    if arguments.fex:
        say("  knobs   {}".format(arguments.fex))
    if arguments.host_features:
        say("  cpu     {}".format(arguments.host_features))

    # **every extra is conditional on having been named**, which is what makes omitting an argument
    # reach the app's own default rather than a default this script picked. an extra carrying the
    # value the app would have chosen anyway is not the same thing: it makes the app stop choosing.
    #
    # **the list is started bare.** it takes no extras at all, and the app resolves a build, a driver
    # and a game from what its settings hold, which is the whole point of looking at it.
    extras = {}
    if runs_guest:
        # **a uri and a name are alternatives rather than a pair.** an app outside this one has only
        # the intent to name a game on, so a run simulating one must not also send the extra the game
        # list sends -- with both present the app would take the uri and the extra would be dead
        # weight nobody could see was ignored.
        extras["game"] = None if arguments.game_uri else game
        extras["sharpemu"] = build_path
        extras["driver"] = driver
        extras["guestenv"] = arguments.guest_env
        extras["smc"] = arguments.smc
        extras["fexpreset"] = arguments.fex_preset
        extras["fex"] = arguments.fex
        # the app's own switch is on by default, so only `minimal` is worth naming -- and naming
        # `probe` explicitly still has to travel, or it could not override a store that is off.
        if arguments.host_features:
            extras["hostprobe"] = arguments.host_features == "probe"
        if arguments.turbo:
            extras["turbo"] = True
        if arguments.log_tids:
            extras["logtids"] = True
        if arguments.audio_watchdog:
            extras["audiowatchdog"] = True
        if arguments.profile:
            extras["profile"] = True

    attached.force_stop(package)
    attached.clear_log()
    started = attached.start(package, activity, extras, data=arguments.game_uri)
    if "Error" in started or "Exception" in started:
        raise Refusal("the launch failed:\n{}".format(started.strip()))


# --- the log ----------------------------------------------------------------------------------------------


def follow(attached, package, arguments):
    """**the two modes are genuinely different, and each is done the way that is reliable for it.**

    a timed run waits and then dumps: reading the log after the fact is deterministic, and it is what
    every measurement in this project has been taken with. following live cannot be timed reliably,
    because a deadline checked between lines only fires when the next line arrives -- if the guest
    stops emitting, the run never ends.
    """
    log = ensure(paths.BUILD / "runs") / "run-{}.log".format(
        datetime.now().strftime("%Y%m%d-%H%M%S"))

    if arguments.no_logs and arguments.seconds <= 0:
        say("")
        say("started. not following the log")
        return

    if arguments.seconds > 0:
        say("")
        say("running for {} s".format(arguments.seconds))
        time.sleep(arguments.seconds)
        attached.force_stop(package)
        write_text(log, attached.adb_capture("logcat", "-d", "-v", "time", "-s", *LOG_TAGS,
                                             check=False))
        summarise(log)
        return

    say("")
    say("following the log -- Ctrl-C to stop")
    say("")
    argv = [str(attached.adb)]
    if attached.serial:
        argv += ["-s", attached.serial]
    argv += ["logcat", "-v", "time", "-s"] + LOG_TAGS
    lines = []
    try:
        process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   encoding="utf-8", errors="replace")
        for line in process.stdout:
            lines.append(line.rstrip("\n"))
            if not arguments.no_logs:
                print(line.rstrip("\n"), flush=True)
    except KeyboardInterrupt:
        pass
    finally:
        write_text(log, "\n".join(lines) + "\n")
        summarise(log)


def summarise(log):
    say("")
    say("== summary")
    text = read_text(log)

    for line in text.splitlines():
        if re.search(r"\[app\] build:|\[host-layer\] loaded|backend=", line):
            say("  " + re.sub(r"^.*sharpemu: ", "", line))

    frames = []
    for line in text.splitlines():
        found = re.search(r"\[vulkan\] presented frame (\d+)", line)
        if found:
            # the log's timestamps carry no year, and a bare month and day is ambiguous to a date
            # parser -- which warns about it, and would refuse a leap day outright. only the
            # difference between two of these is ever used, so a leap year is supplied to make the
            # one date that would not parse parse.
            try:
                when = datetime.strptime("2024-" + line[:18], "%Y-%m-%d %H:%M:%S.%f")
            except ValueError:
                continue
            frames.append((when, int(found.group(1))))

    # from frame 300: the first few hundred include shader compilation and the runtime tiering up,
    # and they are worth several frames a second of noise.
    #
    # **the window needs two lines in it to have a slope, and the presented-frame log is sparse.** a
    # short run logs one frame line and then another far later, which makes a steady-state window
    # one line wide and its span zero -- and a summary that then said nothing about frames at all,
    # neither a rate nor why there was none. **a run that reports no number has to say so**, so the
    # fallback is the whole range with boot in it, named as such so it is never mistaken for a
    # steady-state figure.
    if len(frames) >= 2:
        last = frames[-1]
        first = next((f for f in frames if f[1] >= 300), None)
        window = "from frame 300"
        if first is None or first[1] == last[1]:
            first = frames[0]
            window = "whole run, boot included -- too few frame lines for a steady-state window"
        span = (last[0] - first[0]).total_seconds()
        if span > 0:
            say("  frames {} -> {} in {:.1f} s  =  {:.2f} fps  ({})".format(
                first[1], last[1], span, (last[1] - first[1]) / span, window))
        else:
            say("  {} presented-frame lines, all at one timestamp -- no rate".format(len(frames)))
    elif len(frames) == 1:
        say("  one presented-frame line (frame {}) -- not enough for a rate".format(frames[0][1]))
    else:
        say("  no frames presented")

    # **count episodes rather than lines, and always say whether it recovered.** the watchdog prints
    # several diagnostic lines per stall, so matching every mention reported one event as four. and a
    # stall that recovers is a game going quiet, while a stall that does not is the open correctness
    # bug -- pooling the two is what made two of this project's published rates meaningless. an
    # episode begins at the line emitted exactly once per event.
    stalls = len(re.findall(r"\[audio-wd\] STALL: writes started", text))
    recovered = len(re.findall(r"\[audio-wd\].*recovered", text))
    fatal = [line.strip() for line in text.splitlines()
             if re.search(r"FATAL EXCEPTION|Invalid Program|UnsatisfiedLinkError", line)]

    if stalls:
        say("  audio: {} stall episode(s), {} recovered".format(stalls, recovered))
        if stalls - recovered > 0:
            say("  ** {} did not recover -- this is the known audio-stall bug, not something you "
                "just broke **".format(stalls - recovered))
    for line in fatal[:3]:
        say("  FATAL  " + line)
    if not stalls and not fatal:
        say("  no stall, no fatal error")
    say("")
    say("  full log: {}".format(paths.relative(log)))


def _script(arguments):
    entry_point = HERE / arguments[0]
    say("  $ py scripts\\{}".format(" ".join(arguments)))
    code = subprocess.run([sys.executable, str(entry_point)] + arguments[1:],
                          cwd=str(paths.ROOT)).returncode
    if code != 0:
        raise Refusal("{} exited {}".format(arguments[0], code))


if __name__ == "__main__":
    main(entry)
