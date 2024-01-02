package com.github.corruptedinc.corruptedmainframe.utils

import com.github.corruptedinc.corruptedmainframe.discord.Bot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.*

// TODO: variable field dimensions
class PathDrawer(private val bot: Bot) {
    private val tmpDir: Path = Files.createTempDirectory("svg")
    private val resvg: Path
    private val fontFile: Path

    init {
        val inp = this::class.java.classLoader.getResourceAsStream("resvg")!!
        val x = PosixFilePermissions.asFileAttribute(setOf(PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
        val fi = tmpDir.resolve("resvg").createFile(x).toFile()
        fi.outputStream().use {
            inp.copyTo(it)
        }
        inp.close()
        resvg = fi.toPath().toAbsolutePath()

        val inp2 = this::class.java.classLoader.getResourceAsStream("DejaVuSans.ttf")!!
        val fi2 = tmpDir.resolve("DejaVuSans.ttf").createFile().toFile()
        fi2.outputStream().use {
            inp2.copyTo(it)
        }
        inp2.close()
        fontFile = fi2.toPath().toAbsolutePath()

        // clean up temp dir
        Runtime.getRuntime().addShutdownHook(Thread {
            deleteDirectory(tmpDir)
        })
    }

    suspend fun render(svg: StringBuilder): ByteArray? {
        val start = System.currentTimeMillis()
        val file = tmpDir.resolve("file.svg")
        file.writeText(svg)
        val output = tmpDir.resolve("file.png")
        val stdErr: String
        val exitCode = withContext(Dispatchers.IO) {
            Runtime.getRuntime().exec(arrayOf(resvg.toString(), "--skip-system-fonts", "--use-font-file", "$fontFile", "--sans-serif-family", "DejaVu Sans", file.absolutePathString(), output.absolutePathString()))
                .apply { errorStream.readAllBytes().decodeToString().apply { stdErr = this } }
                .waitFor()
        }

        if (exitCode != 0) {
            bot.log.error("SVG error: $stdErr")
            return null
        }

        val read = output.readBytes()
        output.deleteIfExists()
        file.deleteIfExists()
        bot.log.trace("rendering SVG took ${System.currentTimeMillis() - start}ms")
        return read
    }

    private suspend fun drawPath(pathData: List<String>, width: Int, height: Int, backgroundSVG: String?, color: RGB, xInverted: Boolean, yInverted: Boolean): ByteArray? {
        val out = StringBuilder()

        val viewboxW = 16.4592
        val viewboxH = 8.2296

        val bg = backgroundSVG?.replace("%width%", viewboxW.toString())?.replace("%height%", viewboxH.toString())

        out.append("""
            <!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">
            <svg  width="$width" height="$height" viewBox="0 0 16.4592 8.2296" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
                ${bg ?: ""}
        """.trimIndent())

        val c = "#%02X%02X%02X".format(color.r.toInt(), color.g.toInt(), color.b.toInt())

        for (item in pathData) {
            out.append("<path transform=\"scale(0.3048, 0.3048)\" d=\"$item\" stroke=\"$c\" stroke-width=\"0.075\" fill=\"transparent\"/>")
            val startPos = "M(\\d+(\\.\\d+)?) (\\d+(\\.\\d+)?)".toRegex().find(item.take(50))
            if (startPos != null) {
                val x = startPos.groups[1]!!.value.toDouble() * 0.3048
                val y = startPos.groups[3]!!.value.toDouble() * 0.3048
                out.append("<circle cx=\"$x\" cy=\"$y\" r=\"0.15\" stroke=\"black\" stroke-width=\"0.05\" fill=\"white\"/>")
            }
        }

        out.append("</svg>")

        return render(out)
    }

    fun robotPathData(x: DoubleArray, y: DoubleArray): StringBuilder {
        require(x.size == y.size)

        val out = StringBuilder()

        if (x.isNotEmpty()) {
            out.append('M')
            out.append(x[0])
            out.append(' ')
            out.append(27.0 - y[0])
            out.append(' ')
            out.append('S')

            for (i in 1 until x.size) {
                if (i != 1) out.append(',')
                out.append(' ')
                out.append(x[i])
                out.append(' ')
                out.append(27.0 - y[i])
            }
        }

        return out
    }

    suspend fun robotPath(x: DoubleArray, y: DoubleArray, year: Int, color: RGB): ByteArray {
        val data = robotPathData(x, y)

        return robotPaths(listOf(data.toString()), year, color)
    }

    suspend fun robotPaths(data: List<String>, year: Int, color: RGB, xInverted: Boolean = false, yInverted: Boolean = false): ByteArray {
        val background = withContext(Dispatchers.IO) {
            this::class.java.classLoader.getResourceAsStream("fields/$year.svg")?.readAllBytes()?.decodeToString()
        }

        val width = 1920
        val height = 1920 / 2

        return drawPath(data, width, height, background, color, xInverted, yInverted)!!
    }
}
