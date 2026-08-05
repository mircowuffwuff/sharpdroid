#include "vma_tracker.h"
#include "guest_threads.h"

#include <FEXCore/Core/Context.h>
#include <FEXCore/Debug/InternalThreadState.h>
#include <FEXCore/Utils/WritePriorityMutex.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <map>
#include <mutex>
#include <shared_mutex>
#include <sys/mman.h>
#include <unistd.h>

namespace HostLayer::VMA {

namespace {

FEXCore::Context::Context* CTX {};
SMCMode CurrentMode {SMCMode::MTrack};

uint64_t PageSize() {
  // the device is a 4k-page kernel and FEXCore's FEX_PAGE_SIZE is 4096, so these agree today. they
  // are asked separately on purpose: FEXCore marks and invalidates in *its* page units, and the
  // host layer has to mprotect in the *kernel's*. on a 16k-page device the seal would cover four
  // FEX pages, which is coarse but not wrong — it seals more than asked, never less.
  static const uint64_t Size = static_cast<uint64_t>(::sysconf(_SC_PAGESIZE));
  return Size;
}

uint64_t AlignDown(uint64_t Value, uint64_t Alignment) {
  return Value & ~(Alignment - 1);
}

uint64_t AlignUp(uint64_t Value, uint64_t Alignment) {
  return AlignDown(Value + Alignment - 1, Alignment);
}

struct Entry {
  uint64_t Length;
  int Prot; ///< what the guest asked for, PROT_EXEC included
};

// keyed by base address; entries never overlap, and adjacent entries with the same protection are
// merged. merging is not just tidiness — Query hands the range straight to the decoder, which
// caches it and only re-asks when decoding walks out of it, so a fragmented map means a syscall
// handler's worth of work per block boundary.
std::map<uint64_t, Entry> VMAs;

// held shared by Query, MarkExecutable and the fault path, exclusively by the syscall layer.
//
// **the lock order is code-invalidation before VMA, never the other way round.** FEXCore calls
// MarkGuestExecutableRange from inside block compilation, where it may already hold the code
// invalidation mutex, and that call takes this lock. so every path here that has to invalidate
// releases this lock first and takes it again afterwards if it still needs it — which is exactly
// what FEX's own SyscallsSMCTracking.cpp does, and for the same reason.
std::shared_mutex MapLock;

std::atomic<uint64_t> SMCFaults {};
std::atomic<uint64_t> Invalidations {};

// --- did FEXCore translate the bytes that were actually there? -----------------------------------
//
// a guest that maps a page, writes a trampoline into it, protects it executable, runs it and
// unmaps it is doing the ordinary JIT/FFI thing, and SharpEmu's blocked-thread resume does exactly
// that ~400 times a second at addresses the allocator hands straight back. it has been measured
// running the *previous* occupant of such an address. everything below exists to answer one
// question the fork-side probe cannot: **did FEXCore translate the bytes that were actually
// there?**
//
// the signal is MarkGuestExecutableRange, and it is exact rather than approximate. FEXCore calls it
// for a page only when LookupCache::AddBlockExecutableRange reports that page's entry in the shared
// CodePages map was *empty*, and `GuestToHostMap::InvalidateRange` erases that entry for every page
// it covers. so it fires once per page per invalidation epoch, and "the page
// became executable, we invalidated it, and it was unmapped again without a single
// MarkGuestExecutableRange" means **no block on that page was compiled after the invalidation** —
// so whatever the guest ran there, it did not come from the bytes the guest had just written.
//
// large mappings are not tracked: guest text is mapped once and never unmapped, and it would sit in
// the table for the life of the process. the idiom this is about is small and short-lived.

// two ways a page becomes executable, and they are not the same event.
//
//   Mapped    — mmap(PROT_EXEC). the guest has an *empty* page it intends to put code in. mapping
//               one and freeing it again without ever running from it is an ordinary thing for an
//               allocator probing for a usable address to do, so nothing compiled here says nothing.
//   Protected — mprotect(PROT_EXEC) over a range that was already mapped. that is a guest saying
//               "the code I just wrote here is ready", and it is the last thing SharpEmu's resume
//               does before running the trampoline. **this is the column to read.**
//
// neither class is free of pages that are made executable and then unmapped without ever being
// run — SharpEmu's loader does a few hundred of those before the game starts, every run — so the
// number that means something is the **steady-state delta**, not the total. the running total below
// is what makes that delta readable, and `local-tools/witness-scan.py` baselines on the first one.
enum class StubSource {
  Mapped,
  Protected,
};

constexpr uint64_t StubTrackLimit = 64 * 1024;
constexpr uint64_t StubHistoryLimit = 8192;
///< anomaly lines allowed **per report interval**, not per run. a global budget is worse than no
///< budget: SharpEmu's loader retires a few hundred of these in four milliseconds during start-up,
///< which exhausted a 256-line cap before the game began and left every later occurrence with no
///< line at all — the run that mattered reported 309 of them and printed none.
constexpr uint64_t StubReportLimit = 48;
///< armings between running totals. matched to the fork witness's 2,048 resumes, because the two
///< numbers are read against each other and a run that stalls early stops arming as well: at 8,192
///< a short run got two reports and its whole steady state fell between them.
constexpr uint64_t StubReportEvery = 2048;

struct StubPage {
  uint64_t Serial;     ///< which arming this page is on, so a report names one occupancy
  uint32_t Generation; ///< how many times this *address* has been armed, ever. 1 is a fresh address
  StubSource Source;
  bool Compiled; ///< MarkGuestExecutableRange fired since this arming
};

std::mutex StubLock; ///< a leaf. nothing is ever acquired while it is held, MapLock included
std::map<uint64_t, StubPage> StubPages;
std::map<uint64_t, uint32_t> StubHistory;
uint64_t StubSerial {};

std::atomic<uint64_t> StubArmed {};       ///< small pages that became executable
std::atomic<uint64_t> StubCompiled {};    ///< of those, ones FEXCore compiled a block on
std::atomic<uint64_t> StubStale {};       ///< armed, unmapped, never compiled
std::atomic<uint64_t> StubStaleReused {}; ///< of those, ones at an address that had been armed before
std::atomic<uint64_t> StubProtected {};   ///< armings by mprotect rather than by mmap
std::atomic<uint64_t> StubStaleProtected {}; ///< the number this whole file exists to produce
std::atomic<uint64_t> StubReports {};

// `live` is not decoration: if pages were being armed and never retired, `stale` would sit at zero
// because nothing ever reaches the check, and the instrument would report a confident nothing. a
// figure that stays small is the evidence that the table drains.
void PrintStubTotals() {
  uint64_t Live {};
  {
    std::lock_guard Lock {StubLock};
    Live = StubPages.size();
  }
  std::printf("[vma] short-lived exec pages: %llu armed (%llu by mprotect), %llu compiled, "
              "%llu live, %llu stale (%llu at a reused address, %llu armed by mprotect)\n",
              static_cast<unsigned long long>(StubArmed.load(std::memory_order_relaxed)),
              static_cast<unsigned long long>(StubProtected.load(std::memory_order_relaxed)),
              static_cast<unsigned long long>(StubCompiled.load(std::memory_order_relaxed)),
              static_cast<unsigned long long>(Live), static_cast<unsigned long long>(StubStale.load(std::memory_order_relaxed)),
              static_cast<unsigned long long>(StubStaleReused.load(std::memory_order_relaxed)),
              static_cast<unsigned long long>(StubStaleProtected.load(std::memory_order_relaxed)));
  std::fflush(stdout);

  // the anomaly-line budget is per interval, so a burst in one interval cannot silence the next.
  StubReports.store(0, std::memory_order_relaxed);
}

// a small range just became executable.
void ArmStubPages(uint64_t Begin, uint64_t End, StubSource Source) {
  if (End - Begin > StubTrackLimit) {
    return;
  }
  const uint64_t Page = PageSize();
  uint64_t Armed {};
  {
    std::lock_guard Lock {StubLock};
    for (uint64_t At = Begin; At < End; At += Page) {
      uint32_t Generation = 1;
      if (auto It = StubHistory.find(At); It != StubHistory.end()) {
        Generation = ++It->second;
      } else if (StubHistory.size() < StubHistoryLimit) {
        StubHistory.emplace(At, 1);
      }
      StubPages[At] = StubPage {++StubSerial, Generation, Source, false};
      Armed = StubArmed.fetch_add(1, std::memory_order_relaxed) + 1;
      if (Source == StubSource::Protected) {
        StubProtected.fetch_add(1, std::memory_order_relaxed);
      }
    }
  }

  // a running total, because every measurement here is taken by killing the process after N
  // seconds — an exit-time summary would never be printed at all. the witness probe on the other
  // side of this comparison reports the same way and for the same reason.
  if (Armed && Armed % StubReportEvery == 0) {
    PrintStubTotals();
  }
}

// FEXCore compiled a block on this page.
void NoteStubCompiled(uint64_t Begin, uint64_t End) {
  std::lock_guard Lock {StubLock};
  if (StubPages.empty()) {
    return;
  }
  for (auto It = StubPages.lower_bound(Begin); It != StubPages.end() && It->first < End; ++It) {
    if (!It->second.Compiled) {
      It->second.Compiled = true;
      StubCompiled.fetch_add(1, std::memory_order_relaxed);
    }
  }
}

// the range is going away. @param Report is false when it merely stopped being executable, which
// says nothing either way — only an unmap means the occupancy is over.
void RetireStubPages(uint64_t Begin, uint64_t End, bool Report) {
  std::lock_guard Lock {StubLock};
  if (StubPages.empty()) {
    return;
  }
  auto It = StubPages.lower_bound(Begin);
  while (It != StubPages.end() && It->first < End) {
    if (Report && !It->second.Compiled) {
      StubStale.fetch_add(1, std::memory_order_relaxed);
      if (It->second.Generation > 1) {
        StubStaleReused.fetch_add(1, std::memory_order_relaxed);
      }
      if (It->second.Source == StubSource::Protected) {
        StubStaleProtected.fetch_add(1, std::memory_order_relaxed);
        // only this class gets a line, and it is capped: the mmap side runs to thousands of pages
        // per loader generation and a line each would drown the log this is meant to be read
        // beside. the tid tells hits on different threads apart and shows when they cluster on one;
        // it does **not** give the guest thread *name* the fork's witness reports, because a guest
        // thread names itself through SharpEmu's HLE and never through a host prctl. matching the
        // two still costs SHARPEMU_LOG_GUEST_THREADS.
        if (StubReports.fetch_add(1, std::memory_order_relaxed) < StubReportLimit) {
          std::printf("[vma] stale-exec 0x%llx unmapped with no block compiled since its mprotect "
                      "(arming #%llu, address armed %u time(s), tid %d)\n",
                      static_cast<unsigned long long>(It->first), static_cast<unsigned long long>(It->second.Serial),
                      It->second.Generation, static_cast<int>(::gettid()));
          std::fflush(stdout);
        }
      }
    }
    It = StubPages.erase(It);
  }
}

std::map<uint64_t, Entry>::iterator FindLocked(uint64_t Address) {
  auto It = VMAs.upper_bound(Address);
  if (It == VMAs.begin()) {
    return VMAs.end();
  }
  --It;
  return Address < It->first + It->second.Length ? It : VMAs.end();
}

// remove [Base, End) from the map, splitting whichever entries straddle either edge.
void CarveLocked(uint64_t Base, uint64_t End) {
  auto It = VMAs.lower_bound(Base);

  // an entry starting before Base may reach into the range, or straight over it.
  if (It != VMAs.begin()) {
    auto Prev = std::prev(It);
    const uint64_t PrevEnd = Prev->first + Prev->second.Length;
    if (PrevEnd > Base) {
      if (PrevEnd > End) {
        VMAs.emplace(End, Entry {PrevEnd - End, Prev->second.Prot});
      }
      // lower_bound guarantees Prev->first < Base, so this length is never zero.
      Prev->second.Length = Base - Prev->first;
    }
  }

  while (It != VMAs.end() && It->first < End) {
    const uint64_t ItEnd = It->first + It->second.Length;
    if (ItEnd <= End) {
      It = VMAs.erase(It);
    } else {
      const Entry Tail {ItEnd - End, It->second.Prot};
      VMAs.erase(It);
      VMAs.emplace(End, Tail);
      break;
    }
  }
}

void RecordLocked(uint64_t Base, uint64_t End, int Prot) {
  CarveLocked(Base, End);

  uint64_t NewBase = Base;
  uint64_t NewEnd = End;

  auto At = VMAs.lower_bound(NewBase);
  if (At != VMAs.begin()) {
    auto Prev = std::prev(At);
    if (Prev->first + Prev->second.Length == NewBase && Prev->second.Prot == Prot) {
      NewBase = Prev->first;
      VMAs.erase(Prev);
    }
  }
  auto Next = VMAs.find(NewEnd);
  if (Next != VMAs.end() && Next->second.Prot == Prot) {
    NewEnd += Next->second.Length;
    VMAs.erase(Next);
  }

  VMAs.emplace(NewBase, Entry {NewEnd - NewBase, Prot});
}

// drop every translation FEXCore holds for a range, and — while still holding the invalidation
// mutex — optionally put a protection back.
//
// the two have to happen together. unsealing after releasing the mutex leaves a window in which
// another thread compiles the page, seals it again, and then has its seal removed by us: the
// block would survive the next guest write undetected.
//
// @return false only when a requested restore failed, which is the fault handler's evidence that
// the write it is trying to rescue was never going to succeed.
bool InvalidateAndRestore(FEXCore::Core::InternalThreadState* Thread, uint64_t Base, uint64_t Length, int RestoreProt) {
  if (!CTX) {
    return true;
  }

  struct Range {
    uint64_t Base, Length;
  } R {Base, Length};

  std::lock_guard InvalidationLock {CTX->GetCodeInvalidationMutex()};

  CTX->InvalidateCodeBuffersCodeRange(Base, Length);
  // and every thread's own lookup cache: a block lives in the shared code buffers *and* in the
  // cache of whichever thread compiled it, and dropping only the first leaves the second pointing
  // at host code that is about to be reused for something else. the registry lock is taken inside
  // the invalidation mutex and is a leaf — nothing is ever acquired while holding it.
  Threads::ForEachLive(
    [](GuestThread& T, void* User) {
      const auto* Which = static_cast<const Range*>(User);
      CTX->InvalidateThreadCachedCodeRange(T.Thread, Which->Base, Which->Length);
    },
    &R);

  Invalidations.fetch_add(1, std::memory_order_relaxed);

  return RestoreProt < 0 || ::mprotect(reinterpret_cast<void*>(Base), Length, RestoreProt) == 0;
}

} // namespace

int HostProt(int GuestProt) {
  int Prot = GuestProt & ~PROT_EXEC;
  if (GuestProt & PROT_EXEC) {
    Prot |= PROT_READ;
  }
  return Prot;
}

void Initialize(FEXCore::Context::Context* Context, SMCMode SMC) {
  CTX = Context;
  CurrentMode = SMC;
}

SMCMode Mode() {
  return CurrentMode;
}

void Record(uint64_t Base, uint64_t Length, int GuestProt) {
  if (!Length) {
    return;
  }
  const uint64_t Begin = AlignDown(Base, PageSize());
  const uint64_t End = AlignUp(Base + Length, PageSize());

  {
    std::unique_lock Lock {MapLock};
    RecordLocked(Begin, End, GuestProt);
  }

  // a fresh mapping that is executable from its first instant — mmap(PROT_EXEC), which is what
  // VirtualAlloc(PAGE_EXECUTE_*) becomes. nothing is invalidated here today, which is one of the
  // things the instrument is for.
  if (GuestProt & PROT_EXEC) {
    ArmStubPages(Begin, End, StubSource::Mapped);
  }
}

void Forget(uint64_t Base, uint64_t Length) {
  if (!Length) {
    return;
  }
  const uint64_t Begin = AlignDown(Base, PageSize());
  const uint64_t End = AlignUp(Base + Length, PageSize());

  {
    std::unique_lock Lock {MapLock};
    CarveLocked(Begin, End);
  }

  // the occupancy is over, so this is where a page that was never compiled from gets reported.
  RetireStubPages(Begin, End, true);

  // after the map says the range is gone, not before: a thread compiling out of these pages
  // between the two would put the block back. the map is what stops it — Query now refuses.
  InvalidateAndRestore(Threads::Current() ? Threads::Current()->Thread : nullptr, Begin, End - Begin, -1);
}

void Reprotect(uint64_t Base, uint64_t Length, int GuestProt) {
  if (!Length) {
    return;
  }
  const uint64_t Begin = AlignDown(Base, PageSize());
  const uint64_t End = AlignUp(Base + Length, PageSize());

  {
    std::unique_lock Lock {MapLock};
    // mprotect only succeeds if the whole range was mapped, and we are only called after it did,
    // so overwriting the range wholesale is exactly right: nothing is being invented here.
    RecordLocked(Begin, End, GuestProt);
  }

  // unconditionally, not only when PROT_EXEC goes away. a page going writable has to lose its
  // seal, a page losing PROT_EXEC must stop being decodable, and a page that merely changed
  // between two executable protections may still have been re-protected around a rewrite.
  InvalidateAndRestore(Threads::Current() ? Threads::Current()->Thread : nullptr, Begin, End - Begin, -1);

  // this is the arming that matters: mprotect(PROT_EXEC) is the last thing SharpEmu's resume does
  // before running the trampoline, and it has just invalidated the range. anything the guest then
  // executes there must have been compiled after this point.
  if (GuestProt & PROT_EXEC) {
    ArmStubPages(Begin, End, StubSource::Protected);
  } else {
    // no longer executable, so the page cannot be run and its silence proves nothing.
    RetireStubPages(Begin, End, false);
  }
}

void Remap(uint64_t OldBase, uint64_t OldLength, uint64_t NewBase, uint64_t NewLength) {
  int Prot = PROT_READ | PROT_WRITE;
  {
    std::shared_lock Lock {MapLock};
    if (auto It = FindLocked(OldBase); It != VMAs.end()) {
      Prot = It->second.Prot;
    }
  }

  if (OldBase != NewBase || NewLength < OldLength) {
    // MREMAP_DONTUNMAP aside, the old range either moved away entirely or shrank in place. either
    // way the bytes FEXCore compiled are no longer reachable at the addresses it compiled them for.
    Forget(OldBase, OldLength);
  }
  Record(NewBase, NewLength, Prot);
}

FEXCore::HLE::ExecutableRangeInfo Query(uint64_t Address) {
  std::shared_lock Lock {MapLock};

  auto It = FindLocked(Address);
  if (It == VMAs.end() || !(It->second.Prot & PROT_EXEC)) {
    // a Size of 0 is the decoder's signal to refuse. this is the whole point of the file: before
    // it existed the host layer answered "the entire address space is executable", and a guest
    // that jumped into a data page got that page translated instead of a fault.
    return {0, 0, false};
  }
  return {It->first, It->second.Length, (It->second.Prot & PROT_WRITE) != 0};
}

void MarkExecutable(uint64_t Base, uint64_t Length) {
  const uint64_t Begin = AlignDown(Base, PageSize());
  const uint64_t End = AlignUp(Base + Length, PageSize());

  // ahead of the mode check, and outside MapLock. this is FEXCore telling us it has just compiled
  // a block on this page, which is the one fact the instrument is built around — and it is worth
  // having under `--smc none` too, where nothing else in this function happens.
  NoteStubCompiled(Begin, End);

  if (CurrentMode != SMCMode::MTrack) {
    return;
  }

  std::shared_lock Lock {MapLock};

  // FindLocked answers "which entry contains Begin", and returns end() when nothing does — which
  // is not the same as "no entry overlaps the range", since one may start partway into it.
  auto First = FindLocked(Begin);
  if (First == VMAs.end()) {
    First = VMAs.lower_bound(Begin);
  }

  for (auto It = First; It != VMAs.end() && It->first < End; ++It) {
    if (!(It->second.Prot & PROT_WRITE)) {
      // read-only guest text — the common case for a normal ELF. nothing can rewrite it without
      // an mprotect first, and mprotect invalidates.
      continue;
    }
    const uint64_t SealBegin = std::max(It->first, Begin);
    const uint64_t SealEnd = std::min(It->first + It->second.Length, End);
    ::mprotect(reinterpret_cast<void*>(SealBegin), SealEnd - SealBegin, HostProt(It->second.Prot) & ~PROT_WRITE);
  }
}

void Invalidate(FEXCore::Core::InternalThreadState* Thread, uint64_t Base, uint64_t Length) {
  InvalidateAndRestore(Thread, Base, Length, -1);
}

WriteFault HandleWriteFault(FEXCore::Core::InternalThreadState* Thread, uint64_t FaultAddress, uint64_t HostPC) {
  if (CurrentMode != SMCMode::MTrack) {
    return WriteFault::NotOurs;
  }

  const uint64_t Page = PageSize();
  const uint64_t FaultBase = AlignDown(FaultAddress, Page);

  int Prot;
  {
    std::shared_lock Lock {MapLock};
    auto It = FindLocked(FaultAddress);
    if (It == VMAs.end() || !(It->second.Prot & PROT_WRITE)) {
      // either not guest memory at all, or memory the guest never asked to write. a fault there
      // is the guest's own, and belongs to the guest's handler.
      return WriteFault::NotOurs;
    }
    Prot = It->second.Prot;
  }

  // a write spanning two pages faults once per page, and each is handled on its own.
  //
  // the return value is the guard against looping here forever. everything above is inference —
  // "the guest asked for this to be writable, so the only thing that can have taken the
  // permission away is us" — and if that inference is wrong, handing back a protection the kernel
  // refuses is the one place it shows. resuming then would re-fault at the same instruction
  // immediately, and again, inside a signal handler, with nothing to break the cycle.
  if (!InvalidateAndRestore(Thread, FaultBase, Page, HostProt(Prot))) {
    return WriteFault::NotOurs;
  }
  SMCFaults.fetch_add(1, std::memory_order_relaxed);

  // if the guest is rewriting code inside the block it is currently executing, re-running the
  // faulting instruction is not enough — the rest of that block is the translation we have just
  // dropped, and control would run straight into it. the caller re-enters the dispatcher asking
  // for a single-instruction block, so any further modification is picked up immediately.
  if (CTX && CTX->IsAddressInCodeBuffer(Thread, HostPC) && !CTX->IsCurrentBlockSingleInst(Thread) &&
      CTX->IsAddressInCurrentBlock(Thread, FaultBase, Page)) {
    return WriteFault::SingleStep;
  }
  return WriteFault::Resume;
}

uint64_t EntryCount() {
  std::shared_lock Lock {MapLock};
  return VMAs.size();
}

uint64_t SMCFaultCount() {
  return SMCFaults.load(std::memory_order_relaxed);
}

uint64_t InvalidationCount() {
  return Invalidations.load(std::memory_order_relaxed);
}

StubStats StubReport() {
  return {
    StubArmed.load(std::memory_order_relaxed),   StubProtected.load(std::memory_order_relaxed),
    StubCompiled.load(std::memory_order_relaxed), StubStale.load(std::memory_order_relaxed),
    StubStaleReused.load(std::memory_order_relaxed), StubStaleProtected.load(std::memory_order_relaxed),
  };
}

} // namespace HostLayer::VMA
