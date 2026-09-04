package info.skyblond.daapu.agent.tool

/**
 * The path helpers shared by the tool providers.
 */

/**
 * The `~` / `~/...` expansion used by the tool providers' directory
 * settings (the filesystem provider's allowed dirs, the bash tool's
 * workdir): a bare `~` becomes the `user.home` system property, a leading
 * `~/` splices the home in front of the remainder; anything else
 * (including a missing `user.home`) is returned verbatim. Canonicalization
 * and existence checks stay with the callers.
 */
internal fun expandHomePath(path: String): String {
    val home = System.getProperty("user.home") ?: return path
    return when {
        path == "~" -> home
        path.startsWith("~/") -> home + path.substring(1)
        else -> path
    }
}
