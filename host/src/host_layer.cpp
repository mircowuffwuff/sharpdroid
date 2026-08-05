// sharpemu-android host layer.
//
// two modes:
//
//   sharpemu-host-layer --spike               24 bytes of hand-assembled x86-64. no loader, no
//                                             syscall table. this is the smoke test:
//                                             it proves the JIT still translates, executes and
//                                             faults on this device.
//   sharpemu-host-layer [--trace] <elf> [..]  load an x86-64 ELF and run it.
//
// the spike came first on purpose. android enforces W^X far more strictly than desktop linux
// and a JIT must write memory then execute it; Dispatcher::Create() allocating buffers was not
// proof they were usable. they are — so everything from the loader onwards is typing.
//
// this file is the driver and nothing else. the per-thread machinery it used to hold — the GDT,
// the call-return stack, the escape hatch, the fault handler and the dispatch loop — moved into
// guest_threads.cpp when there stopped being exactly one of each.
//
// and since the app arrived it is not the process entry either. what was main() is HostLayer::RunMain, called
// either by entry_exe.cpp's main() or by entry_jni.cpp on behalf of the app — see host_layer.h.
// the argument vector is the interface in both cases, so the app passes the same flags a shell
// would and every measurement stays comparable.

#include "elf_loader.h"
#include "guest_log.h"
#include "host_layer.h"
#include "guest_signals.h"
#include "guest_threads.h"
#include "linux_syscalls.h"
#include "thunk_abi.h"
#include "vma_tracker.h"

#include <FEXCore/Config/Config.h>
// CodeCache.h before SyscallHandler.h, and not for tidiness: SyscallHandler.h names
// ExecutableFileSectionInfo unqualified from inside namespace FEXCore::HLE, resolving it via
// the enclosing namespace to FEXCore::ExecutableFileSectionInfo — which CodeCache.h declares.
// without this include the header does not compile on its own.
#include <FEXCore/Core/CodeCache.h>
#include <FEXCore/Core/Context.h>
#include <FEXCore/Core/CoreState.h>
#include <FEXCore/Core/HostFeatures.h>
#include <FEXCore/Core/SignalDelegator.h>
#include <FEXCore/Debug/InternalThreadState.h>
#include <FEXCore/HLE/SyscallHandler.h>
#include <FEXCore/Utils/TypeDefines.h>

#include <cstdio>
#include <cstring>
#include <iterator>
#include <optional>
#include <vector>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

// same guard as elf_loader.cpp: the NDK headers this builds against predate the flag.
#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

