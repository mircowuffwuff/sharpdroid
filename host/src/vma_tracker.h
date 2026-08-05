// sharpemu-android host layer — guest VMA tracking, and self-modifying code.
//
// FEXCore asks the host layer two questions about guest memory and expects to be told when the
// answers change:
//
//   "is there executable guest code at this address, and how far does it run?"
//        Decoder::CheckRangeExecutable, via SyscallHandler::QueryGuestExecutableRange. a Size of 0
//        is how the host layer says "not executable", and it is what makes the decoder refuse to
//        translate a data page a guest jumped into by accident.
//
//   "this page now holds code i have compiled."
//        SyscallHandler::MarkGuestExecutableRange, once per page. under SMCChecks=mtrack this is
//        an invitation to arrange for a fault the next time the guest writes there — which is what
//        MarkExecutable below does, by taking PROT_WRITE off the host mapping.
//
// and the other direction: whenever guest memory stops holding what FEXCore compiled — unmapped,
// re-protected, moved, discarded, or simply written to — the host layer must call back into
// Context::InvalidateCodeBuffersCodeRange / InvalidateThreadCachedCodeRange before the guest can
// reach the stale translation.
//
// **we cannot ask the kernel any of this, and that is the whole reason this file exists.**
// TranslateProt drops PROT_EXEC before any guest mapping reaches bionic, because an android
// app is denied `execute` on its own app_data_file. so the host kernel does not know which guest
// pages the guest believes are executable, and /proc/self/maps will never say. the guest's
// *requested* protection exists only here, recorded at the moment the syscall arrives.
//
// a mapping that is not recorded here is not executable as far as FEXCore is concerned, so every
// producer of guest memory has to report in — not just mmap, but the ELF loader, the guest stack,
// the sigreturn trampoline and the spike's hand-assembled page.

#pragma once

#include <FEXCore/HLE/SyscallHandler.h>

#include <cstdint>

namespace FEXCore::Context {
class Context;
}
namespace FEXCore::Core {
struct InternalThreadState;
}

namespace HostLayer::VMA {

// mirrors FEXCore::Config::ConfigSMCChecks, restated so callers do not have to include the
// FEXCore config machinery to name a mode.
enum class SMCMode {
  None,   ///< no self-modifying-code detection at all. fast, and wrong for anything with a JIT.
  MTrack, ///< write-protect compiled pages and invalidate from the fault. FEXCore's default.
  Full,   ///< FEXCore emits a byte-comparison guard into every block. correct, and slower.
};

void Initialize(FEXCore::Context::Context* CTX, SMCMode Mode);
SMCMode Mode();

/**
 * @brief The guest's requested protection, as bionic is allowed to see it.
 *
 * PROT_EXEC never reaches the host kernel. FEX does not execute guest memory — it reads those
 * bytes and emits arm64 into its own code buffers — so nothing is lost by mapping guest text
 * read-only, and something is gained: an android app is denied `execute` on its own
 * app_data_file, so a guest ld.so mapping a library's text segment PROT_EXEC would be refused
 * outright once this stops running out of /data/local/tmp. dropping the bit here is what lets a
 * conventional dynamic linker work in a place that forbids executable file mappings.
 *
 * it lives here rather than in the syscall layer because the tracker has to reproduce it exactly
 * when it seals and unseals a page, and two copies of this rule would eventually disagree.
 */
int HostProt(int GuestProt);

// --- recording what the guest has ------------------------------------------------------------
//
// Prot throughout is what the *guest* asked for, PROT_EXEC included — not what was passed to
// bionic. ranges are page-aligned by the tracker, so callers may pass byte lengths.

///< a new mapping. replaces whatever was recorded over the same range, as MAP_FIXED does.
void Record(uint64_t Base, uint64_t Length, int GuestProt);

///< munmap. the range simply stops existing.
void Forget(uint64_t Base, uint64_t Length);

///< mprotect. splits entries as needed; the range keeps its identity, only Prot changes.
void Reprotect(uint64_t Base, uint64_t Length, int GuestProt);

///< mremap. the old range is forgotten and the new one recorded with the old protection.
void Remap(uint64_t OldBase, uint64_t OldLength, uint64_t NewBase, uint64_t NewLength);

// --- what FEXCore asks -------------------------------------------------------------------------

FEXCore::HLE::ExecutableRangeInfo Query(uint64_t Address);

///< SyscallHandler::MarkGuestExecutableRange. under mtrack, seals the page against guest writes.
void MarkExecutable(uint64_t Base, uint64_t Length);

/**
 * @brief Drop every translation FEXCore holds for a guest range.
 *
 * takes the code invalidation mutex and walks every live guest thread, because a block compiled
 * by one thread sits in that thread's lookup cache as well as in the shared code buffers.
 *
 * @param Thread the calling thread, or nullptr from a context that has none.
 */
void Invalidate(FEXCore::Core::InternalThreadState* Thread, uint64_t Base, uint64_t Length);

enum class WriteFault {
  NotOurs,    ///< a real fault. carry on treating it as one.
  Resume,     ///< handled; return from the signal handler and re-run the faulting instruction.
  SingleStep, ///< handled, but the write landed inside the block that is currently executing.
};

/**
 * @brief A write fault on a page we sealed for SMC tracking, or somebody else's problem.
 *
 * called from the host SIGSEGV handler before anything else looks at the fault. if the address
 * lies in a mapping the guest asked to be writable, the only thing that can have made it fault is
 * MarkExecutable — so drop the translations for that page, hand the write permission back, and let
 * the faulting instruction re-run.
 *
 * SingleStep is the case where the guest is rewriting code inside the very block it is executing.
 * re-running the faulting instruction there would carry straight on into the translation that was
 * just dropped, so the caller has to re-enter the dispatcher and ask for a one-instruction block
 * instead. that is host-context surgery and belongs to the fault handler, not here.
 *
 * @param HostPC where the *host* faulted, which is what says whether we are inside a JIT block.
 */
WriteFault HandleWriteFault(FEXCore::Core::InternalThreadState* Thread, uint64_t FaultAddress, uint64_t HostPC);

// --- reporting ---------------------------------------------------------------------------------

uint64_t EntryCount();
uint64_t SMCFaultCount();
uint64_t InvalidationCount();

/**
 * @brief Short-lived executable mappings, and whether FEXCore translated what was in them.
 *
 * a guest that maps a page, writes a trampoline into it, protects it executable, runs it and unmaps
 * it — SharpEmu's blocked-thread resume, ~400 times a second — has been measured running the
 * *previous* occupant of a recycled address. FEXCore calls MarkGuestExecutableRange for a page
 * exactly once per invalidation epoch, so a page that was armed executable and then unmapped
 * without one is a page no block was compiled on since we invalidated it: whatever ran there was
 * cached.
 *
 * `Stale` counts those, and on its own it is not evidence. a page that is made executable and then
 * freed without ever being *run* lands in the same column, which is legitimate and which SharpEmu's
 * loader does a few hundred times during start-up in every run. so **`StaleProtected` is the column
 * to read** — armed by an `mprotect` to executable, which is a guest declaring that code it has just
 * written is ready to run, and which is the last thing the blocked-thread resume does before
 * entering the trampoline — and even that is only meaningful as a delta past start-up.
 */
struct StubStats {
  uint64_t Armed;
  uint64_t Protected; ///< of Armed, the ones armed by mprotect rather than by mmap
  uint64_t Compiled;
  uint64_t Stale;
  uint64_t StaleReused;
  uint64_t StaleProtected;
};
StubStats StubReport();

} // namespace HostLayer::VMA
