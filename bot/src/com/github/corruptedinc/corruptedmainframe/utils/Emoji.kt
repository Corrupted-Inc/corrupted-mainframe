package com.github.corruptedinc.corruptedmainframe.utils

import java.io.DataInputStream
import java.util.zip.InflaterInputStream

/**
 * Fast index of all valid emoji.
 */
class Emoji {
    private val hashSet: HashSet<String> = hashSetOf()

    init {
        // code to generate bot/resources/emoji
//        val request = Request.Builder().url("https://www.unicode.org/Public/emoji/14.0/emoji-test.txt").get().build()
//        val lines = OkHttpClient().newCall(request).execute().body()!!.string().lines().filter { it.isNotBlank() && !it.startsWith('#') }
//        val codepoints = lines.map { it.substringBefore(';').trimEnd() }
//        val asUTF8 = codepoints.map { val code = it.split(' ').map { c -> c.toInt(16) }.toIntArray(); String(code, 0, code.size) } + (0x1F1E6..0x1F1FF).map { String(intArrayOf(it), 0, 1) }
//        val output = File("/tmp/unicode").outputStream()
//        val zip = DeflaterOutputStream(output)
//        val data = DataOutputStream(zip)
//        data.writeInt(asUTF8.size)
//        for (u in asUTF8) {
//            data.writeByte(u.length)
//            for (c in u) {
//                data.writeChar(c.code)
//            }
//        }
//        data.close()
//        zip.close()
//        output.close()

        this::class.java.classLoader.getResourceAsStream("emoji").use { fi ->
            InflaterInputStream(fi).use { inf ->
                DataInputStream(inf).use { dataIn ->
                    val builder = StringBuilder()
                    repeat(dataIn.readInt()) {
                        builder.clear()
                        repeat(dataIn.readByte().toInt()) {
                            builder.append(dataIn.readChar())
                        }
                        hashSet.add(builder.toString())
                    }
                }
            }
        }
    }

    // despite its simplicity, in my testing this runs in under 20 nanoseconds
    fun isValid(inp: String): Boolean {
        return inp in hashSet
    }
}
