package com.deepfaceswap.android

import java.io.File

/**
 * Removes EXIF from a JPEG, losslessly.
 *
 * A photo carries its GPS coordinates, the capture time and often the phone's
 * serial number. An app that promises "nothing leaves your phone" should not
 * then hand back a file that says where you were standing.
 *
 * This strips the metadata segments rather than decoding and re-encoding, so the
 * pixels are untouched - re-encoding would have cost quality to gain privacy,
 * which is a bad trade.
 *
 * Only JPEG carries this; PNG and WebP are left alone, and a file that is not a
 * JPEG is returned unchanged rather than risk corrupting it.
 */
object ExifStrip {

    /** Rewrite [file] without its metadata. Returns true when something changed. */
    fun strip(file: File): Boolean {
        if (!file.isFile) return false
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return false
        if (raw.size < 4 || raw[0] != 0xFF.toByte() || raw[1] != 0xD8.toByte()) return false

        val out = java.io.ByteArrayOutputStream(raw.size)
        out.write(raw, 0, 2) // SOI
        var i = 2
        var removed = false
        while (i + 4 <= raw.size) {
            if (raw[i] != 0xFF.toByte()) break
            val marker = raw[i + 1].toInt() and 0xFF
            // Start of scan / end of image: everything after is pixel data.
            if (marker == 0xDA || marker == 0xD9) {
                out.write(raw, i, raw.size - i)
                i = raw.size
                break
            }
            val length = ((raw[i + 2].toInt() and 0xFF) shl 8) or (raw[i + 3].toInt() and 0xFF)
            if (length < 2 || i + 2 + length > raw.size) break
            // APP1 is where EXIF lives; APP13 is Photoshop IRB, also metadata.
            val isMeta = marker == 0xE1 || marker == 0xED
            if (isMeta) {
                removed = true
            } else {
                out.write(raw, i, 2 + length)
            }
            i += 2 + length
        }
        if (i < raw.size && out.size() > 2 && removed) out.write(raw, i, raw.size - i)

        if (!removed) return false
        val cleaned = out.toByteArray()
        return runCatching {
            file.writeBytes(cleaned)
            true
        }.getOrDefault(false)
    }
}
