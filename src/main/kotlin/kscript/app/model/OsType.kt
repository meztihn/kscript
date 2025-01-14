package kscript.app.model

enum class OsType(val osName: String) {
    LINUX("linux"), DARWIN("darwin"), WINDOWS("windows"), CYGWIN("cygwin"), MSYS("msys"), FREEBSD("freebsd");

    fun isUnixLike() = (this == LINUX || this == DARWIN || this == FREEBSD)
    fun isWindowsLike() = (this == WINDOWS)
    fun isUnixHostedOnWindows() = (this == CYGWIN || this == MSYS)

    companion object {
        fun findOrThrow(name: String): OsType {
            // Exact comparison (it.osName.equals(name, true)) seems to be not feasible as there is also e.g. "darwin21"
            // "darwin19" and maybe even other osTypes. It seems though that startsWith() is covering all cases.
            // https://github.com/holgerbrandl/kscript/issues/356
            return values().find { name.startsWith(it.osName, true) }
                ?: throw IllegalArgumentException("Unsupported OS: $name")
        }
    }
}
