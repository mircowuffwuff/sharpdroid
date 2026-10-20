// sharpdroid host layer -- reading a guest x86-64 call's arguments out of the spilled state.
//
// this is the piece both thunks stand on, and it is here rather than in either of them because
// there is exactly one right answer and two copies of it would be two chances to be wrong.
//
// FEXCore spills the *entire* guest state, GPRs and FPRs alike, before every syscall
// (JIT/BranchOps.cpp DEF_OP(Syscall), GPRSpillMask = FPRSpillMask = ~0U), so at a thunk trap
// CPUState is a complete and exact description of the guest's registers at the call. that is what
// lets one 16-byte stub shape serve any number of entry points with different signatures: the
// *host* reads the arguments according to the SysV AMD64 classification, so no stub ever has to
// know anything about its own prototype.
//
// the classification is cut down to what a C API without by-value aggregates uses: INTEGER for
// everything that is not floating point, SSE for what is, and a return value that is always in
// RAX. Next<T>() static_asserts the "no aggregates" half, so a header that grows a by-value struct
// parameter stops the build instead of quietly reading the wrong register.
//
// and there is **no pointer translation anywhere**, which is the single biggest thing sharing a
// process buys: guest and host share one address space 1:1, so a guest pointer is already a
// host pointer and every structure the guest fills in is read by the real library in place.

#pragma once

#include <FEXCore/Core/CoreState.h>

#include <cstdint>
#include <cstring>
#include <tuple>
#include <type_traits>

namespace HostLayer::ThunkABI {

// which half of CPUState's XMM union the register file is in. FEX picks the 32-byte stride avx
// layout only when SupportsAVX *and* SupportsSVE256 are set -- Arm64Emitter::SpillStaticRegs and
// Arm64Emitter::FillStaticRegs are the two sites. with AVX alone, which is what the host-features
// work turned on and what this device runs since the Oryon cores have no SVE256, spills go to the
// 16-byte stride sse layout instead. reading the wrong one is
// not a crash, it is every float argument after the first silently belonging to a different
// register. set once from main.cpp, off what FEXCore reports rather than off what we assume.
inline bool AvxRegisterFileState {};
inline void SetAvxRegisterFile(bool Avx) {
  AvxRegisterFileState = Avx;
}
inline bool AvxRegisterFile() {
  return AvxRegisterFileState;
}

class ArgReader {
public:
  ArgReader(const uint64_t* IntArgs, const FEXCore::Core::CPUState& State)
    : IntArgs(IntArgs)
    , State(State)
    // the stub does not touch the stack, so at the syscall RSP still points at the return
    // address the guest's call pushed. stack-passed arguments start one slot above it.
    , StackBase(State.gregs[FEXCore::X86State::REG_RSP] + 8) {}

  template<typename T>
  T Next() {
    static_assert(std::is_scalar_v<T> && sizeof(T) <= 8,
                  "this ABI reader handles only scalars passed by value; an aggregate would need "
                  "real SysV classification");

    if constexpr (std::is_floating_point_v<T>) {
      const uint64_t Raw = XmmLow(NextSSE++);
      T Value {};
      std::memcpy(&Value, &Raw, sizeof(T));
      return Value;
    } else {
      const uint64_t Raw = TakeInteger();
      T Value {};
      std::memcpy(&Value, &Raw, sizeof(T));
      return Value;
    }
  }

  // consume an integer-class argument and discard it. the callers are the places a thunk refuses
  // to pass something through -- a guest allocation callback, say -- and they still have to advance
  // the reader or every argument after it belongs to the wrong parameter.
  void SkipInteger() { TakeInteger(); }

private:
  uint64_t TakeInteger() {
    if (NextInteger < 6) {
      return IntArgs[NextInteger++];
    }
    uint64_t Value {};
    std::memcpy(&Value, reinterpret_cast<const void*>(StackBase + 8 * NextStack++), sizeof(Value));
    return Value;
  }

  uint64_t XmmLow(unsigned Index) const {
    return AvxRegisterFile() ? State.xmm.avx.data[Index][0] : State.xmm.sse.data[Index][0];
  }

  const uint64_t* IntArgs;
  const FEXCore::Core::CPUState& State;
  uint64_t StackBase;
  unsigned NextInteger {};
  unsigned NextSSE {};
  unsigned NextStack {};
};

// the whole marshaller, in one template. deducing Args... from a function pointer type is what
// removes any need for a per-command generator on the host side: the compiler already knows every
// signature, so the only thing that has to be generated is the *list*.
//
// Read is a customisation point rather than ArgReader::Next directly, so that a thunk can refuse
// to forward a particular parameter type -- vulkan's VkAllocationCallbacks* is the one case, and it
// is expressed as a type-level rule there rather than as ninety named commands here.
//
// the tuple is brace-initialised on purpose. plain function-argument evaluation order is
// unspecified in C++, which would hand the arguments to Read<T>() in whatever order the compiler
// felt like and shuffle every call's registers; the initializer-clauses of a braced-init-list are
// guaranteed to be evaluated left to right.
template<template<typename> class Read, typename PFN>
struct Marshal;

template<template<typename> class Read, typename Ret, typename... Args>
struct Marshal<Read, Ret (*)(Args...)> {
  static uint64_t Call(void* Fn, ArgReader& R) {
    auto* Host = reinterpret_cast<Ret (*)(Args...)>(Fn);
    std::tuple<Args...> Unpacked {Read<Args>::From(R)...};
    if constexpr (std::is_void_v<Ret>) {
      std::apply(Host, Unpacked);
      return 0;
    } else {
      const Ret Value = std::apply(Host, Unpacked);
      uint64_t Out {};
      std::memcpy(&Out, &Value, sizeof(Value));
      return Out;
    }
  }
};

// the default rule: read the argument and pass it on.
template<typename T>
struct PassThrough {
  static T From(ArgReader& R) { return R.template Next<T>(); }
};

} // namespace HostLayer::ThunkABI
