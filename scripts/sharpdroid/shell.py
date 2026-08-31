# how every script here talks, and how it runs things.
#
# two rules live in this one file because both were paid for.
#
# **a script refuses; it never prompts.** stdin is a pipe under CI and under anything driving these
# scripts, so a prompt either throws or reads EOF -- and a naive prompt takes EOF for a yes. a
# refusal instead names the words that resolve it, which is an instruction a person and a pipe can
# both act on.
#
# **a step that returned zero is not a step that produced something.** the most common failure in
# this project is a tool exiting cleanly having done nothing: a package manager installing no
# package, a staging step reporting success and leaving nothing behind. so every script asserts the
# artefact it was supposed to produce rather than trusting an exit code.

import os
import shutil
import subprocess
import sys
import time
from pathlib import Path


class Refusal(Exception):
    """a refusal the caller can act on. printed without a traceback, since a stack of our own
    frames tells the reader nothing they can use."""


def say(text=""):
    print(text, flush=True)


def step(text):
    say("")
    say("== " + text)


def warn(text):
    say("warning: " + text)


def size(n):
    return "{:,} bytes".format(n)


def run(argv, cwd=None, env=None, check=True, quiet=False):
    """run a program with an argument vector and no shell between us and it.

    the list form is the whole point. a game directory is named after its title and so has spaces
    in it, an android device path is full of slashes, and a shell between us and the callee would
    give each of them quoting rules that differ per tool -- here neither is ever parsed by anything. what the callee receives
    is what was passed.
    """
    argv = [str(a) for a in argv]
    if not quiet:
        say("  $ " + " ".join(_readable(a) for a in argv))
    result = subprocess.run(argv, cwd=_maybe_str(cwd), env=env)
    if check and result.returncode != 0:
        raise Refusal("{} exited {}".format(Path(argv[0]).name, result.returncode))
    return result.returncode


def capture(argv, cwd=None, env=None, check=True):
    """the same, but the output comes back as text instead of going to the console."""
    argv = [str(a) for a in argv]
    result = subprocess.run(
        argv, cwd=_maybe_str(cwd), env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        encoding="utf-8", errors="replace",
    )
    if check and result.returncode != 0:
        raise Refusal("{} exited {}:\n{}".format(
            Path(argv[0]).name, result.returncode, (result.stdout or "").strip()))
    return result.stdout or ""


def produced(path, what, quiet=False):
    """assert the artefact that was supposed to appear, and say how big it is.

    the size is not decoration. a zero-length library and a missing one fail in different places
    and only one of them is obvious, and a byte count printed at the moment of production is what
    later lets a staging step tell yesterday's bytes from today's.
    """
    path = Path(path)
    if not path.exists():
        raise Refusal("{} was not produced: {}".format(what, path))
    if path.is_file():
        if path.stat().st_size == 0:
            raise Refusal("{} is empty: {}".format(what, path))
        if not quiet:
            say("  {}  {}".format(path.name, size(path.stat().st_size)))
    return path


def tree_size(path):
    """total bytes of a directory tree, or of a file."""
    path = Path(path)
    if path.is_file():
        return path.stat().st_size
    return sum(p.stat().st_size for p in path.rglob("*") if p.is_file())


def wipe(path):
    """remove a directory and everything under it, without minding that it was not there."""
    shutil.rmtree(str(path), ignore_errors=True)


def fresh(path):
    """an empty directory, whatever was there before."""
    wipe(path)
    Path(path).mkdir(parents=True, exist_ok=True)
    return Path(path)


def ensure(path):
    Path(path).mkdir(parents=True, exist_ok=True)
    return Path(path)


def write_text(target, text):
    """write a file with LF line endings, whatever platform this is.

    everything in this repository is LF and `.gitattributes` enforces it, so a generated file
    written with the platform's own endings comes back modified the moment it is committed -- and
    the generated thunk sources are committed, so that would happen on every run of the generator.
    """
    Path(target).parent.mkdir(parents=True, exist_ok=True)
    with open(str(target), "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def read_text(source):
    return Path(source).read_text(encoding="utf-8", errors="replace")


def newer(source, target):
    """true when the target has to be rebuilt: it is missing, or something in source is newer.

    source may be one path or several, and either files or directories.
    """
    target = Path(target)
    if not target.exists():
        return True
    stamp = target.stat().st_mtime
    sources = source if isinstance(source, (list, tuple)) else [source]
    for s in sources:
        s = Path(s)
        if not s.exists():
            continue
        if s.is_file():
            if s.stat().st_mtime > stamp:
                return True
        else:
            for p in s.rglob("*"):
                if p.is_file() and p.stat().st_mtime > stamp:
                    return True
    return False


class Timer:
    """elapsed wall time for a stretch of work, printed the way a build log wants it."""

    def __enter__(self):
        self.started = time.monotonic()
        return self

    def __exit__(self, *exc):
        self.seconds = time.monotonic() - self.started
        return False

    def __str__(self):
        return "{:.1f}s".format(self.seconds)


def main(entry):
    """run a script's entry point, turning a refusal into a message and an exit code.

    exit 2 rather than 1, so a caller can tell "this refused, and told you why" from "the tool it
    ran failed". anything else propagates with its traceback, because an unexpected exception is a
    bug in these scripts and hiding it would cost more than it saves.
    """
    try:
        entry()
    except Refusal as refusal:
        say("")
        say("refused: {}".format(refusal))
        sys.exit(2)
    except KeyboardInterrupt:
        say("")
        say("interrupted")
        sys.exit(130)


def environment(**overrides):
    """the current environment with some names changed, for handing to a child process."""
    env = dict(os.environ)
    for name, value in overrides.items():
        if value is None:
            env.pop(name, None)
        else:
            env[name] = str(value)
    return env


def _maybe_str(path):
    return None if path is None else str(path)


def _readable(argument):
    """one argument as an echoed command line reads it.

    a path inside the repository is shortened to a repository-relative one. these commands are long
    -- a cmake configure is a dozen absolute paths -- and the part a reader is looking for is the
    part that is not the same on every line. anything outside the repository stays absolute, so the
    line still says where a tool came from.
    """
    from . import paths
    prefix = str(paths.ROOT) + os.sep
    # a compiler flag with the path glued to it, `-Lsomewhere`, is the common case and reads worst.
    head, shortened = "", argument
    if not argument.startswith(prefix) and prefix in argument:
        head, shortened = argument.split(prefix, 1)
    elif argument.startswith(prefix):
        shortened = argument[len(prefix):]
    line = head + shortened
    return '"{}"'.format(line) if " " in line else line
