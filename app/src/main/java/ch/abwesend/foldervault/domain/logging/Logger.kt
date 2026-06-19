package ch.abwesend.foldervault.domain.logging

/** Convenience accessor used across the codebase: `logger.info("...")`. */
val Any.logger: ILogger get() = LoggerProvider.forTag(this::class.java.simpleName ?: "FolderVault")
