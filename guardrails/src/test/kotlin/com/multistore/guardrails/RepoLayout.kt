package com.multistore.guardrails

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Access to the repository tree from the guardrail tests.
 *
 * The root arrives from the `multistore.repoRoot` system property, set by the build file: deriving it
 * from `user.dir` would give different results depending on how Gradle is launched.
 */
object RepoLayout {

    val root: File by lazy {
        val path = System.getProperty("multistore.repoRoot")
            ?: error(
                "System property 'multistore.repoRoot' is missing. " +
                    "The :guardrails tests must be run through Gradle.",
            )
        File(path).also {
            require(it.isDirectory) { "Repo root does not exist: $path" }
            require(File(it, "settings.gradle.kts").isFile) {
                "$path does not look like the repo root: settings.gradle.kts is missing"
            }
        }
    }

    /** Directories excluded from the scan: build outputs and metadata. */
    private val IGNORED_DIRECTORIES = setOf("build", ".git", ".gradle", ".idea", ".kotlin")

    /** `values`, `values-it`, `values-night`, `values-sw600dp`… */
    private val VALUES_DIRECTORY = Regex("""^values(-.+)?$""")

    /** Every file under the root, skipping build outputs. */
    fun walkSources(): Sequence<File> = root.walkTopDown()
        .onEnter { it.name !in IGNORED_DIRECTORIES }
        .filter(File::isFile)

    /**
     * Every `res` folder in the repository, identified by its `values*` subfolders.
     *
     * The unit of scanning is the **folder**, not the `strings.xml` file. That is not a detail:
     * Android merges every `.xml` in a `values*` folder into a single resource set, so searching by
     * file name would make a `plurals.xml` or a `strings_search.xml` invisible to this guardrail —
     * that is, precisely the files that appear when the strings grow.
     */
    fun resourceDirectories(): List<File> = root.walkTopDown()
        .onEnter { it.name !in IGNORED_DIRECTORIES }
        .filter { it.isDirectory && VALUES_DIRECTORY.matches(it.name) }
        .mapNotNull { it.parentFile }
        .distinct()
        .sortedBy { it.invariantSeparatorsPath }
        .toList()

    /** Every XML of one qualifier, in stable order. Empty if the qualifier does not exist. */
    fun resourceFiles(resDir: File, qualifier: String): List<File> =
        File(resDir, qualifier)
            .listFiles { file: File -> file.isFile && file.extension == "xml" }
            ?.sortedBy(File::getName)
            .orEmpty()

    fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    fun parseXml(file: File): Document =
        DocumentBuilderFactory.newInstance()
            .apply {
                isNamespaceAware = false
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            .newDocumentBuilder()
            .parse(file)

    fun Document.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }
}
