package io.github.vquand.anddiary

import android.content.Context
import android.util.AtomicFile
import androidx.ink.brush.Brush
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDate

internal class InkEntry(
    context: Context,
    val date: LocalDate,
    private val brushes: DiaryBrushes,
) {
    private val file = AtomicFile(File(context.filesDir, "entries/$date.ink"))

    fun read(): List<Stroke> {
        val raw = try {
            file.readFully()
        } catch (error: FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return emptyList()
        }

        return decodeRecords(raw).map { record ->
            val family = when (record.brushKind) {
                PEN -> brushes.penFamily
                SOFT_BRUSH -> brushes.softBrushFamily
                else -> throw IOException("Unknown diary brush")
            }
            val brush = Brush.createWithColorIntArgb(family, record.colorArgb, record.size, record.epsilon)
            Stroke(brush, StrokeInputBatch.decode(record.payload))
        }
    }

    fun write(strokes: List<Stroke>) {
        if (strokes.isEmpty()) {
            file.delete()
            if (file.baseFile.exists()) throw IOException("Could not remove empty diary entry")
            return
        }

        val records = strokes.map { stroke ->
            val brush = stroke.brush
            RawStrokeRecord(
                brushKind = if (brush.family == brushes.softBrushFamily) SOFT_BRUSH else PEN,
                colorArgb = brush.colorIntArgb,
                size = brush.size,
                epsilon = brush.epsilon,
                payload = stroke.inputs.encode(),
            )
        }
        val stream = file.startWrite()
        try {
            val output = DataOutputStream(stream.buffered())
            encodeRecords(output, records)
            output.flush()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    internal data class RawStrokeRecord(
        val brushKind: Int,
        val colorArgb: Int,
        val size: Float,
        val epsilon: Float,
        val payload: ByteArray,
    )

    internal companion object {
        const val FORMAT = 0x41444931
        const val RECORD_HEADER_SIZE = 17
        const val PEN = 0
        const val SOFT_BRUSH = 1

        fun decodeRecords(raw: ByteArray): List<RawStrokeRecord> =
            DataInputStream(ByteArrayInputStream(raw)).use { input ->
                if (input.readInt() != FORMAT) throw IOException("Unsupported diary ink format")
                val count = input.readInt()
                if (count <= 0 || count > input.available() / RECORD_HEADER_SIZE) {
                    throw IOException("Invalid diary stroke count")
                }
                List(count) {
                    val brushKind = input.readUnsignedByte()
                    if (brushKind != PEN && brushKind != SOFT_BRUSH) {
                        throw IOException("Unknown diary brush")
                    }
                    val colorArgb = input.readInt()
                    val size = input.readFloat()
                    val epsilon = input.readFloat()
                    val payloadSize = input.readInt()
                    if (payloadSize <= 0 || payloadSize > input.available()) {
                        throw IOException("Invalid diary stroke size")
                    }
                    val payload = ByteArray(payloadSize).also(input::readFully)
                    RawStrokeRecord(brushKind, colorArgb, size, epsilon, payload)
                }.also {
                    if (input.read() != -1) throw IOException("Unexpected data in diary entry")
                }
            }

        fun encodeRecords(output: DataOutputStream, records: List<RawStrokeRecord>) {
            output.writeInt(FORMAT)
            output.writeInt(records.size)
            for (record in records) {
                output.writeByte(record.brushKind)
                output.writeInt(record.colorArgb)
                output.writeFloat(record.size)
                output.writeFloat(record.epsilon)
                output.writeInt(record.payload.size)
                output.write(record.payload)
            }
        }
    }
}
