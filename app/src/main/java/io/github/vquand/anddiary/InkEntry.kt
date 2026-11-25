package io.github.vquand.anddiary

import android.content.Context
import android.util.AtomicFile
import androidx.ink.brush.Brush
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDate

internal class InkEntry(context: Context, date: LocalDate, private val brushes: DiaryBrushes) {
    private val file = AtomicFile(File(context.filesDir, "entries/$date.ink"))

    fun read(): List<Stroke> {
        val stream = try {
            file.openRead()
        } catch (error: FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return emptyList()
        }

        return DataInputStream(stream.buffered()).use { input ->
            if (input.readInt() != FORMAT) throw IOException("Unsupported diary ink format")
            val count = input.readInt()
            if (count <= 0 || count > input.available() / RECORD_HEADER_SIZE) {
                throw IOException("Invalid diary stroke count")
            }
            List(count) {
                val family = when (input.readUnsignedByte()) {
                    PEN -> brushes.pen.family
                    SOFT_BRUSH -> brushes.softBrush.family
                    else -> throw IOException("Unknown diary brush")
                }
                val brush = Brush.createWithColorIntArgb(family, input.readInt(), input.readFloat(), input.readFloat())
                val size = input.readInt()
                if (size <= 0 || size > input.available()) throw IOException("Invalid diary stroke size")
                val bytes = ByteArray(size).also(input::readFully)
                Stroke(brush, StrokeInputBatch.decode(bytes))
            }.also {
                if (input.read() != -1) throw IOException("Unexpected data in diary entry")
            }
        }
    }

    fun write(strokes: List<Stroke>) {
        if (strokes.isEmpty()) {
            file.delete()
            if (file.baseFile.exists()) throw IOException("Could not remove empty diary entry")
            return
        }

        val stream = file.startWrite()
        try {
            val output = DataOutputStream(stream.buffered())
            output.writeInt(FORMAT)
            output.writeInt(strokes.size)
            for (stroke in strokes) {
                val brush = stroke.brush
                output.writeByte(if (brush.family == brushes.softBrush.family) SOFT_BRUSH else PEN)
                output.writeInt(brush.colorIntArgb)
                output.writeFloat(brush.size)
                output.writeFloat(brush.epsilon)
                val encoded = stroke.inputs.encode()
                output.writeInt(encoded.size)
                output.write(encoded)
            }
            output.flush()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val FORMAT = 0x41444931
        const val RECORD_HEADER_SIZE = 17
        const val PEN = 0
        const val SOFT_BRUSH = 1
    }
}