namespace {

// --- host context, shared by both modes ------------------------------------------------------

FEXCore::SignalDelegator* GlobalSignals = nullptr;

HostLayer::GuestSignals GuestSigs;
HostLayer::LinuxSyscallHandler LinuxSyscalls;

// one MIDR_EL1 per core, out of sysfs.
//
// this is not optional and understating it is not safe, which makes it the exception to the rule
// below. FEXCore's CPUID emulation sizes its per-core table from CPUMIDRs.size() and then indexes
// it with the *current* core number — CPUIDEmu::Function_8000_0002h is `PerCPUData[GetCPUID()]`
// with no bounds check — so leaving the vector empty is a wild read the moment a guest asks for
// CPUID leaf 0x8000_0002, the processor brand string. that is exactly where CoreCLR's startup
// crashed.
//
// read from /sys rather than by pinning to each core and executing `mrs`: the kernel already
// publishes the value per cpu, and MIDR_EL1 in userspace is an emulated trap anyway. it matters
// that these are per-core and not one value copied around — the Snapdragon 8 Elite really is
// hybrid, reporting 0x514F0014 on its prime cores and 0x513F0014 on the rest, and FEXCore decides
// whether to advertise a hybrid topology to the guest by comparing them.
void FillMIDRs(FEXCore::HostFeatures& Features) {
  const long Cores = ::sysconf(_SC_NPROCESSORS_CONF);
  if (Cores <= 0) {
    return;
  }
  Features.CPUMIDRs.resize(static_cast<size_t>(Cores));

  for (long i = 0; i < Cores; ++i) {
    char Path[128];
    std::snprintf(Path, sizeof(Path), "/sys/devices/system/cpu/cpu%ld/regs/identification/midr_el1", i);
    std::FILE* File = std::fopen(Path, "re");
    if (!File) {
      continue;
    }
    unsigned long long MIDR = 0;
    if (std::fscanf(File, "%llx", &MIDR) == 1) {
      // truncated to 32 bits, as FEXCore does: the top half of MIDR_EL1 is all reserved.
      Features.CPUMIDRs[static_cast<size_t>(i)] = static_cast<uint32_t>(MIDR);
    }
    std::fclose(File);
  }
}

// otherwise deliberately conservative. FEX's own FetchHostFeatures() lives in Source/Common/,
// outside FEXCore, and probes rather more than this; wiring it up is its own task.
//
// "understating the host's instruction set costs performance and never correctness" was the rule
// here, and AVX proved it wrong. SupportsAVX is not a host capability at all: it is what decides
// whether FEXCore's decoder has a VEX table *to decode with*. the Decoder constructor picks
// VEXTableOps + SVE256 if the host has it, VEXTableOps_AVX128 — 256-bit decomposed into pairs of
// 128-bit NEON, which any arm64 can run — if it does not, and leaves both **null** otherwise. so
// with it unset every VEX-encoded instruction is undecodable and raises #UD.
//
// that is fine for a guest that checks CPUID first, and fatal for one that does not. PS5 code is
// compiled for a fixed Zen 2 target and simply uses AVX: `vmovups ymm0, [rip+0xc663d]` at
// 0x80400B75B in libc.prx, ~250 imports into Dreaming Sarah's startup, is where it landed.
//
// FEX itself sets this unconditionally on arm64 (FetchHostFeatures, in Source/Common/) for exactly
// this reason, so this is not us claiming something the device cannot do.
FEXCore::HostFeatures MinimalHostFeatures() {
  FEXCore::HostFeatures Features {};
  const unsigned long HwCaps = ::getauxval(AT_HWCAP);
  Features.SupportsAES = (HwCaps & HWCAP_AES) != 0;
  Features.SupportsCRC = (HwCaps & HWCAP_CRC32) != 0;
  Features.SupportsAtomics = (HwCaps & HWCAP_ATOMICS) != 0;
  Features.SupportsRCPC = (HwCaps & HWCAP_LRCPC) != 0;
  Features.SupportsAVX = true;
  // gated on AVX in FEX's own probe too: VAES is a VEX encoding, so it is unreachable without one.
  Features.SupportsAES256 = Features.SupportsAVX && Features.SupportsAES;
  FillMIDRs(Features);
  // told here rather than worked out there, because this is the one place that decides it. both
  // thunks read guest float arguments straight out of the spilled register file, and FEX only uses
  // the 32-byte-stride avx layout when both of these are set.
  HostLayer::ThunkABI::SetAvxRegisterFile(Features.SupportsAVX && Features.SupportsSVE256);
  return Features;
}

// what the run cost, printed once however the process ends. exit_group can come from any guest
// thread and only the initial one unwinds back to RunELF, so this is handed to the thread layer
// as well as called directly.
void PrintRunSummary() {
  if (HostLayer::Threads::CreatedCount() > 1) {
    std::printf("[host-layer] %llu guest thread(s) created, %llu still live\n",
                static_cast<unsigned long long>(HostLayer::Threads::CreatedCount()),
                static_cast<unsigned long long>(HostLayer::Threads::LiveCount()));
  }
  if (GuestSigs.DeliveredCount()) {
    std::printf("[host-layer] %llu signal(s) delivered to guest handlers\n",
                static_cast<unsigned long long>(GuestSigs.DeliveredCount()));
  }
  const auto Async = HostLayer::Threads::AsyncStats();
  if (Async.Raised) {
    // "deferred" means the host interrupt landed somewhere the thread could not be redirected
    // from. under the default site that is every one of them by construction, so the number to
    // read is Raised against how many were delivered above — a gap between the two is a thread
    // that never reached a boundary.
    std::printf("[host-layer] %llu signal(s) raised on a guest thread, %llu interrupt(s) left for a later boundary\n",
                static_cast<unsigned long long>(Async.Raised), static_cast<unsigned long long>(Async.Deferred));
  }
  if (HostLayer::VulkanThunk::PresentedFrameCount()) {
    std::printf("[host-layer] %llu frame(s) presented\n",
                static_cast<unsigned long long>(HostLayer::VulkanThunk::PresentedFrameCount()));
  }
  if (HostLayer::VulkanThunk::CallCount() || HostLayer::VulkanThunk::UnresolvedCount()) {
    std::printf("[host-layer] %llu vulkan call(s) thunked, %llu unresolved%s%s\n",
                static_cast<unsigned long long>(HostLayer::VulkanThunk::CallCount()),
                static_cast<unsigned long long>(HostLayer::VulkanThunk::UnresolvedCount()),
                HostLayer::VulkanThunk::LastUnresolved() ? ", last: " : "",
                HostLayer::VulkanThunk::LastUnresolved() ? HostLayer::VulkanThunk::LastUnresolved() : "");
  }
  if (HostLayer::AudioThunk::CallCount() || HostLayer::AudioThunk::UnresolvedCount()) {
    std::printf("[host-layer] %llu audio call(s) thunked, %llu unresolved%s%s, %llu refused as callbacks\n",
                static_cast<unsigned long long>(HostLayer::AudioThunk::CallCount()),
                static_cast<unsigned long long>(HostLayer::AudioThunk::UnresolvedCount()),
                HostLayer::AudioThunk::LastUnresolved() ? ", last: " : "",
                HostLayer::AudioThunk::LastUnresolved() ? HostLayer::AudioThunk::LastUnresolved() : "",
                static_cast<unsigned long long>(HostLayer::AudioThunk::RefusedCount()));
    // a stream that opened and never played is the one failure that looks like success, so the
    // frames-read figure goes in the summary rather than only in the periodic report.
    HostLayer::AudioThunk::ReportStreams();
  }
  if (HostLayer::Threads::CallRetResetCount()) {
    std::printf("[host-layer] %llu call-return shadow stack reset(s) after a guard-page fault\n",
                static_cast<unsigned long long>(HostLayer::Threads::CallRetResetCount()));
  }
  if (HostLayer::Threads::UnalignedFixupCount()) {
    std::printf("[host-layer] %llu unaligned access(es) backpatched\n",
                static_cast<unsigned long long>(HostLayer::Threads::UnalignedFixupCount()));
  }
  std::printf("[host-layer] smc=%s: %llu guest mapping(s) tracked, %llu code invalidation(s), %llu SMC write fault(s)\n",
              HostLayer::VMA::Mode() == HostLayer::VMA::SMCMode::None    ? "none" :
              HostLayer::VMA::Mode() == HostLayer::VMA::SMCMode::MTrack ? "mtrack" :
                                                                          "full",
              static_cast<unsigned long long>(HostLayer::VMA::EntryCount()),
              static_cast<unsigned long long>(HostLayer::VMA::InvalidationCount()),
              static_cast<unsigned long long>(HostLayer::VMA::SMCFaultCount()));
  if (const auto Stubs = HostLayer::VMA::StubReport(); Stubs.Armed) {
    // short-lived executable mappings — the JIT/FFI trampoline idiom. `stale` is the number this
    // instrument exists for: armed, unmapped, and no block ever compiled on it in between, so
    // anything the guest ran there came out of a cache rather than out of the bytes it had just
    // written.
    std::printf("[host-layer] %llu short-lived executable page(s) armed (%llu by mprotect), %llu compiled, "
                "%llu stale (%llu at a reused address, %llu armed by mprotect)\n",
                static_cast<unsigned long long>(Stubs.Armed), static_cast<unsigned long long>(Stubs.Protected),
                static_cast<unsigned long long>(Stubs.Compiled), static_cast<unsigned long long>(Stubs.Stale),
                static_cast<unsigned long long>(Stubs.StaleReused), static_cast<unsigned long long>(Stubs.StaleProtected));
  }
  if (LinuxSyscalls.UnhandledCount()) {
    std::printf("[host-layer] %llu unhandled syscall(s), last was %llu\n",
                static_cast<unsigned long long>(LinuxSyscalls.UnhandledCount()),
                static_cast<unsigned long long>(LinuxSyscalls.LastUnhandledNumber()));
  }
}

// --- mode 1: the hand-assembled spike ---------------------------------------------------------

constexpr uint64_t SpikeSyscallNumber = 0xAB;
constexpr uint64_t SpikeSyscallArg = 0xABCD;
constexpr uint64_t SpikeWitnessValue = 0x5AFE;

bool SpikeSyscallWasCalled = false;

class SpikeSyscallHandler final : public FEXCore::HLE::SyscallHandler {
public:
  SpikeSyscallHandler() {
    OSABI = FEXCore::HLE::SyscallOSABI::OS_LINUX64;
  }

