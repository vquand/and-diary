# Diary `.ink` Binary Format

Each dated sheet stores its dry strokes in `entries/<YYYY-MM-DD>.ink` inside app-private storage. All multi-byte integers and floats are big-endian (`java.io.DataInputStream` / `java.io.DataOutputStream`).

## File layout

| Offset | Size | Type | Description |
| --- | --- | --- | --- |
| 0 | 4 bytes | `int32` | Magic/version header `FORMAT = 0x41444931` (`"ADI1"`) |
| 4 | 4 bytes | `int32` | Stroke record count (`> 0`; empty sheets delete the `.ink` file) |
| 8 | variable | `Record[count]` | Sequential stroke records |

## Stroke record layout

Each record has a 17-byte header followed by the encoded `StrokeInputBatch` payload (`androidx.ink.storage.encode` / `decode`):

| Field | Size | Type | Description |
| --- | --- | --- | --- |
| `brushKind` | 1 byte | `uint8` | Stable brush storage code (see table below) |
| `colorArgb` | 4 bytes | `int32` | Ink color in ARGB format |
| `size` | 4 bytes | `float32` | Brush size in pixels |
| `epsilon` | 4 bytes | `float32` | Geometry tessellation epsilon |
| `payloadLength` | 4 bytes | `int32` | Byte length of `payload` (`> 0`) |
| `payload` | `payloadLength` bytes | `byte[]` | `StrokeInputBatch` encoded bytes |

`decodeRecords` rejects files with an unknown `FORMAT`, invalid counts, truncated/trailing bytes, or any `brushKind` code not listed below.

## Brush kind codes

Codes `0` and `1` are built-in toolbar tools; codes `16+` are brush bank entries defined in `BrushBank.all`. Codes are permanent and must never be reused.

| Code (`uint8`) | Brush ID | Notes |
| --- | --- | --- |
| `0` | `pen` | Built-in pressure pen (`StockBrushes.pressurePen()`) |
| `1` | `soft-brush` | Built-in soft brush (`DiaryBrushes.softBrushFamily`) |
| `16` | `pencil` | Fine graphite tooth with paper grain texture |
| `17` | `chinese-ink-brush` | Expressive tapered ink hair brush |
| `18` | `calligraphy-quill` | Fixed 40° broad chisel nib |
| `19` | `airbrush` | Wide low-opacity soft spray |
| `20` | `charcoal` | Coarse grainy charcoal stick with jitter |

## Compatibility

- **Forward compatibility**: Builds from before the brush bank reject `.ink` files that contain brush codes `16`–`20` (`IOException("Unknown diary brush")`).
- **Backward compatibility**: Old `.ink` files (using codes `0` and `1`) continue to load normally in new builds.
