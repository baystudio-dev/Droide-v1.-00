package com.baystudio.droide.core

/** Exact reviewed profiles only. No inferred scheduling from language IDs or custom commands. */
internal data class LspBackgroundPreparationPolicy(val label: String, val initializeMs: Long, val attemptMs: Long, val replayMs: Long) {
    companion object {
        fun forSpec(spec: LanguageServerSpec): LspBackgroundPreparationPolicy? = when {
            LspServerProfiles.isAsm(spec) -> LspBackgroundPreparationPolicy("Assembly", 30_000, 90_000, 15_000)
            LspServerProfiles.isSolidity(spec) -> LspBackgroundPreparationPolicy("Solidity", 90_000, 180_000, 15_000)
            LspServerProfiles.isScala(spec) -> LspBackgroundPreparationPolicy("Scala", 60_000, 180_000, 15_000)
            LspServerProfiles.isElixir(spec) -> LspBackgroundPreparationPolicy("Elixir", 60_000, 180_000, 15_000)
            LspServerProfiles.isHaskell(spec) -> LspBackgroundPreparationPolicy("Haskell", 60_000, 120_000, 15_000)
            LspServerProfiles.isR(spec) -> LspBackgroundPreparationPolicy("R", 40_000, 90_000, 15_000)
            LspServerProfiles.isElp(spec) -> LspBackgroundPreparationPolicy("ELP", 40_000, 180_000, 30_000)
            LspServerProfiles.isCSharp(spec) -> LspBackgroundPreparationPolicy("C# native model", 60_000, 120_000, 15_000)
            LspServerProfiles.isVue(spec) -> LspBackgroundPreparationPolicy("Vue native pair", 25_000, 45_000, 20_000)
            LspServerProfiles.isNim(spec) -> LspBackgroundPreparationPolicy("Nim fixed-toolchain", 30_000, 90_000, 15_000)
            LspServerProfiles.isSvelte(spec) -> LspBackgroundPreparationPolicy("Svelte", 30_000, 90_000, 45_000)
            LspServerProfiles.isClojure(spec) -> LspBackgroundPreparationPolicy("Clojure", LspClojureProfile.INITIALIZE_TIMEOUT_MS,
                LspClojureProfile.ATTEMPT_TIMEOUT_MS, LspClojureProfile.REPLAY_TIMEOUT_MS)
            LspServerProfiles.isPowerShell(spec) -> LspBackgroundPreparationPolicy("PowerShell", 60_000, 90_000, 15_000)
            else -> null
        }
    }
}
