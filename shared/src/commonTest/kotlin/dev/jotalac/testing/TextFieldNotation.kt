package dev.jotalac.testing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/*
* Text field values written the way they look in the editor, so tests read like what the user sees:
*   `|`     the cursor                  "- milk|"
*   `«` `»` around a selection           "«hello» world"
* ([ ] are not used as markers because markdown checkboxes and links need them)
* */

/** Parses the notation, [reversed] makes a selection that was made backwards (e.g. shift + left arrow). */
fun fieldOf(notation: String, reversed: Boolean = false): TextFieldValue {
    val cursor = notation.indexOf('|')
    if (cursor != -1) {
        return TextFieldValue(notation.removeRange(cursor, cursor + 1), TextRange(cursor))
    }

    val start = notation.indexOf('«')
    val end = notation.indexOf('»') - 1
    require(start != -1 && end >= start) { "mark the cursor with | or the selection with « »: $notation" }

    val text = notation.replace("«", "").replace("»", "")
    return TextFieldValue(text, if (reversed) TextRange(end, start) else TextRange(start, end))
}

/** The value written back in the same notation, to compare it with the expected one. */
fun TextFieldValue.notation(): String = if (selection.collapsed) {
    text.substring(0, selection.start) + "|" + text.substring(selection.start)
} else {
    text.substring(0, selection.min) + "«" + text.substring(selection.min, selection.max) + "»" +
        text.substring(selection.max)
}