  uint64_t HandleSyscall(FEXCore::Core::CpuStateFrame*, FEXCore::HLE::SyscallArguments* Args) override {
    std::printf("[host-layer]   guest syscall: number=0x%llX arg0=0x%llX\n",
                static_cast<unsigned long long>(Args->Argument[0]), static_cast<unsigned long long>(Args->Argument[1]));
    SpikeSyscallWasCalled = Args->Argument[0] == SpikeSyscallNumber && Args->Argument[1] == SpikeSyscallArg;
    return 0;
  }

  // the spike goes through the same VMA tracker as everything else rather than claiming the whole
  // address space is executable. it is the cheapest possible test that the tracker is wired up:
  // if RunSpike's Record call below is wrong, the decoder refuses the first instruction and the
  // spike regression fails immediately instead of something subtle happening much later.
  FEXCore::HLE::ExecutableRangeInfo QueryGuestExecutableRange(FEXCore::Core::InternalThreadState*, uint64_t Address) override {
    return HostLayer::VMA::Query(Address);
  }

  void MarkGuestExecutableRange(FEXCore::Core::InternalThreadState*, uint64_t Start, uint64_t Length) override {
    HostLayer::VMA::MarkExecutable(Start, Length);
  }

  void InvalidateGuestCodeRange(FEXCore::Core::InternalThreadState* Thread, uint64_t Start, uint64_t Length) override {
    HostLayer::VMA::Invalidate(Thread, Start, Length);
  }

