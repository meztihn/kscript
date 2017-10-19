package kscript.app

import kscript.app.ShellUtils.requireInPath
import org.docopt.DocOptWrapper
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import javax.xml.bind.DatatypeConverter


/**
 * A kscript reimplementation in kotlin
 *
 * @author Holger Brandl
 */

const val KSCRIPT_VERSION = "2.0.0"

val USAGE = """
kscript - Enhanced scripting support for Kotlin on *nix-based systems.

Usage:
 kscript [options] <script> [<script_args>]...
 kscript --clear-cache
 kscript --self-update

The <script> can be a  script file (*kts), a script URL, - for stdin, a *.kt source file with a main method, or some kotlin code.

Use '--clear-cache' to wipe cached script jars and urls
Use '--self-update' to wipe cached script jars and urls

Options:
 -i --interactive        Create interactive shell with DEPS as declared in script

Copyright : 2017 Holger Brandl
License   : MIT
Version   : v$KSCRIPT_VERSION
Website   : https://github.com/holgerbrandl/kscript
""".trim()

val KSCRIPT_CACHE_DIR = File(System.getenv("HOME")!!, ".kscript")


fun main(args: Array<String>) {
    // skip org.docopt for version and help to allow for lazy version-check
    if (args.size == 1 && listOf("--help", "-h", "--version", "-v").contains(args[0])) {
        info(USAGE)
        versionCheck()
        quit(0)
    }

    val docopt = DocOptWrapper(args, USAGE)

    // create cache dir if it does not yet exist
    if (!KSCRIPT_CACHE_DIR.isDirectory) {
        KSCRIPT_CACHE_DIR.mkdir()
    }

    // optionally clear up the jar cache
    if (docopt.getBoolean("clear-cache")) {
        info("Cleaning up cache...")
        KSCRIPT_CACHE_DIR.listFiles().forEach { it.delete() }
        //        evalBash("rm -f ${KSCRIPT_CACHE_DIR}/*")
        quit(0)
    }

    // optionally self-update kscript ot the newest version
    // (if not local copy is not being maintained by sdkman)
    if (docopt.getBoolean(("self-update"))) {
        if (evalBash("which kscript | grep .sdkman").stdout.isNotBlank()) {
            info("Installing latest version of kscript...")
            println("sdkman_auto_answer=true && sdk install kscript")
        } else {
            info("Self-update is currently just supported via sdkman.")
            // todo port sdkman-indpendent self-update
        }

        quit(0)
    }


    // Resolve the script resource argument into an actual file
    val scriptResource = docopt.getString("script")
    val scriptFile = prepareScript(scriptResource)


    val scriptText = scriptFile.readLines()

    // Make sure that dependencies declarations are well formatted
    if (scriptText.any { it.startsWith("// DEPS") }) {
        error("Dependencies must be declared by using the line prefix //DEPS")
    }

    // Find all //DEPS directives and concatenate their values
    val dependencies = scriptText
            .filter { it.startsWith("//DEPS ") }
            .map { it.split("[ ]+".toRegex())[1] }
            .flatMap { it.split(";", ",", " ") }
            .map(String::trim)

    val classpath = resolveDependencies(dependencies)

    // Extract kotlin arguments
    val kotlinOpts = scriptText.
            filter { it.startsWith("//KOTLIN_OPTS ") }.
            flatMap { it.split(" ").drop(0) }.
            joinToString(" ")


    //  Optionally enter interactive mode
    if (docopt.getBoolean("interactive")) {
        System.err.println("Creating REPL from ${scriptFile}")
        System.err.println("kotlinc ${kotlinOpts} -classpath '${classpath}'")

        println("kotlinc ${kotlinOpts} -classpath ${classpath}")
    }

    val scriptFileExt = scriptFile.extension
    val scriptCheckSum = md5(scriptFile)


    // Even if we just need and support the //ENTRY directive in case of kt-class
    // files, we extract it here to fail if it was used in kts files.
    val entryDirective = scriptText
            .find { it.contains("^//ENTRY ".toRegex()) }
            ?.replace("//ENTRY ", "")?.trim()

    errorIf(entryDirective != null && scriptFileExt == "kts") {
        "//ENTRY directive is just supported for kt class files"
    }


    val jarFile = if (scriptFile.nameWithoutExtension.endsWith(scriptCheckSum)) {
        File(KSCRIPT_CACHE_DIR, scriptFile.nameWithoutExtension + ".jar")
    } else {
        File(KSCRIPT_CACHE_DIR, scriptFile.nameWithoutExtension + "." + scriptCheckSum + ".jar")
    }

    // Capitalize first letter and get rid of dashes (since this is what kotlin compiler is doing for the wrapper to create a valid java class name)
    val className = scriptFile.nameWithoutExtension
            .replace("[.-]".toRegex(), "_")
            .capitalize()


    // Define the entrypoint for the scriptlet jar
    val execClassName = if (scriptFileExt == "kts") {
        "Main_${className}"
    } else {
        // extract package from kt-file
        val pckg = scriptText.find { it.startsWith("package ") }
                ?.split("[ ]+".toRegex())?.get(1)?.run { this + "." }

        """${pckg ?: ""}${entryDirective ?: "${className}Kt"}"""
    }


    // infer KOTLIN_HOME if not set
    @Suppress("LocalVariableName")
    val KOTLIN_HOME = System.getenv("KOTLIN_HOME") ?: guessKotlinHome()
    errorIf(KOTLIN_HOME == null) {
        "KOTLIN_HOME is not set and could not be inferred from context"
    }


    // If scriplet jar ist not cached yet, build it
    if (!jarFile.isFile) {
        // disabled because a user might have same-named scripts for different projects
        // // remove previous (now outdated) cache jars
        // KSCRIPT_CACHE_DIR.listFiles({
        //     file -> file.name.startsWith(scriptFile.nameWithoutExtension) && file.extension=="jar"
        // }).forEach { it.delete() }


        requireInPath("kotlinc")

        val scriptCompileResult = evalBash("kotlinc -classpath '$classpath' -d '${jarFile.absolutePath}' '${scriptFile.absolutePath}'")
        with(scriptCompileResult) {
            errorIf(exitCode != 0) { "compilation of '$scriptResource' failed\n$stderr" }
        }


        // create main-wrapper for kts scripts
        if (scriptFileExt == "kts") {
            val mainJava = File(createTempDir("kscript"), execClassName + ".java")
            mainJava.writeText("""
            public class Main_${className} {
                public static void main(String... args) throws Exception {
                    Class script = Main_${className}.class.getClassLoader().loadClass("${className}");
                    script.getDeclaredConstructor(String[].class).newInstance((Object)args);
                }
            }
            """.trimIndent())

            // compile the wrapper
            with(evalBash("javac '${mainJava}'")) {
                errorIf(exitCode != 0) { "Compilation of script-wrapper failed:$stderr" }
            }

            // update the jar to include main-wrapper
            // requireInPath("jar") // disabled because it's another process invocation
            val jarUpdateCmd = "jar uf '${jarFile.absoluteFile}' ${mainJava.nameWithoutExtension}.class"
            with(evalBash(jarUpdateCmd, wd = mainJava.parentFile)) {
                errorIf(exitCode != 0) { "Update of script jar with wrapper class failed\n${stderr}" }
            }
        }
    }


    // print the final command to be run by exec
    val shiftedArgs = args.drop(1 + args.indexOfFirst { it == scriptResource }).
            //            map { "\""+it+"\"" }.
            joinToString(" ")

    println("kotlin ${kotlinOpts} -classpath ${jarFile}${CP_SEPARATOR_CHAR}${KOTLIN_HOME}${File.separatorChar}lib${File.separatorChar}kotlin-script-runtime.jar${CP_SEPARATOR_CHAR}${classpath} ${execClassName} ${shiftedArgs} ")
}

