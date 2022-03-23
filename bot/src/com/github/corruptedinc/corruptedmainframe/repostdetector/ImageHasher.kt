package com.github.corruptedinc.corruptedmainframe.repostdetector

import dev.brachtendorf.jimagehash.hashAlgorithms.PerceptiveHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.IOException
import java.net.URL
import javax.imageio.ImageIO
import javax.imageio.ImageReader

class ImageHasher {
    private val hasher = PerceptiveHash(64)

    // adapted from https://medium.com/chargebee-engineering/perils-of-parsing-pixel-flood-attack-on-java-imageio-a97aeb06637d
    private suspend fun loadImage(url: URL): BufferedImage? {
        return withContext(Dispatchers.IO) {
            try {
                val inp = ImageIO.createImageInputStream(url.openStream())
                val iter = ImageIO.getImageReaders(inp)
                val maxSize = 3840L * 2160L

                if (!iter.hasNext()) {
                    inp.close()
                    return@withContext null
                }

                val reader = iter.next() as ImageReader
                reader.setInput(inp, true, true)
                val width = reader.getWidth(0)
                val height = reader.getWidth(0)

                if (width * height > maxSize) {
                    inp.close()
                    return@withContext  null
                }
                return@withContext reader.read(0)
            } catch (ignored: IOException) {
                return@withContext null
            } catch (ignored: IndexOutOfBoundsException) {
                return@withContext null
            }
        }
    }

    // adapted from https://www.geeksforgeeks.org/hamming-distance-between-two-integers/
    private fun hammingDistance(n1: ULong, n2: ULong): Int {
        var x = n1 xor n2
        var setBits = 0UL
        while (x > 0U) {
            setBits += x and 1U
            x = x shr 1
        }
        return setBits.toInt()
    }

    suspend fun hash(url: String): ULong? {
        val u = URL(url)
        val img = loadImage(u) ?: return null
        synchronized(hasher) {
            return hasher.hash(img).hashValue.toLong().toULong()
        }
    }

    suspend fun distance(url: String, hash: ULong): Int? {
        val imgHash = hash(url) ?: return null
        return hammingDistance(imgHash, hash)
    }

    suspend fun similar(url: String, hash: ULong, threshold: Int = 10): Boolean? {
        return (distance(url, hash) ?: return null) < threshold
    }
}
