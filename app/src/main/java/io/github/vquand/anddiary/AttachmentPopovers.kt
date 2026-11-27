package io.github.vquand.anddiary

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor") // Constructed programmatically by InkSheet.
internal class StickerPickerPopover(
    context: Context,
    private val store: PaperItemsStore,
    private val onPick: (StickerSpec) -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val palette = DiarySettings.palette(context)
    private var selectedPackId: String? = null
    private var currentQuery: String = ""

    private val searchInput = EditText(context).apply {
        setHint(R.string.search_stickers_hint)
        setHintTextColor(palette.muted)
        setTextColor(palette.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        background = null
        setPadding(dp(8), dp(6), dp(4), dp(6))
    }
    private val clearSearchButton = TextView(context).apply {
        text = "×"
        setTextColor(palette.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        isClickable = true
        visibility = GONE
        setPadding(dp(8), 0, dp(8), 0)
    }
    private val packRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val tagRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val grid = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        isClickable = true
        elevation = 8f * density
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = cardBackground(density, palette)

        // Header + Search input
        addView(TextView(context).apply {
            setText(R.string.sticker)
            setTextColor(palette.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            paint.isFakeBoldText = true
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        val searchBox = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(palette.wash)
                setStroke(dp(1), palette.rule)
                cornerRadius = 8f * density
            }
            addView(searchInput, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(clearSearchButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(34)))
        }
        addView(searchBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        // Pack tabs (All, Capybara, Lop Rabbit, Classic, ...)
        val packScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(packRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(packScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })

        // Quick mood/tag chips
        val tagScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tagRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(tagScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        // Sticker results grid
        val scroll = ScrollView(context).apply {
            addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(250)))

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString().orEmpty()
                clearSearchButton.visibility = if (currentQuery.isNotEmpty()) VISIBLE else GONE
                rebuildTags()
                rebuildGrid()
            }
        })
        clearSearchButton.setOnClickListener {
            searchInput.setText("")
        }

        rebuildPacks()
        rebuildTags()
        rebuildGrid()
    }

    fun hideKeyboard() {
        searchInput.clearFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun rebuildPacks() {
        packRow.removeAllViews()
        addPackTab(null, context.getString(R.string.all_packs))
        for (pack in store.catalog.packs) {
            addPackTab(pack.id, pack.name)
        }
    }

    private fun addPackTab(packId: String?, label: String) {
        val selected = selectedPackId == packId
        val tab = TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (selected) palette.accent else palette.text)
            paint.isFakeBoldText = selected
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            isClickable = true
            background = GradientDrawable().apply {
                setColor(if (selected) palette.wash else palette.paper)
                setStroke(dp(1), if (selected) palette.accent else palette.rule)
                cornerRadius = 14f * density
            }
            setOnClickListener {
                selectedPackId = packId
                rebuildPacks()
                rebuildGrid()
            }
        }
        packRow.addView(tab, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(6)
        })
    }

    private fun rebuildTags() {
        tagRow.removeAllViews()
        val trimmed = currentQuery.trim().lowercase()
        for (tag in QUICK_TAGS) {
            val active = trimmed == tag
            val chip = TextView(context).apply {
                text = context.getString(R.string.quick_tag_format, tag)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(if (active) palette.accent else palette.muted)
                paint.isFakeBoldText = active
                setPadding(dp(8), dp(3), dp(8), dp(3))
                isClickable = true
                background = GradientDrawable().apply {
                    setColor(if (active) palette.wash else 0x00000000)
                    setStroke(dp(1), if (active) palette.accent else palette.rule)
                    cornerRadius = 10f * density
                }
                setOnClickListener {
                    if (active) {
                        searchInput.setText("")
                    } else {
                        selectedPackId = null
                        rebuildPacks()
                        searchInput.setText(tag)
                        searchInput.setSelection(tag.length)
                    }
                }
            }
            tagRow.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(5)
            })
        }
    }

    private fun rebuildGrid() {
        grid.removeAllViews()
        val results = store.catalog.filter(currentQuery, selectedPackId)
        if (results.isEmpty()) {
            grid.addView(TextView(context).apply {
                setText(R.string.no_stickers_found)
                setTextColor(palette.muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(28), dp(12), dp(28))
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            return
        }

        results.chunked(3).forEach { chunk ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.TOP
            }
            for (index in 0 until 3) {
                val spec = chunk.getOrNull(index)
                if (spec == null) {
                    row.addView(LinearLayout(context), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                } else {
                    val cell = LinearLayout(context).apply {
                        orientation = VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        isClickable = true
                        setPadding(dp(4), dp(6), dp(4), dp(6))
                        addView(ImageView(context).apply {
                            val cached = store.cachedStickerBitmap(spec.id)
                            if (cached != null) {
                                setImageBitmap(cached)
                            } else {
                                store.loadStickerBitmapAsync(spec.id) { bitmap -> setImageBitmap(bitmap) }
                            }
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            contentDescription = spec.description.ifBlank { spec.label }
                        }, LayoutParams(dp(48), dp(48)))
                        addView(TextView(context).apply {
                            text = spec.label
                            setTextColor(palette.text)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                            gravity = Gravity.CENTER
                            maxLines = 2
                        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(3)
                        })
                        setOnClickListener {
                            hideKeyboard()
                            onPick(spec)
                        }
                    }
                    row.addView(cell, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                }
            }
            grid.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun dp(value: Int) = (value * density).roundToInt()

    private companion object {
        val QUICK_TAGS = listOf("happy", "sleepy", "cozy", "reading", "love", "sad")
    }
}

@SuppressLint("ViewConstructor") // Constructed programmatically by InkSheet.
internal class MediaPickerPopover(
    context: Context,
    onPick: (PaperItemKind) -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private var palette = DiarySettings.palette(context)
    private val optionViews = mutableListOf<TextView>()

    init {
        orientation = VERTICAL
        isClickable = true
        elevation = 8f * density
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = cardBackground(density, palette)

        addOption(R.string.add_photo, PaperItemKind.PHOTO, onPick)
        addOption(R.string.add_video, PaperItemKind.VIDEO, onPick)
        addOption(R.string.add_audio, PaperItemKind.AUDIO, onPick)
    }

    fun applyPalette(next: DiaryPalette) {
        palette = next
        background = cardBackground(density, palette)
        for (option in optionViews) option.setTextColor(palette.text)
    }

    private fun addOption(labelRes: Int, kind: PaperItemKind, onPick: (PaperItemKind) -> Unit) {
        val option = TextView(context).apply {
            setText(labelRes)
            setTextColor(palette.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(44)
            setPadding(dp(14), 0, dp(22), 0)
            isClickable = true
            setOnClickListener { onPick(kind) }
        }
        optionViews.add(option)
        addView(option, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun dp(value: Int) = (value * density).roundToInt()
}

private fun cardBackground(density: Float, palette: DiaryPalette) = GradientDrawable().apply {
    setColor(palette.paper)
    setStroke((1f * density).roundToInt(), palette.rule)
    cornerRadius = 10f * density
}
