package com.baystudio.droide.core

/** One fixed backend owner, published only after application startup resolution succeeds. */
internal data class ResolvedProjectCatalog(val store: ProjectStore, val defaultStartup: DefaultProjectStartup?)