/** Determine the latest version by checking github repo and print info if newer version is availabe. */
fun versionCheck() {

    //    val latestVersion = fetchFromURL("https://git.io/v9R73")?.useLines {
    //    val kscriptRawReleaseURL= "https://git.io/v9R73"
    // todo use the actual kscript.app.Kscript.kt here to infer version
    val kscriptRawReleaseURL = "https://raw.githubusercontent.com/holgerbrandl/kscript/releases/kscript"
    val latestVersion = BufferedReader(InputStreamReader(URL(kscriptRawReleaseURL).openStream())).useLines {
        it.first { it.startsWith("KSCRIPT_VERSION") }.split("=")[1]
    }

    fun padVersion(version: String) = java.lang.String.format("%03d%03d%03d", *version.split(".").map { Integer.valueOf(it) }.toTypedArray())

    if (padVersion(latestVersion) > padVersion(KSCRIPT_VERSION)) {
        info("""\nA new version (v${latestVersion}) of kscript is available. Use 'kscript --self-update' to update your local kscript installation""")
    }
}

/** see discussion on https://github.com/holgerbrandl/kscript/issues/15*/
private fun guessKotlinHome(): String? {
    return evalBash("KOTLIN_RUNNER=1 JAVACMD=echo kotlinc").stdout.run {
        "kotlin.home=([^\\s]*)".toRegex()
                .find(this)?.groups?.get(1)?.value
    }
}

