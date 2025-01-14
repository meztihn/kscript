package kscript.app.resolver

import kscript.app.model.*
import kscript.app.parser.LineParser.extractValues
import kscript.app.util.ScriptUtils
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.net.URL

class ScriptResolver(
    private val sectionResolver: SectionResolver,
    private val contentResolver: ContentResolver,
    private val kotlinOptsEnvVariable: String = ""
) {
    private val kotlinExtensions = listOf("kts", "kt")
    private val scripletName = "scriplet"

    //level parameter - for how many levels should include be resolved
    //level 0       -   do not resolve includes in base file and any other embedded
    //level 1 to n  -   resolve includes up to respective level (1 is a base script)
    //level Int.Max -   full resolution (default)
    fun resolve(
        string: String, preambles: List<String> = emptyList(), maxResolutionLevel: Int = Int.MAX_VALUE
    ): Script {
        //Is it stdin?
        if (string == "-" || string == "/dev/stdin") {
            // we need to keep track of the scripts dir or the working dir in case of stdin script to correctly resolve includes
            val scriptText = ScriptUtils.prependPreambles(preambles, generateSequence { readLine() }.joinToString("\n"))
            val scriptType = ScriptUtils.resolveScriptType(scriptText)

            return createScript(
                ScriptSource.STD_INPUT,
                scriptType,
                null,
                File(".").toURI(),
                scripletName + scriptType.extension,
                scriptText,
                true,
                maxResolutionLevel
            )
        }

        //Is it a URL?
        if (ScriptUtils.isUrl(string)) {
            val content = contentResolver.resolve(URL(string))
            val scriptText = ScriptUtils.prependPreambles(preambles, content.text)

            return createScript(
                ScriptSource.HTTP,
                content.scriptType,
                content.uri,
                content.contextUri,
                content.fileName,
                scriptText,
                false,
                maxResolutionLevel
            )
        }

        val file = File(string)
        if (file.canRead()) {
            if (kotlinExtensions.contains(file.extension)) {
                //Regular file
                val content = contentResolver.resolve(file.toPath())
                val scriptText = ScriptUtils.prependPreambles(preambles, content.text)

                return createScript(
                    ScriptSource.FILE,
                    content.scriptType,
                    content.uri,
                    content.contextUri,
                    content.fileName,
                    scriptText,
                    true,
                    maxResolutionLevel
                )
            } else {
                //If script input is a process substitution file handle we can not use for content reading:
                //FileInputStream(this).bufferedReader().use{ readText() } nor readText()
                val uri = file.toURI()
                val includeContext = uri.resolve(".")

                val scriptText =
                    ScriptUtils.prependPreambles(preambles, FileInputStream(file).bufferedReader().readText())

                val scriptType = ScriptUtils.resolveScriptType(scriptText)
                return createScript(
                    ScriptSource.OTHER_FILE,
                    scriptType,
                    uri,
                    includeContext,
                    scripletName + scriptType.extension,
                    scriptText,
                    true,
                    maxResolutionLevel
                )
            }
        }

        if (kotlinExtensions.contains(file.extension)) {
            throw IllegalStateException("Could not read script from '$string'")
        }

        //As a last resort we assume that input is a Kotlin program...
        val scriptText = ScriptUtils.prependPreambles(preambles, string)
        val scriptType = ScriptUtils.resolveScriptType(scriptText)

        return createScript(
            ScriptSource.PARAMETER,
            scriptType,
            null,
            File(".").toURI(),
            scripletName + scriptType.extension,
            scriptText,
            true,
            maxResolutionLevel
        )
    }

    private fun createScript(
        scriptSource: ScriptSource,
        scriptType: ScriptType,
        sourceUri: URI?,
        sourceContextUri: URI,
        scriptName: String,
        scriptText: String,
        allowLocalReferences: Boolean,
        maxResolutionLevel: Int
    ): Script {
        val level = 0
        val resolutionContext = ResolutionContext()
        val sections = sectionResolver.resolve(
            scriptText, sourceContextUri, allowLocalReferences, level, maxResolutionLevel, resolutionContext
        )

        val scriptNode = ScriptNode(level, scriptSource, scriptType, sourceUri, sourceContextUri, scriptName, sections)
        resolutionContext.scriptNodes.add(scriptNode)
        resolutionContext.packageName = resolutionContext.packageName ?: PackageName("kscript.scriplet")

        val code = ScriptUtils.resolveCode(resolutionContext.packageName, resolutionContext.importNames, scriptNode)

        if (kotlinOptsEnvVariable.isNotBlank()) {
            extractValues(kotlinOptsEnvVariable).map { KotlinOpt(it) }.forEach {
                resolutionContext.kotlinOpts.add(it)
            }
        }

        val digest = ScriptUtils.calculateHash(code, resolutionContext)

        return Script(
            scriptSource,
            scriptType,
            sourceUri,
            sourceContextUri,
            scriptName,
            code,
            resolutionContext.packageName!!,
            resolutionContext.entryPoint,
            resolutionContext.importNames,
            resolutionContext.includes,
            resolutionContext.dependencies,
            resolutionContext.repositories,
            resolutionContext.kotlinOpts,
            resolutionContext.compilerOpts,
            resolutionContext.scriptNodes,
            scriptNode,
            digest
        )
    }
}