  std::optional<FEXCore::ExecutableFileSectionInfo> LookupExecutableFileSection(FEXCore::Core::InternalThreadState*, uint64_t) override {
    return std::nullopt;
  }
};

//  off  bytes                      instruction
//    0  48 c7 c0 ab 00 00 00       mov rax, 0xAB
//    7  48 c7 c7 cd ab 00 00       mov rdi, 0xABCD
//   14  0f 05                      syscall
//   16  48 c7 c3 fe 5a 00 00       mov rbx, 0x5AFE     <- after the syscall, on purpose
//   23  31 c0                      xor eax, eax
//   25  48 8b 08                   mov rcx, [rax]      <- guest reads address 0: SIGSEGV
//   28  f4                         hlt                 (never reached)
//
// rbx is set after the syscall so reading it back proves execution resumed past the callback
// rather than stopping at it. the load from a null guest address is the fault we want to catch:
// FEX maps the 64-bit guest address space 1:1 onto the host, so it arrives as a real host
// SIGSEGV raised from inside JIT-generated code.
constexpr unsigned char SpikeGuestCode[] = {
  0x48, 0xC7, 0xC0, 0xAB, 0x00, 0x00, 0x00, //
  0x48, 0xC7, 0xC7, 0xCD, 0xAB, 0x00, 0x00, //
  0x0F, 0x05,                               //
  0x48, 0xC7, 0xC3, 0xFE, 0x5A, 0x00, 0x00, //
  0x31, 0xC0,                               //
  0x48, 0x8B, 0x08,                         //
  0xF4,                                     //
};
constexpr uint64_t SpikeFaultingInstructionOffset = 25;

int RunSpike(FEXCore::Context::Context* CTX) {
  // guest code is mapped RW, not RWX: FEX *reads* these bytes and emits arm64 elsewhere, so
  // the host never executes this page directly.
  void* CodeMem = ::mmap(nullptr, 64 * 1024, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  void* StackMem = ::mmap(nullptr, 256 * 1024, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (CodeMem == MAP_FAILED || StackMem == MAP_FAILED) {
    std::fprintf(stderr, "[host-layer] guest mmap failed\n");
    return 1;
  }
  std::memcpy(CodeMem, SpikeGuestCode, sizeof(SpikeGuestCode));

  // the guest's view: executable code, and a writable stack. the host mapping stays RW either
  // way — HostProt drops PROT_EXEC — but this is what the decoder is told, and without it the
  // spike never decodes its first instruction.
  HostLayer::VMA::Record(reinterpret_cast<uint64_t>(CodeMem), 64 * 1024, PROT_READ | PROT_EXEC);
  HostLayer::VMA::Record(reinterpret_cast<uint64_t>(StackMem), 256 * 1024, PROT_READ | PROT_WRITE);

  const auto EntryRIP = reinterpret_cast<uint64_t>(CodeMem);
  const auto StackTop = (reinterpret_cast<uint64_t>(StackMem) + 256 * 1024 - 16) & ~15ULL;
  std::printf("[host-layer] guest entry=0x%llX stack=0x%llX (%zu bytes of x86-64)\n",
              static_cast<unsigned long long>(EntryRIP), static_cast<unsigned long long>(StackTop), sizeof(SpikeGuestCode));

  auto* T = HostLayer::Threads::CreateInitial(EntryRIP, StackTop);
  if (!T) {
    std::fprintf(stderr, "[host-layer] could not create the initial guest thread\n");
    return 1;
  }

  std::printf("[host-layer] executing guest...\n");
  HostLayer::Threads::Run(*T);
  std::printf("[host-layer] %s\n",
              T->Reason == HostLayer::Escape::Fault ? "escaped guest fault" : "returned from guest without faulting");

  const uint64_t ExpectedFaultRIP = EntryRIP + SpikeFaultingInstructionOffset;
  // recovered out of a host arm64 register through the SRA mapping, not read out of memory: at
  // fault time guest GPRs are live in host registers and CPUState holds whatever was last
  // spilled there.
  const auto SRARBX = T->Fault.GPR[FEXCore::X86State::REG_RBX];

  std::printf("[host-layer] fault caught=%d in_jit_code=%d\n", T->Fault.Caught, T->Fault.InJitCode);
  std::printf("[host-layer]   guest RIP            = 0x%llX (expected 0x%llX)\n",
              static_cast<unsigned long long>(T->Fault.GuestRIP), static_cast<unsigned long long>(ExpectedFaultRIP));
  std::printf("[host-layer]   guest RBX recovered  = 0x%llX (expected 0x%llX)\n", static_cast<unsigned long long>(SRARBX),
              static_cast<unsigned long long>(SpikeWitnessValue));

  const bool Ok = SpikeSyscallWasCalled && T->Fault.Caught && T->Fault.InJitCode && T->Fault.FaultAddress == nullptr &&
                  T->Fault.GuestRIP == ExpectedFaultRIP && SRARBX == SpikeWitnessValue;
  std::printf("[host-layer] %s\n", Ok ? "spike OK: guest executed, faulted, and guest state was recovered."
                                      : "spike FAILED: see above.");

  HostLayer::Threads::Destroy(*T);
  return Ok ? 0 : 1;
}

// --- mode 2: load and run an x86-64 ELF --------------------------------------------------------

// where an ET_DYN (PIE) guest gets biased to, relative to the base chosen below. ET_EXEC guests
// are mapped where their program headers say, which for a linked-at-fixed-address binary is
// normally 0x400000.
//
// the host layer's address budget gives 0..32 GiB to the host, FEXCore and .NET, so a guest
// down here is out of the way of the 32-36 GiB window the PS5 image will want later.
constexpr uint64_t GuestPIEOffset = 0x2000'0000;

// and where its interpreter goes. this has to clear both the program image and the 512 MiB brk
// arena reserved immediately past it, because the arena is claimed *after* the interpreter is
// mapped and cannot move: brk has to stay contiguous with the image. 2 GiB leaves room for a
// program far larger than the 61 MB SharpEmu publish.
constexpr uint64_t GuestInterpOffset = 0x8000'0000;

// the end of what the pair of them plus the interpreter's own budget can touch, and therefore
// what has to be free for a base to be usable.
constexpr uint64_t GuestSpanEnd = 0xA000'0000;

// **an app process is not an empty process, and moving into an APK is where that was found out.**
// until then the host layer only ever ran as a shell binary, where the bottom 2.5 GiB is untouched
// and these offsets could be absolute addresses. inside an APK, ART got there first: the dalvik
// main heap is a 256 MiB region at 0x14000000, the non-moving heap at 0x34000000, two JIT code
// caches at 0x54000000, and the boot image and its .oat files run from about 0x70cc0000 upwards.
// the guest program at 512 MiB lands inside the first of those and the interpreter at 2 GiB
// inside the last, so the loader's MAP_FIXED_NOREPLACE reservation returned EEXIST and the run
// ended before the guest existed.
//
// so the base is measured rather than declared, which is the same answer the fork reached for
// SharpEmu's own layout one level up. **zero is tried first and it is not a formality**: on a
// shell process it always wins, so every address in every log taken before the app existed is
// reproduced exactly and no earlier measurement stops being comparable. the rest are 4 GiB apart and all
// stop short of the 32 GiB the PS5 image wants.
constexpr uint64_t GuestBaseCandidates[] {
  0, 0x2'0000'0000, 0x3'0000'0000, 0x4'0000'0000, 0x5'0000'0000, 0x6'0000'0000, 0x7'0000'0000,
};

// probe by reserving and releasing, rather than by parsing /proc/self/maps. the kernel is the
// only authority that cannot disagree with itself, and MAP_FIXED_NOREPLACE asks it the exact
// question the loader is about to ask for real.
uint64_t ChooseGuestBase() {
  for (const uint64_t Base : GuestBaseCandidates) {
    const uint64_t Start = Base + GuestPIEOffset;
    const uint64_t Size = GuestSpanEnd - GuestPIEOffset;
    void* Probe = ::mmap(reinterpret_cast<void*>(Start), Size, PROT_NONE,
                         MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE | MAP_NORESERVE, -1, 0);
    if (Probe == MAP_FAILED) {
      continue;
    }
    const bool Usable = reinterpret_cast<uint64_t>(Probe) == Start;
    ::munmap(Probe, Size);
    if (Usable) {
      if (Base) {
        std::printf("[host-layer] guest base 0x%llX: something already occupies the usual one\n",
                    static_cast<unsigned long long>(Base));
      }
      return Base;
    }
  }
  // nothing to do but try the usual place and let the loader report what it finds.
  std::fprintf(stderr, "[host-layer] no free guest base below 32 GiB; falling back to 0\n");
  return 0;
}

constexpr size_t GuestStackSize = 8 * 1024 * 1024;

int RunELF(FEXCore::Context::Context* CTX, const char* Path, const char* LibDir, const char* TmpDir,
           const std::vector<const char*>& ExtraEnv, int GuestArgc, const char* const* GuestArgv) {
  const uint64_t GuestBase = ChooseGuestBase();
  auto Program = HostLayer::LoadProgram(Path, GuestBase + GuestPIEOffset, GuestBase + GuestInterpOffset, LibDir);
  if (!Program.Ok) {
    std::fprintf(stderr, "[host-layer] load failed: %s (%s)\n", Program.Error, Path);
    return 1;
  }
  const auto& Elf = Program.Exec;
  // the payload's size alongside its path, so a measurement is attributable to a specific build
  // rather than to whatever happened to be lying at that path. selectable builds gave a run a
  // build *name*; this
  // is the cheapest form of the same guarantee, and it is the one the shell binary gets too. the
  // `mrpurple-t29` trap in miniature: a plausible number attributed to the wrong artefact.
  struct stat PayloadStat {};
  if (::stat(Path, &PayloadStat) == 0) {
    std::printf("[host-layer] loaded %s (%lld bytes)\n", Path, static_cast<long long>(PayloadStat.st_size));
  } else {
    std::printf("[host-layer] loaded %s\n", Path);
  }
  std::printf("[host-layer]   image 0x%llX..0x%llX, entry 0x%llX, bias 0x%llX\n",
              static_cast<unsigned long long>(Elf.MappingBegin), static_cast<unsigned long long>(Elf.MappingEnd),
              static_cast<unsigned long long>(Elf.Entry), static_cast<unsigned long long>(Elf.LoadBias));
  std::printf("[host-layer]   phdr 0x%llX x%llu, brk base 0x%llX\n", static_cast<unsigned long long>(Elf.PhdrAddr),
              static_cast<unsigned long long>(Elf.PhNum), static_cast<unsigned long long>(Elf.BrkBase));
  if (Program.Interp.Ok) {
    std::printf("[host-layer]   interp %s\n", Elf.InterpPath);
    std::printf("[host-layer]   interp 0x%llX..0x%llX, entry 0x%llX (AT_BASE 0x%llX)\n",
                static_cast<unsigned long long>(Program.Interp.MappingBegin),
                static_cast<unsigned long long>(Program.Interp.MappingEnd),
                static_cast<unsigned long long>(Program.Interp.Entry),
                static_cast<unsigned long long>(Program.InterpBase));
  } else {
    std::printf("[host-layer]   statically linked, no interpreter\n");
  }

  // the guest's own library search path. the same directory PT_INTERP was resolved out of, on the
  // grounds that if ld.so came from there so did everything it is about to load.
  char LibPathVar[512] {};
  std::snprintf(LibPathVar, sizeof(LibPathVar), "LD_LIBRARY_PATH=%s", LibDir ? LibDir : "");

  // android has no /tmp, and .NET reaches for a writable directory for far more than bundles. all
  // three names point at the same place: HOME because the single-file host falls back to
  // $HOME/.net, TMPDIR because everything else in the runtime looks there, and the explicit
  // DOTNET_ variable because relying on a fallback to land somewhere writable is how the previous
  // run failed with "Default extraction directory [/]".
  char HomeVar[512] {};
  char TmpVar[512] {};
  char BundleVar[512] {};
  std::snprintf(HomeVar, sizeof(HomeVar), "HOME=%s", TmpDir ? TmpDir : "/");
  std::snprintf(TmpVar, sizeof(TmpVar), "TMPDIR=%s", TmpDir ? TmpDir : "/");
  std::snprintf(BundleVar, sizeof(BundleVar), "DOTNET_BUNDLE_EXTRACT_BASE_DIR=%s", TmpDir ? TmpDir : "/");

  const char* const GuestEnv[] {
    "PATH=/usr/bin:/bin",
    "LANG=C",
    LibPathVar,
    HomeVar,
    TmpVar,
    BundleVar,
    // .NET links globalization against ICU at runtime and FailFast()s if it cannot find it —
    // "Couldn't find a valid ICU package installed on the system", from a static constructor deep
    // under the first DateTimeOffset.ToLocalTime() SharpEmu's logger performs. that is not a host
    // layer problem: libicu is simply not among the 23 x86-64 shared objects staged in
    // guest-libs/, and it is ~30 MB of them.
    //
    // invariant mode is the right answer for the proof of concept and probably for the app too:
    // it costs culture-aware formatting and collation, which a PS5 emulator uses for log
    // timestamps, and nothing that affects running a game. if the fork ever needs real
    // globalization the fix is to stage libicuuc/libicui18n/libicudata, not to change anything
    // here.
    "DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1",
    // deliberately NOT setting DOTNET_GCRegionRange. the early throwaway probe under wine needed
    // it — the regions GC reserved a range covering 0x8_0000_0000 and the PS5 image landed on top
    // of it — but that was wine's address space, not ours. measured here: setting it to 0x100000000
    // changes nothing either way, and without it the guest maps the full PS5 image at 32 GiB and
    // applies all 120,776 relocations. so it is a wine artefact, not a latent fix to keep around.
  };

  // the fixed set above, then whatever --env added. a flag rather than more entries in that array
  // because the interesting .NET knobs — W^X, tiered compilation, AVX — are one-line experiments
  // whose whole value is being cheap to try, and a rebuild per experiment is not cheap.
  std::vector<const char*> Env {std::begin(GuestEnv), std::end(GuestEnv)};
  for (const char* Entry : ExtraEnv) {
    std::printf("[host-layer]   guest env: %s\n", Entry);
    Env.push_back(Entry);
  }
  Env.push_back(nullptr);

  auto Stack = HostLayer::BuildGuestStack(Program, Path, GuestArgc, GuestArgv, Env.data(), GuestStackSize);
  if (!Stack.Ok) {
    std::fprintf(stderr, "[host-layer] stack setup failed: %s\n", Stack.Error);
    return 1;
  }
  std::printf("[host-layer]   stack 0x%llX..0x%llX, RSP 0x%llX\n", static_cast<unsigned long long>(Stack.Base),
              static_cast<unsigned long long>(Stack.Base + Stack.Size), static_cast<unsigned long long>(Stack.RSP));
  {
    // what the guest is about to find at RSP, from the host's side. worth printing every run:
    // if the guest and the host ever disagree about these eight words, the fault is between
    // them — in the JIT or in guest state setup — and not in either one's arithmetic.
    const auto* Slots = reinterpret_cast<const uint64_t*>(Stack.RSP);
    for (int i = 0; i < 8; ++i) {
      std::printf("[host-layer]   [rsp+%02d] = 0x%016llX\n", i * 8, static_cast<unsigned long long>(Slots[i]));
    }
  }

  LinuxSyscalls.SetBrkBase(Elf.BrkBase);
  LinuxSyscalls.SetSignals(&GuestSigs);
  // /proc/self has to describe the guest, not the arm64 executable hosting it.
  LinuxSyscalls.ProcFS().SetExe(Path);
  LinuxSyscalls.ProcFS().SetCmdline(GuestArgc, GuestArgv);

  auto* T = HostLayer::Threads::CreateInitial(Program.StartRIP, Stack.RSP);
  if (!T) {
    std::fprintf(stderr, "[host-layer] could not create the initial guest thread\n");
    return 1;
  }

  std::printf("[host-layer] --- guest starts ---\n");
  HostLayer::Threads::Run(*T);
  std::printf("[host-layer] --- guest stops ---\n");

  int Result = 1;
  switch (T->Reason) {
  case HostLayer::Escape::Exited:
    // the initial guest thread called exit(2) rather than exit_group(2). on linux the process
    // outlives it — every other thread keeps running — so we wait rather than tearing the address
    // space down under threads that are still using it.
    HostLayer::Threads::WaitForOthers();
    [[fallthrough]];
  case HostLayer::Escape::ExitedGroup: {
    int Status = T->ExitStatus;
    // if some other thread got to exit_group first, its status is the process's status. that is
    // what linux reports and, more practically, it is the one that says why.
    HostLayer::Threads::ProcessExitRequested(&Status);
    // the kernel keeps only the low 8 bits of an exit status, so a guest exiting with a value
    // that has meaning above them — .NET's apphost exits with 0x80008031-shaped HRESULTs — must
    // be reported the way linux would report it, or the number on screen is one no real system
    // would ever show. the raw value is worth printing too, since for those callers it is the
    // actual error code.
    if (static_cast<unsigned>(Status) > 0xFF) {
      std::printf("[host-layer] guest exited with status %d (raw 0x%08X)\n", Status & 0xFF, static_cast<unsigned>(Status));
    } else {
      std::printf("[host-layer] guest exited with status %d\n", Status);
    }
    Result = Status & 0xFF;
    break;
  }
  case HostLayer::Escape::Fault:
    HostLayer::Threads::PrintFaultReport(*T);
    std::printf("[host-layer] guest died on an unhandled fault (no signal delivery yet)\n");
    break;
  case HostLayer::Escape::Returned:
  case HostLayer::Escape::Restart:
    // with EnableExitOnHLT set, a hlt unwinds ExecuteThread cleanly. a linux binary has no
    // business executing one, so this means the guest ran off the end of something.
    std::printf("[host-layer] guest stopped without exiting (stray hlt?) at RIP 0x%llX\n",
                static_cast<unsigned long long>(T->Thread->CurrentFrame->State.rip));
    break;
  }

  PrintRunSummary();

  // exit_group means every thread dies now, and that is also the only safe thing to do: the other
  // guest threads are still inside FEXCore, so unwinding out of here to tear down this thread's
  // state and shut the config down would be racing them. the kernel does not politely join threads
  // on exit_group either.
  if (T->Reason == HostLayer::Escape::ExitedGroup || HostLayer::Threads::LiveCount() > 1) {
    std::fflush(stdout);
    ::_exit(Result);
  }

  HostLayer::Threads::Destroy(*T);
  return Result;
}

} // namespace

int HostLayer::RunMain(int argc, char** argv) {
  // t=0, before anything else does any work: every stamp in the log is measured from here, and the
  // startup this line precedes is exactly the part we most want to see the size of.
  HostLayer::GuestLog::Start();

  // unbuffered: through `adb shell` stdout is a pipe and therefore fully buffered, so anything
  // printed before a crash is lost. that cost a debugging round already. it also stops two guest
  // threads' trace lines from arriving as interleaved half-lines.
  ::setvbuf(stdout, nullptr, _IONBF, 0);

  bool SpikeMode = false;
  bool TurboRequested = false;
  bool Trace = false;
  auto SMC = HostLayer::VMA::SMCMode::MTrack;
  const char* LibDir = nullptr;
  const char* TmpDir = nullptr;
  std::vector<const char*> ExtraEnv;
  int ArgIndex = 1;
  for (; ArgIndex < argc; ++ArgIndex) {
    if (std::strcmp(argv[ArgIndex], "--spike") == 0) {
      SpikeMode = true;
    } else if (std::strcmp(argv[ArgIndex], "--trace") == 0) {
      Trace = true;
    } else if (std::strcmp(argv[ArgIndex], "--trace-signals") == 0) {
      // separate from --trace, and not implied by it: the asynchronous signal path is a dozen
      // events in a whole run, where --trace is millions of lines. keeping them apart is what
      // makes it usable on the real workload.
      HostLayer::Threads::SetSignalTrace(true);
    } else if (std::strcmp(argv[ArgIndex], "--asyncsig") == 0 && ArgIndex + 1 < argc) {
      const char* Site = argv[++ArgIndex];
      if (std::strcmp(Site, "safepoint") == 0) {
        HostLayer::Threads::SetAsyncSite(HostLayer::Threads::AsyncSite::SafePoint);
      } else if (std::strcmp(Site, "block") == 0) {
        HostLayer::Threads::SetAsyncSite(HostLayer::Threads::AsyncSite::Block);
      } else if (std::strcmp(Site, "syscall") == 0) {
        HostLayer::Threads::SetAsyncSite(HostLayer::Threads::AsyncSite::SyscallOnly);
      } else {
        std::fprintf(stderr, "[host-layer] unknown --asyncsig site '%s' (safepoint|block|syscall)\n", Site);
        return 2;
      }
    } else if (std::strcmp(argv[ArgIndex], "--timestamps") == 0) {
      // off by default, so an unmeasured run produces exactly the log every milestone recorded.
      HostLayer::GuestLog::Enable();
    } else if (std::strcmp(argv[ArgIndex], "--smc") == 0 && ArgIndex + 1 < argc) {
      // a flag rather than a constant because the three modes are the natural way to bisect an
      // SMC problem: `full` is correct without any tracking at all and is the fallback if the
      // page-protection machinery misbehaves, `none` says whether tracking is what costs.
      const char* Mode = argv[++ArgIndex];
      if (std::strcmp(Mode, "none") == 0) {
        SMC = HostLayer::VMA::SMCMode::None;
      } else if (std::strcmp(Mode, "full") == 0) {
        SMC = HostLayer::VMA::SMCMode::Full;
      } else if (std::strcmp(Mode, "mtrack") == 0) {
        SMC = HostLayer::VMA::SMCMode::MTrack;
      } else {
        std::fprintf(stderr, "[host-layer] unknown --smc mode '%s' (none|mtrack|full)\n", Mode);
        return 2;
      }
    } else if (std::strcmp(argv[ArgIndex], "--vulkan") == 0) {
      // off by default so that every measurement taken before the thunk existed still reproduces
      // exactly: without it the
      // guest's dlopen of libvulkan.so.1 fails the way it always did, and nothing else changes.
      HostLayer::VulkanThunk::SetEnabled(true);
    } else if (std::strcmp(argv[ArgIndex], "--vulkan-lib") == 0 && ArgIndex + 1 < argc) {
      HostLayer::VulkanThunk::SetLibraryPath(argv[++ArgIndex]);
      HostLayer::VulkanThunk::SetEnabled(true);
    } else if (std::strcmp(argv[ArgIndex], "--vulkan-driver") == 0 && ArgIndex + 1 < argc) {
      // a custom driver for the platform loader to load, injected with libadrenotools. this is
      // **not** --vulkan-lib: that names a different loader, and a turnip .so is not a loader --
      // on android WSI lives in the loader, so dlopening the driver directly would mean no
      // swapchain at all. this leaves the loader alone and changes what it finds underneath.
      //
      // it needs --vulkan-hooks as well, and an app process to be in. neither given, and the
      // library open below is exactly the one every measurement so far was taken against.
      HostLayer::VulkanThunk::SetDriver(argv[++ArgIndex]);
      HostLayer::VulkanThunk::SetEnabled(true);
    } else if (std::strcmp(argv[ArgIndex], "--vulkan-hooks") == 0 && ArgIndex + 1 < argc) {
      HostLayer::VulkanThunk::SetHookLibDir(argv[++ArgIndex]);
    } else if (std::strcmp(argv[ArgIndex], "--vulkan-turbo") == 0) {
      // pins the GPU clocks through KGSL for the life of the run. off by default: it is a thermal
      // and battery trade rather than a free win, and every number recorded before it was taken
      // without it. works on any driver, because it is a kernel call rather than a mesa one.
      TurboRequested = true;
    } else if (std::strcmp(argv[ArgIndex], "--vulkan-driver-env") == 0 && ArgIndex + 1 < argc) {
      // the driver's environment, not the guest's. mesa's knobs -- TU_DEBUG and friends -- are
      // read by host arm64 code, so --env can never reach them.
      HostLayer::VulkanThunk::AddDriverEnv(argv[++ArgIndex]);
    } else if (std::strcmp(argv[ArgIndex], "--vulkan-size") == 0 && ArgIndex + 1 < argc) {
      // the presentation size the guest is told the display is. it must match whatever the
      // client thinks its drawable is, or the client recreates its swapchain forever without
      // ever erroring, which is how this was found. it applies only when there is no window:
      // with one, the size comes from the ANativeWindow and this flag is refused. under android
      // WSI the driver answers the question and none of it is consulted.
      unsigned Width = 0, Height = 0;
      if (std::sscanf(argv[++ArgIndex], "%ux%u", &Width, &Height) != 2 || !Width || !Height) {
        std::fprintf(stderr, "[host-layer] --vulkan-size wants WxH, e.g. 1920x1080\n");
        return 2;
      }
      HostLayer::VulkanThunk::SetSurfaceSize(Width, Height);
    } else if (std::strcmp(argv[ArgIndex], "--vulkan-wsi") == 0 && ArgIndex + 1 < argc) {
      // which window system the guest gets. the default decides itself from whether there is a
      // window, which is the honest answer in both configurations we ship; the two explicit values
      // exist so that a graphics regression can be bisected against the invented swapchain rather
      // than guessed at, in the shape --smc and --asyncsig already established.
      const char* Mode = argv[++ArgIndex];
      if (std::strcmp(Mode, "headless") == 0) {
        HostLayer::VulkanThunk::SetWsiMode(HostLayer::VulkanThunk::WsiMode::Headless);
      } else if (std::strcmp(Mode, "android") == 0) {
        HostLayer::VulkanThunk::SetWsiMode(HostLayer::VulkanThunk::WsiMode::Android);
      } else if (std::strcmp(Mode, "auto") == 0) {
        HostLayer::VulkanThunk::SetWsiMode(HostLayer::VulkanThunk::WsiMode::Auto);
      } else {
        std::fprintf(stderr, "[host-layer] unknown --vulkan-wsi mode '%s' (auto|headless|android)\n", Mode);
        return 2;
      }
    } else if (std::strcmp(argv[ArgIndex], "--vulkan-dump") == 0 && ArgIndex + 1 < argc) {
      HostLayer::VulkanThunk::SetDumpPrefix(argv[++ArgIndex]);
    } else if (std::strcmp(argv[ArgIndex], "--trace-vulkan") == 0) {
      HostLayer::VulkanThunk::SetTrace(true);
    } else if (std::strcmp(argv[ArgIndex], "--vulkan-profile") == 0) {
      // where the time goes, per command, dumped every 300 presented frames. --trace-vulkan says
      // which commands are called and cannot find a stall; this sorts by time and usually answers
      // it on the first line.
      HostLayer::VulkanThunk::SetProfile(true);
    } else if (std::strcmp(argv[ArgIndex], "--audio") == 0) {
      // off by default so that every measurement taken before audio existed still reproduces
      // exactly: without it the guest's AAudio calls fail the way they always did and the fork's
      // backend degrades to silent, which is what every earlier number was taken against.
      HostLayer::AudioThunk::SetEnabled(true);
    } else if (std::strcmp(argv[ArgIndex], "--audio-lib") == 0 && ArgIndex + 1 < argc) {
      HostLayer::AudioThunk::SetLibraryPath(argv[++ArgIndex]);
      HostLayer::AudioThunk::SetEnabled(true);
    } else if (std::strcmp(argv[ArgIndex], "--trace-audio") == 0) {
      HostLayer::AudioThunk::SetTrace(true);
    } else if (std::strcmp(argv[ArgIndex], "--audio-watchdog") == 0) {
      HostLayer::AudioThunk::SetWatchdog(true);
    } else if (std::strcmp(argv[ArgIndex], "--libs") == 0 && ArgIndex + 1 < argc) {
      // one flag, two jobs: where PT_INTERP is resolved from, and what the guest is handed as
      // LD_LIBRARY_PATH. they are the same directory in every case that matters, and splitting
      // them would only create a way to get them out of step.
      LibDir = argv[++ArgIndex];
    } else if (std::strcmp(argv[ArgIndex], "--tmp") == 0 && ArgIndex + 1 < argc) {
      TmpDir = argv[++ArgIndex];
    } else if (std::strcmp(argv[ArgIndex], "--env") == 0 && ArgIndex + 1 < argc) {
      // NAME=VALUE, appended to the guest environment. repeatable.
      ExtraEnv.push_back(argv[++ArgIndex]);
    } else {
      break;
    }
  }

  if (!SpikeMode && ArgIndex >= argc) {
    std::fprintf(stderr, "usage: sharpemu-host-layer [--smc none|mtrack|full] --spike\n"
                         "       sharpemu-host-layer [--trace] [--trace-signals] [--timestamps] [--smc none|mtrack|full] "
                         "[--asyncsig syscall|safepoint|block] [--vulkan] [--vulkan-lib <so>] "
                         "[--vulkan-driver <so>] [--vulkan-hooks <dir>] [--vulkan-driver-env NAME=VALUE]... [--vulkan-turbo] "
                         "[--vulkan-wsi auto|headless|android] [--trace-vulkan] [--vulkan-profile] "
                         "[--audio] [--audio-lib <so>] [--trace-audio] [--libs <dir>] "
                         "[--tmp <dir>] [--env NAME=VALUE]... <x86-64-elf> [guest args...]\n");
    return 2;
  }

  // applied after parsing rather than inside it, so that the *absence* of the flag is also an
  // action: SetTurbo(false) clears a clock pin leaked by a previous run that was killed rather
  // than exited, which is how every measurement in this project ends. see vulkan_thunk.cpp.
  HostLayer::VulkanThunk::SetTurbo(TurboRequested);

  std::printf("[host-layer] starting\n");
  if (HostLayer::GuestLog::Enabled()) {
    // said once, because the stamps only appear on the guest's own output and their absence from
    // the host layer's lines should read as deliberate rather than broken.
    std::printf("[host-layer] --timestamps: guest stdout/stderr lines carry [+seconds.millis] since process start\n");
  }
  FEXCore::Config::Initialize();

  // FEXCore defaults to 32-bit mode, and nothing complains if you leave it there: the decoder
  // takes its bitness from the CS descriptor, so 64-bit instructions still decode correctly.
  // what changes is the *register file* — the Arm64Emitter constructor picks x32::SRA over x64::SRA,
  // which is 8 guest GPRs instead of 16 mapped to host registers. guest code touching R8-R15,
  // or holding a 64-bit value anywhere, then quietly gets 32-bit results.
  //
  // this has to be set before InitCore(), which is where the dispatcher and its register
  // allocation are built.
  FEXCore::Config::Set(FEXCore::Config::CONFIG_IS64BIT_MODE, "1");

  // has to agree with what the VMA tracker is told below, and has to be set before InitCore()
  // for the same reason as the line above: it changes how blocks are compiled. under `full`
  // FEXCore emits a byte-comparison guard into every block; under `mtrack` it instead promises to
  // call MarkGuestExecutableRange and expects the host layer to arrange the rest.
  //
  // the numbers are FEXCore::Config::ConfigSMC_{NONE,MTRACK,FULL}; Config::Set takes strings, and
  // the "none"/"mtrack"/"full" spellings are handled by FEX's own argument parser, which we do
  // not use.
  FEXCore::Config::Set(FEXCore::Config::CONFIG_SMCCHECKS, SMC == HostLayer::VMA::SMCMode::None    ? "0" :
                                                          SMC == HostLayer::VMA::SMCMode::MTrack ? "1" :
                                                                                                   "2");

  // the interrupt fault page, which is how an asynchronous signal reaches a thread that is off
  // running already-compiled guest code — see guest_threads.h's AsyncSite.
  //
  // GDBSERVER is a strange-looking way to ask for it, and it is deliberate: inside FEXCore this
  // option does exactly one thing in ContextImpl::InitCore,
  // `Config.NeedsPendingInterruptFaultCheck = true`,
  // which is the switch that makes the JIT emit the check. everything else called gdbserver lives
  // in FEX's frontend, which we do not build. it is the only public way to reach the switch, and
  // FEX is not ours to add another one to.
  if (HostLayer::Threads::AsyncNeedsInterruptCheck()) {
    FEXCore::Config::Set(FEXCore::Config::CONFIG_GDBSERVER, "1");
  }

  const auto Features = MinimalHostFeatures();
  std::printf("[host-layer] host features: AES=%d CRC=%d Atomics=%d RCPC=%d, %zu core(s)\n", Features.SupportsAES,
              Features.SupportsCRC, Features.SupportsAtomics, Features.SupportsRCPC, Features.CPUMIDRs.size());

  auto CTX = FEXCore::Context::Context::CreateNewContext(Features);
  if (!CTX) {
    std::fprintf(stderr, "[host-layer] CreateNewContext returned null\n");
    return 1;
  }

  // before the syscall handler is installed, because the very first thing FEXCore will ask it is
  // QueryGuestExecutableRange, and that is answered out of the tracker.
  HostLayer::VMA::Initialize(CTX.get(), SMC);

  SpikeSyscallHandler SpikeSyscalls;
  LinuxSyscalls.SetTrace(Trace);
  CTX->SetSyscallHandler(SpikeMode ? static_cast<FEXCore::HLE::SyscallHandler*>(&SpikeSyscalls) : &LinuxSyscalls);

  // InitCore() unconditionally dereferences the signal delegator — it calls
  // SignalDelegation->SetConfig(...) with no null check — so one must be installed first or it
  // segfaults at +0x38. a plain instance is enough to get through init; it delivers nothing,
  // which is what makes an unhandled guest fault fatal for now.
  FEXCore::SignalDelegator Signals;
  CTX->SetSignalDelegator(&Signals);
  GlobalSignals = &Signals;

  // before InitCore(): this changes how blocks are compiled, so it has to be set while the
  // dispatcher is being built rather than after any code has been generated.
  CTX->EnableExitOnHLT();

  if (!CTX->InitCore()) {
    std::fprintf(stderr, "[host-layer] InitCore failed\n");
    return 1;
  }
  std::printf("[host-layer] FEXCore initialised\n");

  // after InitCore, because the dispatcher bounds and the static register allocation the fault
  // handler reads out of the config are only populated once the dispatcher has been built.
  GuestSigs.Attach(CTX.get(), &GlobalSignals->GetConfig());
  HostLayer::Threads::Initialize(CTX.get(), &GuestSigs, &GlobalSignals->GetConfig());
  HostLayer::Threads::SetSummaryCallback(PrintRunSummary);
  HostLayer::Threads::InstallProcessFaultHandlers();

  const int Result =
    SpikeMode ? RunSpike(CTX.get()) : RunELF(CTX.get(), argv[ArgIndex], LibDir, TmpDir, ExtraEnv, argc - ArgIndex, argv + ArgIndex);

  FEXCore::Config::Shutdown();
  return Result;
}
