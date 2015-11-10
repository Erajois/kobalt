package com.beust.kobalt.wrapper

import com.beust.kobalt.JavaInfo
import com.beust.kobalt.SystemProperties
import com.beust.kobalt.api.Kobalt
import com.beust.kobalt.maven.Http
import com.beust.kobalt.misc.KFiles
import com.beust.kobalt.misc.KobaltWrapperProperties
import com.beust.kobalt.misc.benchmark
import com.beust.kobalt.misc.log
import com.google.inject.Inject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.zip.ZipFile

public fun main(argv: Array<String>) {
    Kobalt.INJECTOR.getInstance(Wrapper::class.java).installAndLaunchMain(argv)
}

/**
 * Download and install a new wrapper if requested.
 */
public class Wrapper @Inject constructor(val wrapperProperties: KobaltWrapperProperties){
    // kobalt.properties
    private val KOBALT_PROPERTIES = "kobalt.properties"
    private val KOBALTW = "kobaltw"

    val FILE_NAME = "kobalt"

    private val properties = Properties()

    public fun installAndLaunchMain(argv: Array<String>) {
        val kobaltJarFile = install()
        launchMain(kobaltJarFile, argv)
    }

    private fun readProperties(properties: Properties, ins: InputStream) {
        properties.load(ins)
        ins.close()
        properties.forEach { es -> System.setProperty(es.key.toString(), es.value.toString()) }
    }

    /**
     * Attemps to read kobalt.properties (which should always exist).
     */
    private fun maybeCreateKobaltProperties() : Properties {
        val result = Properties()

        // kobalt.properties is internal to Kobalt
        val url = javaClass.classLoader.getResource(KOBALT_PROPERTIES)
        if (url != null) {
            readProperties(result, url.openConnection().inputStream)
        } else {
            throw IllegalArgumentException("Couldn't find $KOBALT_PROPERTIES")
        }

        return result
    }

//    private fun initWrapperFile(version: String) {
//        val config = wrapperProperties.file
//        if (! config.exists()) {
//            wrapperProperties.create(version)
//        }
//        properties.load(FileReader(config))
//    }
//
//    private val wrapperVersion : String
//        get() {
//            return properties.getProperty(PROPERTY_VERSION)
//        }
//
//    private val wrapperDownloadUrl : String
//        get() {
//            return properties.getProperty(PROPERTY_DOWNLOAD_URL)
//        }
//
//    fun urlFor(version: String) : String {
//        //        val URL = "https://dl.bintray.com/cbeust/generic/"
//        //        return "https://dl.bintray.com/cbeust/generic/$fileName-$version.zip"
//        return wrapperDownloadUrl ?: defaultUrlFor(version)
//    }

    /**
     * Install a new version if requested in .kobalt/wrapper/kobalt-wrapper.properties
     *
     * @return the path to the Kobalt jar file
     */
    public fun install() : Path {
        val properties = maybeCreateKobaltProperties()
        val version = properties.getProperty("kobalt.version")
        wrapperProperties.maybeCreate(version)
        log(2, "Wrapper version: ${wrapperProperties.version}")

        val wrapperVersion = wrapperProperties.version
        val fileName = "$FILE_NAME-$wrapperVersion.zip"
        File(KFiles.distributionsDir).mkdirs()
        val localZipFile = Paths.get(KFiles.distributionsDir, fileName)
        val zipOutputDir = KFiles.distributionsDir + "/" + wrapperVersion
        val kobaltJarFile = Paths.get(zipOutputDir, "kobalt/wrapper/$FILE_NAME-$wrapperVersion.jar")
        if (!Files.exists(localZipFile) || !Files.exists(kobaltJarFile)) {
            log(1, "Downloading $fileName")
            val fullUrl = wrapperProperties.downloadUrl
            val body = Http().get(fullUrl)
            if (body.code == 200) {
                if (!Files.exists(localZipFile)) {
                    val target = localZipFile.toAbsolutePath()
                    val ins = body.getAsStream()
                    benchmark("Download .zip file") {
                        // This takes about eight seconds for a 21M file because of the extra copying, not good.
                        // Should use Okio.sink(file) to create a Sink and then call readAll(fileSink) on
                        // the BufferedSource returned in the ResponseBody
                        Files.copy(ins, target)
                    }
                    log(2, "$localZipFile downloaded, extracting it")
                } else {
                    log(2, "$localZipFile already exists, extracting it")
                }

                //
                // Extract all the zip files
                //
                val zipFile = ZipFile(localZipFile.toFile())
                val entries = zipFile.entries()
                val outputDirectory = File(KFiles.distributionsDir)
                outputDirectory.mkdirs()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val entryFile = File(entry.name)
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        val dest = Paths.get(zipOutputDir, entryFile.path)
                        log(2, "  Writing ${entry.name} to $dest")
                        Files.createDirectories(dest.parent)
                        Files.copy(zipFile.getInputStream(entry),
                                dest,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                log(2, "$localZipFile extracted")
            } else {
                error("Couldn't download $fullUrl")
            }
        }

        //
        // Copy the wrapper files in the current kobalt/wrapper directory
        //
        log(2, "Copying the wrapper files...")
        arrayListOf(KOBALTW, "kobalt/wrapper/$FILE_NAME-wrapper.jar").forEach {
            val from = Paths.get(zipOutputDir, it)
            val to = Paths.get(File(".").absolutePath, it)
            KFiles.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        File(KOBALTW).setExecutable(true)

        return kobaltJarFile
    }

    /**
     * Launch kobalt-xxx.jar
     *
     * Note: currently launching it in a separate VM because both this jar file and the wrapper contain
     * the same classes, so the old classes will be run. Once wrapper.jar contains only the
     * wrapper class and nothing else from the Kobalt distribution, we can just invoke main from the same JVM here,
     * which will speed up the start up
     */
    private fun launchMain(kobaltJarFile: Path, argv: Array<String>) {
        val jvm = JavaInfo.create(File(SystemProperties.javaBase))
        val java = jvm.javaExecutable

        val args = arrayListOf(
                java!!.absolutePath,
                "-jar", kobaltJarFile.toFile().absolutePath)
        args.addAll(argv)
        val pb = ProcessBuilder(args)
        pb.inheritIO()
        log(2, "Launching\n    ${args.joinToString(" ")}")
        val process = pb.start()
        process.waitFor()
    }
}
