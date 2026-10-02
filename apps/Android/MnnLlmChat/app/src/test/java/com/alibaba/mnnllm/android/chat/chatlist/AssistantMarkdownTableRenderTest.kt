// Modified by MNN Chat API contributors, 2026: test the current row-based table renderer.
package com.alibaba.mnnllm.android.chat.chatlist

import android.graphics.Typeface
import android.os.Looper
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantMarkdownTableRenderTest {

    @Test
    fun `assistant markdown should render gfm tables as scrollable rows and cells`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        val itemView = LayoutInflater.from(activity)
            .inflate(R.layout.item_holder_assistant, FrameLayout(activity), false)
        val holder = ChatViewHolders.AssistantViewHolder(itemView)
        val data = ChatDataItem(ChatViewHolders.ASSISTANT).apply {
            displayText = """
                | Model | Params |
                | --- | --- |
                | Qwen3 30B-A3B | 30B |
                | GPT-OSS 20B | 20B |
            """.trimIndent()
        }

        holder.bind(data, "Qwen", null)
        shadowOf(Looper.getMainLooper()).idle()

        val chatText = itemView.findViewById<MarkdownMessageView>(R.id.tv_chat_text)
        assertEquals("A table is a single scrollable markdown block", 1, chatText.childCount)
        val scroll = chatText.getChildAt(0) as HorizontalScrollView
        assertTrue(scroll.isHorizontalScrollBarEnabled)
        val table = scroll.getChildAt(0) as MarkdownTableView
        assertEquals("Header plus two data rows; the delimiter is not a data row", 3, table.childCount)
        val expected = listOf(
            listOf("Model", "Params"),
            listOf("Qwen3 30B-A3B", "30B"),
            listOf("GPT-OSS 20B", "20B")
        )
        expected.forEachIndexed { rowIndex, cells ->
            val row = table.getChildAt(rowIndex) as LinearLayout
            assertEquals(cells.size, row.childCount)
            cells.forEachIndexed { column, text ->
                val cell = row.getChildAt(column) as TextView
                assertEquals(text, cell.text.toString())
                // Robolectric's legacy Typeface shadow tracks getStyle(), while isBold()
                // reads a native-backed field that stays unset. Assert the requested style.
                assertEquals(
                    "Only table headers must be bold (row=$rowIndex, column=$column)",
                    if (rowIndex == 0) Typeface.BOLD else Typeface.NORMAL,
                    cell.typeface.style and Typeface.BOLD
                )
            }
        }

    }

    @Test
    fun `assistant fenced code should render in a horizontal scroll view`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        activity.setTheme(R.style.AppTheme)
        val itemView = LayoutInflater.from(activity)
            .inflate(R.layout.item_holder_assistant, FrameLayout(activity), false)
        val holder = ChatViewHolders.AssistantViewHolder(itemView)
        val data = ChatDataItem(ChatViewHolders.ASSISTANT).apply {
            displayText = """
                Before

                ```kotlin
                val result = thisIsAnIntentionallyLongFunctionNameForHorizontalScrolling()
                ```

                After
            """.trimIndent()
        }

        holder.bind(data, "Qwen", null)

        val chatText = itemView.findViewById<MarkdownMessageView>(R.id.tv_chat_text)
        val codeBlock = chatText.getChildAt(1) as HorizontalScrollView
        val codeText = codeBlock.getChildAt(0) as TextView

        assertTrue(codeBlock.isHorizontalScrollBarEnabled)
        assertTrue(codeText.text.contains("thisIsAnIntentionallyLongFunctionName"))
    }
}