fun prepareScript(scriptResource: String): File {
    var scriptFile: File?

    // map script argument to script file
    scriptFile = with(File(scriptResource)) {
        if (!canRead()) {
            // not a file so let's keep the script-file undefined here
            null
        } else if (listOf("kts", "kt").contains(extension)) {
            // script input is a regular script or clas file
            this
        } else {
            // if we can "just" read from script resource create tmp file
            // i.e. script input is process substitution file handle
            // not FileInputStream(this).bufferedReader().use{ readText()} does not work nor does this.readText
            createTmpScript(FileInputStream(this).bufferedReader().readText())
        }
    }

    // support stdin
    if (scriptResource == "-" || scriptResource == "/dev/stdin") {
        val scriptText = generateSequence() { readLine() }.joinToString("\n").trim()
        scriptFile = createTmpScript(scriptText)
    }


    // Support URLs as script files
    if (scriptResource.startsWith("http://") || scriptResource.startsWith("https://")) {
        scriptFile = fetchFromURL(scriptResource)
    }

    // Support for support process substitution and direct scripts
    if (scriptFile == null && !scriptResource.endsWith(".kts") && !scriptResource.endsWith(".kt")) {
        val scriptText = if (File(scriptResource).canRead()) {
            File(scriptResource).readText().trim()

        } else {
            // the last resout is to assume the input to be a kotlin program
            var script = scriptResource.trim()

            //auto-prefix one-liners with kscript-support api
            if (numLines(script) == 1 && (script.startsWith("lines") || script.startsWith("stdin"))) {
                val prefix = """
                //DEPS com.github.holgerbrandl:kscript:1.2.2

                import kscript.text.*
                val lines = resolveArgFile(args)

                """.trimIndent()

                script = prefix + script
            }

            script.trim()
        }

        scriptFile = createTmpScript(scriptText)
    }


    // just proceed if the script file is a regular file at this point
    errorIf(scriptFile == null || !scriptFile.canRead()) {
        "Could not read script argument '$scriptResource'"
    }

    return scriptFile!!
}

private fun createTmpScript(scriptText: String): File {
    return File(createTempDir(), "scriptlet.${md5(scriptText)}.kts").apply {
        writeText(scriptText)
    }
}

fun fetchFromURL(scriptURL: String): File? {
    val urlHash = md5(scriptURL)
    val urlCache = File(KSCRIPT_CACHE_DIR, "/url_cache_${urlHash}.kts")

    if (!urlCache.isFile) {
        urlCache.writeText(URL(scriptURL).readText())
    }

    return urlCache
}


fun md5(byteProvider: () -> ByteArray): String {
    // from https://stackoverflow.com/questions/304268/getting-a-files-md5-checksum-in-java
    val md = MessageDigest.getInstance("MD5")
    md.update(byteProvider())

    val digestInHex = DatatypeConverter.printHexBinary(md.digest()).toLowerCase()

    return digestInHex.substring(0, 16)
}

fun md5(msg: String) = md5 { msg.toByteArray() }

fun md5(file: File) = md5 { Files.readAllBytes(Paths.get(file.toURI())) }


private fun numLines(str: String) =
        str.split("\r\n|\r|\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray().size

fun info(msg: String) = System.err.println(msg)