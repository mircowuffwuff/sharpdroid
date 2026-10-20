#include "elf_loader.h"
#include "vma_tracker.h"

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

namespace HostLayer {

namespace {

uint64_t PageSizeCached() {
  // read at runtime rather than assuming 4096: android also ships 16k-page configurations,
  // and the Odin 3 being a 4k device is not something to bake into the loader.
  static const uint64_t Size = static_cast<uint64_t>(::sysconf(_SC_PAGESIZE));
  return Size;
}

uint64_t AlignDown(uint64_t Value, uint64_t Alignment) {
  return Value & ~(Alignment - 1);
}

uint64_t AlignUp(uint64_t Value, uint64_t Alignment) {
  return AlignDown(Value + Alignment - 1, Alignment);
}

bool ReadExactly(int FD, void* Dest, size_t Size, off_t Offset) {
  auto* Cursor = static_cast<uint8_t*>(Dest);
  while (Size) {
    const ssize_t Got = ::pread(FD, Cursor, Size, Offset);
    if (Got <= 0) {
      return false;
    }
    Cursor += Got;
    Offset += Got;
    Size -= static_cast<size_t>(Got);
  }
  return true;
}

// PF_X deliberately does not become PROT_EXEC.
//
// FEX never executes the guest mapping -- it *reads* those bytes and emits arm64 into its own
// code buffers -- so the guest image only ever needs to be readable. dropping PROT_EXEC is not
// a shortcut, it is what makes this work inside an android app at all: apps are denied
// `execute` on their own app_data_file, so a file the app wrote could never be mapped
// executable. an emulator that needed PROT_EXEC on guest images would be stuck; this host layer
// does not need it.
// what the guest asked for, PF_X and all. this is what the VMA tracker records; ProtFromPhdrFlags
// below is the same thing with PROT_EXEC taken back off, which is all the host kernel ever sees.
int GuestProtFromPhdrFlags(uint32_t Flags) {
  int Prot = 0;
  if (Flags & PF_R) {
    Prot |= PROT_READ;
  }
  if (Flags & PF_W) {
    Prot |= PROT_WRITE;
  }
  if (Flags & PF_X) {
    Prot |= PROT_EXEC;
  }
  return Prot ? Prot : PROT_READ;
}

int ProtFromPhdrFlags(uint32_t Flags) {
  int Prot = 0;
  if (Flags & PF_R) {
    Prot |= PROT_READ;
  }
  if (Flags & PF_W) {
    Prot |= PROT_WRITE;
  }
  if (Flags & PF_X) {
    Prot |= PROT_READ;
  }
  return Prot ? Prot : PROT_READ;
}

} // namespace

LoadedELF LoadELF64(const char* Path, uint64_t PIEBase) {
  LoadedELF Result {};
  const uint64_t PageSize = PageSizeCached();

  const int FD = ::open(Path, O_RDONLY | O_CLOEXEC);
  if (FD < 0) {
    Result.Error = "cannot open guest binary";
    return Result;
  }

  Elf64_Ehdr Header {};
  if (!ReadExactly(FD, &Header, sizeof(Header), 0)) {
    ::close(FD);
    Result.Error = "short read on ELF header";
    return Result;
  }

  if (std::memcmp(Header.e_ident, ELFMAG, SELFMAG) != 0 || Header.e_ident[EI_CLASS] != ELFCLASS64 ||
      Header.e_ident[EI_DATA] != ELFDATA2LSB) {
    ::close(FD);
    Result.Error = "not a little-endian 64-bit ELF";
    return Result;
  }
  if (Header.e_machine != EM_X86_64) {
    ::close(FD);
    Result.Error = "not an x86-64 ELF";
    return Result;
  }
  if (Header.e_type != ET_EXEC && Header.e_type != ET_DYN) {
    ::close(FD);
    Result.Error = "not an executable or shared-object ELF";
    return Result;
  }
  if (Header.e_phentsize != sizeof(Elf64_Phdr) || Header.e_phnum == 0 || Header.e_phnum > 128) {
    ::close(FD);
    Result.Error = "implausible program header table";
    return Result;
  }

  Elf64_Phdr Phdrs[128] {};
  if (!ReadExactly(FD, Phdrs, sizeof(Elf64_Phdr) * Header.e_phnum, static_cast<off_t>(Header.e_phoff))) {
    ::close(FD);
    Result.Error = "short read on program headers";
    return Result;
  }

  // a PT_INTERP means the binary wants ld.so mapped alongside it and control handed to the
  // interpreter rather than to e_entry. record it and carry on: mapping this image is the same
  // work either way, and LoadProgram decides what to do about it.
  for (uint16_t i = 0; i < Header.e_phnum; ++i) {
    const auto& Phdr = Phdrs[i];
    if (Phdr.p_type != PT_INTERP) {
      continue;
    }
    if (Phdr.p_filesz == 0 || Phdr.p_filesz > sizeof(Result.InterpPath)) {
      ::close(FD);
      Result.Error = "implausible PT_INTERP length";
      return Result;
    }
    if (!ReadExactly(FD, Result.InterpPath, Phdr.p_filesz, static_cast<off_t>(Phdr.p_offset))) {
      ::close(FD);
      Result.Error = "short read on PT_INTERP";
      return Result;
    }
    // PT_INTERP is NUL-terminated in the file, but trusting that would let a malformed image
    // walk off the end of the buffer.
    Result.InterpPath[sizeof(Result.InterpPath) - 1] = '\0';
    break;
  }

  uint64_t LowVaddr = ~0ULL;
  uint64_t HighVaddr = 0;
  for (uint16_t i = 0; i < Header.e_phnum; ++i) {
    const auto& Phdr = Phdrs[i];
    if (Phdr.p_type != PT_LOAD || Phdr.p_memsz == 0) {
      continue;
    }
    LowVaddr = std::min(LowVaddr, AlignDown(Phdr.p_vaddr, PageSize));
    HighVaddr = std::max(HighVaddr, AlignUp(Phdr.p_vaddr + Phdr.p_memsz, PageSize));
  }
  if (LowVaddr == ~0ULL) {
    ::close(FD);
    Result.Error = "no PT_LOAD segments";
    return Result;
  }

  const uint64_t Bias = Header.e_type == ET_DYN ? PIEBase : 0;
  const uint64_t SpanBegin = LowVaddr + Bias;
  const uint64_t SpanSize = HighVaddr - LowVaddr;

  // reserve the whole image span in one mapping, then fill it in segment by segment, rather
  // than issuing one mmap per PT_LOAD. two reasons: adjacent segments whose page-rounded
  // ranges touch cannot then collide with each other, and the pages arrive already zeroed, so
  // .bss and the partial tail page of a file-backed segment need no special handling.
  //
  // MAP_FIXED_NOREPLACE, not MAP_FIXED: if something of the host's is already living at the
  // guest's load address we want EEXIST, not silent destruction of the host's own mapping.
  void* Reserved = ::mmap(reinterpret_cast<void*>(SpanBegin), SpanSize, PROT_NONE,
                          MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE | MAP_NORESERVE, -1, 0);
  if (Reserved == MAP_FAILED || reinterpret_cast<uint64_t>(Reserved) != SpanBegin) {
    if (Reserved != MAP_FAILED) {
      ::munmap(Reserved, SpanSize);
    }
    ::close(FD);
    Result.Error = "could not reserve the guest image range (already occupied?)";
    return Result;
  }
  // the whole span as the reservation currently is -- unreadable. the per-segment Record calls
  // below then carve the real segments out of it, and whatever gap alignment leaves between them
  // stays PROT_NONE instead of inheriting a neighbour's executability.
  VMA::Record(SpanBegin, SpanSize, PROT_NONE);

  // anonymous pages read in by hand, not a file-backed mapping. see ProtFromPhdrFlags above:
  // an app cannot map its own files executable, and it is a short step from there to not
  // wanting file-backed guest images at all.
  for (uint16_t i = 0; i < Header.e_phnum; ++i) {
    const auto& Phdr = Phdrs[i];
    if (Phdr.p_type != PT_LOAD || Phdr.p_memsz == 0) {
      continue;
    }

    const uint64_t SegBegin = AlignDown(Phdr.p_vaddr + Bias, PageSize);
    const uint64_t SegEnd = AlignUp(Phdr.p_vaddr + Bias + Phdr.p_memsz, PageSize);

    if (::mprotect(reinterpret_cast<void*>(SegBegin), SegEnd - SegBegin, PROT_READ | PROT_WRITE) != 0) {
      ::munmap(reinterpret_cast<void*>(SpanBegin), SpanSize);
      ::close(FD);
      Result.Error = "mprotect RW failed while loading a segment";
      return Result;
    }
    if (Phdr.p_filesz && !ReadExactly(FD, reinterpret_cast<void*>(Phdr.p_vaddr + Bias), Phdr.p_filesz,
                                      static_cast<off_t>(Phdr.p_offset))) {
      ::munmap(reinterpret_cast<void*>(SpanBegin), SpanSize);
      ::close(FD);
      Result.Error = "short read on a PT_LOAD segment";
      return Result;
    }
  }

  // protections applied in a second pass, after every segment's bytes are in place: a segment
  // that ends up read-only must not be sealed while a later segment sharing its tail page is
  // still to be written.
  for (uint16_t i = 0; i < Header.e_phnum; ++i) {
    const auto& Phdr = Phdrs[i];
    if (Phdr.p_type != PT_LOAD || Phdr.p_memsz == 0) {
      continue;
    }
    const uint64_t SegBegin = AlignDown(Phdr.p_vaddr + Bias, PageSize);
    const uint64_t SegEnd = AlignUp(Phdr.p_vaddr + Bias + Phdr.p_memsz, PageSize);
    ::mprotect(reinterpret_cast<void*>(SegBegin), SegEnd - SegBegin, ProtFromPhdrFlags(Phdr.p_flags));

    // and tell the VMA tracker what the *guest* thinks this segment is, PF_X included. this is
    // the only record of it that will ever exist: the mprotect above deliberately does not carry
    // PROT_EXEC, so bionic cannot be asked later. without this the guest's own text is not
    // executable as far as FEXCore's decoder is concerned, and nothing runs at all.
    VMA::Record(SegBegin, SegEnd - SegBegin, GuestProtFromPhdrFlags(Phdr.p_flags));
  }
  ::close(FD);

  // AT_PHDR must name the program headers *as mapped*, which is where the guest's own startup
  // code will look for them. they are covered by a PT_LOAD in every sane binary; if they are
  // not, leaving AT_PHDR at 0 is better than pointing it somewhere untrue.
  for (uint16_t i = 0; i < Header.e_phnum; ++i) {
    const auto& Phdr = Phdrs[i];
    if (Phdr.p_type != PT_LOAD) {
      continue;
    }
    if (Header.e_phoff >= Phdr.p_offset && Header.e_phoff + sizeof(Elf64_Phdr) * Header.e_phnum <= Phdr.p_offset + Phdr.p_filesz) {
      Result.PhdrAddr = Phdr.p_vaddr + Bias + (Header.e_phoff - Phdr.p_offset);
      break;
    }
  }

  Result.Ok = true;
  Result.Entry = Header.e_entry + Bias;
  Result.LoadBias = Bias;
  Result.PhEnt = Header.e_phentsize;
  Result.PhNum = Header.e_phnum;
  Result.MappingBegin = SpanBegin;
  Result.MappingEnd = SpanBegin + SpanSize;
  Result.BrkBase = AlignUp(SpanBegin + SpanSize, PageSize);
  return Result;
}

// --- the interpreter -------------------------------------------------------------------------

namespace {

// PT_INTERP names "/lib64/ld-linux-x86-64.so.2" on anything built for a normal linux distro, and
// android has no /lib64. rather than fabricate that path on the device -- which the app could not
// do anyway, having no write access outside its own directory -- the basename is looked up in the
// directory the guest libraries were staged in.
bool ResolveInterp(const char* InterpPath, const char* SearchDir, char* Out, size_t OutSize) {
  if (::access(InterpPath, R_OK) == 0) {
    std::snprintf(Out, OutSize, "%s", InterpPath);
    return true;
  }
  if (!SearchDir || !*SearchDir) {
    return false;
  }
  const char* Base = std::strrchr(InterpPath, '/');
  Base = Base ? Base + 1 : InterpPath;
  std::snprintf(Out, OutSize, "%s/%s", SearchDir, Base);
  return ::access(Out, R_OK) == 0;
}

} // namespace

LoadedProgram LoadProgram(const char* Path, uint64_t PIEBase, uint64_t InterpBase, const char* InterpSearchDir) {
  LoadedProgram Result {};

  Result.Exec = LoadELF64(Path, PIEBase);
  if (!Result.Exec.Ok) {
    Result.Error = Result.Exec.Error;
    return Result;
  }

  if (Result.Exec.InterpPath[0] == '\0') {
    // static. AT_BASE stays 0, which is how the kernel tells a program it has no interpreter.
    Result.Ok = true;
    Result.StartRIP = Result.Exec.Entry;
    return Result;
  }

  char Resolved[512];
  if (!ResolveInterp(Result.Exec.InterpPath, InterpSearchDir, Resolved, sizeof(Resolved))) {
    Result.Error = "cannot find the program interpreter named by PT_INTERP";
    return Result;
  }

  // the interpreter is loaded at a base of its own, well clear of the program image and of the
  // brk arena that gets reserved immediately past it. it is an ET_DYN like any other, so this is
  // the same code path, only biased somewhere else.
  Result.Interp = LoadELF64(Resolved, InterpBase);
  if (!Result.Interp.Ok) {
    Result.Error = Result.Interp.Error;
    return Result;
  }
  if (Result.Interp.InterpPath[0] != '\0') {
    // an interpreter that itself asks for an interpreter is not a chain anyone unwinds; the
    // kernel refuses it too.
    Result.Error = "the program interpreter has a PT_INTERP of its own";
    return Result;
  }

  Result.Ok = true;
  Result.InterpBase = Result.Interp.LoadBias;
  // control goes to the interpreter. the program's own entry still reaches the guest, as AT_ENTRY
  // -- ld.so relocates everything and then jumps there itself.
  Result.StartRIP = Result.Interp.Entry;
  return Result;
}

// --- the initial stack ---------------------------------------------------------------------
//
// what the kernel hands _start on x86-64, low address first:
//
//   RSP -> argc
//          argv[0] .. argv[argc-1], NULL
//          envp[0] .. envp[n-1],    NULL
//          auxv pairs, terminated by AT_NULL
//          (padding)
//          the strings the pointers above point into
//
// RSP is 16-byte aligned on entry. getting that wrong is not a crash at _start but a fault
// hundreds of instructions later inside the first SSE store to a stack local, which is a
// miserable thing to debug -- hence the explicit alignment step below.

namespace {

// the generic auxv keys. spelled out rather than taken from <linux/auxvec.h> so it is obvious
// these are the *guest's* values and not whatever the host arm64 headers happen to define.
constexpr uint64_t AT_NULL_ = 0;
constexpr uint64_t AT_PHDR_ = 3;
constexpr uint64_t AT_PHENT_ = 4;
constexpr uint64_t AT_PHNUM_ = 5;
constexpr uint64_t AT_PAGESZ_ = 6;
constexpr uint64_t AT_BASE_ = 7;
constexpr uint64_t AT_FLAGS_ = 8;
constexpr uint64_t AT_ENTRY_ = 9;
constexpr uint64_t AT_UID_ = 11;
constexpr uint64_t AT_EUID_ = 12;
constexpr uint64_t AT_GID_ = 13;
constexpr uint64_t AT_EGID_ = 14;
constexpr uint64_t AT_PLATFORM_ = 15;
constexpr uint64_t AT_HWCAP_ = 16;
constexpr uint64_t AT_CLKTCK_ = 17;
constexpr uint64_t AT_SECURE_ = 23;
constexpr uint64_t AT_RANDOM_ = 25;
constexpr uint64_t AT_HWCAP2_ = 26;
constexpr uint64_t AT_EXECFN_ = 31;

} // namespace

GuestStack BuildGuestStack(const LoadedProgram& Program, const char* GuestPath, int Argc, const char* const* Argv,
                           const char* const* Envp, size_t StackSize) {
  GuestStack Result {};
  // every auxv entry below describes the *program*, never the interpreter -- AT_PHDR, AT_PHNUM and
  // AT_ENTRY are how ld.so finds the thing it was asked to run. AT_BASE is the one exception, and
  // is where the interpreter learns its own load address.
  const LoadedELF& Elf = Program.Exec;
  const uint64_t PageSize = PageSizeCached();
  StackSize = AlignUp(StackSize, PageSize);

  void* Stack = ::mmap(nullptr, StackSize, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
  if (Stack == MAP_FAILED) {
    Result.Error = "could not map the guest stack";
    return Result;
  }
  const uint64_t Base = reinterpret_cast<uint64_t>(Stack);
  uint64_t Cursor = Base + StackSize;

  // recorded read-write and *not* executable, which is what linux gives a modern program. it earns
  // its place in the map even so: an accidental jump onto the stack now decodes to nothing rather
  // than to whatever the guest last pushed.
  VMA::Record(Base, StackSize, PROT_READ | PROT_WRITE);

  auto PushBytes = [&](const void* Data, size_t Size) -> uint64_t {
    Cursor -= Size;
    std::memcpy(reinterpret_cast<void*>(Cursor), Data, Size);
    return Cursor;
  };
  auto PushString = [&](const char* Text) -> uint64_t {
    return PushBytes(Text, std::strlen(Text) + 1);
  };

  int EnvCount = 0;
  while (Envp && Envp[EnvCount]) {
    ++EnvCount;
  }

  uint64_t ArgvPtrs[64] {};
  if (Argc > 64) {
    ::munmap(Stack, StackSize);
    Result.Error = "too many guest arguments";
    return Result;
  }
  uint64_t EnvPtrs[128] {};
  if (EnvCount > 128) {
    ::munmap(Stack, StackSize);
    Result.Error = "too many guest environment variables";
    return Result;
  }

  // strings first, at the very top, in reverse so the resulting layout reads forwards.
  const uint64_t ExecFnAddr = PushString(GuestPath);
  const uint64_t PlatformAddr = PushString("x86_64");
  for (int i = EnvCount - 1; i >= 0; --i) {
    EnvPtrs[i] = PushString(Envp[i]);
  }
  for (int i = Argc - 1; i >= 0; --i) {
    ArgvPtrs[i] = PushString(Argv[i]);
  }

  // AT_RANDOM: 16 bytes the guest libc uses to seed its stack canary and, in glibc's case,
  // pointer mangling. it must be a valid guest address with 16 readable bytes behind it.
  uint8_t Random[16] {};
  ::getentropy(Random, sizeof(Random));
  Cursor = AlignDown(Cursor, 16);
  const uint64_t RandomAddr = PushBytes(Random, sizeof(Random));

  const uint64_t AuxV[][2] {
    {AT_PHDR_, Elf.PhdrAddr},
    {AT_PHENT_, Elf.PhEnt},
    {AT_PHNUM_, Elf.PhNum},
    {AT_PAGESZ_, PageSize},
    // AT_BASE is the load address of the *interpreter*. static binaries have none, and 0 is
    // how the kernel says so.
    {AT_BASE_, Program.InterpBase},
    {AT_FLAGS_, 0},
    {AT_ENTRY_, Elf.Entry},
    {AT_UID_, ::getuid()},
    {AT_EUID_, ::geteuid()},
    {AT_GID_, ::getgid()},
    {AT_EGID_, ::getegid()},
    {AT_PLATFORM_, PlatformAddr},
    // AT_HWCAP on x86 carries the CPUID leaf-1 EDX feature bits. left at 0 deliberately: both
    // glibc and bionic issue CPUID themselves for feature detection, which FEX answers from
    // RunCPUIDFunction, so a fabricated value here could only ever disagree with that.
    {AT_HWCAP_, 0},
    {AT_HWCAP2_, 0},
    {AT_CLKTCK_, 100},
    {AT_SECURE_, 0},
    {AT_RANDOM_, RandomAddr},
    {AT_EXECFN_, ExecFnAddr},
    {AT_NULL_, 0},
  };
  constexpr size_t AuxCount = sizeof(AuxV) / sizeof(AuxV[0]);

  const size_t VectorSlots = 1                                  // argc
                             + static_cast<size_t>(Argc) + 1    // argv + NULL
                             + static_cast<size_t>(EnvCount) + 1 // envp + NULL
                             + AuxCount * 2;
  uint64_t RSP = AlignDown(Cursor - VectorSlots * 8, 16);

  auto* Slot = reinterpret_cast<uint64_t*>(RSP);
  *Slot++ = static_cast<uint64_t>(Argc);
  for (int i = 0; i < Argc; ++i) {
    *Slot++ = ArgvPtrs[i];
  }
  *Slot++ = 0;
  for (int i = 0; i < EnvCount; ++i) {
    *Slot++ = EnvPtrs[i];
  }
  *Slot++ = 0;
  for (size_t i = 0; i < AuxCount; ++i) {
    *Slot++ = AuxV[i][0];
    *Slot++ = AuxV[i][1];
  }

  Result.Ok = true;
  Result.RSP = RSP;
  Result.Base = Base;
  Result.Size = StackSize;
  return Result;
}

} // namespace HostLayer
