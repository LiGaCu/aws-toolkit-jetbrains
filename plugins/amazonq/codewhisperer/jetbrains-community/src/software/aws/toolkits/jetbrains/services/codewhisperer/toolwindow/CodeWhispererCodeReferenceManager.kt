// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.toolwindow

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.awt.RelativePoint
import software.aws.toolkits.jetbrains.services.amazonq.lsp.model.aws.textDocument.InlineCompletionItem
import software.aws.toolkits.jetbrains.services.amazonq.lsp.model.aws.textDocument.InlineCompletionReference
import software.aws.toolkits.jetbrains.services.codewhisperer.editor.CodeWhispererEditorUtil.getPopupPositionAboveText
import software.aws.toolkits.jetbrains.services.codewhisperer.editor.CodeWhispererEditorUtil.getRelativePathToContentRoot
import software.aws.toolkits.jetbrains.services.codewhisperer.layout.CodeWhispererLayoutConfig.horizontalPanelConstraints
import software.aws.toolkits.jetbrains.services.codewhisperer.model.CaretPosition
import software.aws.toolkits.jetbrains.services.codewhisperer.model.InvocationContext
import software.aws.toolkits.jetbrains.services.codewhisperer.model.InvocationContextNew
import software.aws.toolkits.jetbrains.services.codewhisperer.model.PreviewContext
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererColorUtil.EDITOR_CODE_REFERENCE_HOVER
import software.aws.toolkits.resources.message
import javax.swing.JLabel
import javax.swing.JPanel

@Service(Service.Level.PROJECT)
class CodeWhispererCodeReferenceManager(private val project: Project) {
    val codeReferenceComponents = CodeWhispererCodeReferenceComponents(project)
    val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(CodeWhispererCodeReferenceToolWindowFactory.id)
    val highlighters = mutableListOf<CodeReferenceHighLightContext>()
    var currentHighLightPopupContext: CodeReferenceHighLightPopupContext? = null
    private val referenceTextAttribute = TextAttributes().apply {
        effectColor = EDITOR_CODE_REFERENCE_HOVER
    }

    init {
        // Listen for global scheme changes
        project.messageBus.connect().subscribe(
            EditorColorsManager.TOPIC,
            EditorColorsListener { scheme ->
                if (scheme == null) return@EditorColorsListener
                codeReferenceComponents.apply {
                    contentPanel.background = scheme.defaultBackground
                    contentPanel.components.forEach {
                        it.background = scheme.defaultBackground
                    }
                }
            }
        )
    }

    fun showCodeReferencePanel() {
        toolWindow?.show()
    }

    fun insertCodeReference(originalCode: String, references: List<InlineCompletionReference>?, editor: Editor, caretPosition: CaretPosition) {
        val startOffset = caretPosition.offset
        insertCodeReference(originalCode, references, editor, startOffset)
    }

    fun insertCodeReference(originalCode: String, references: List<InlineCompletionReference>?, editor: Editor, startOffset: Int) {
        val relativePath = getRelativePathToContentRoot(editor)
        references?.forEachIndexed { i, reference ->
            // TODO YUX: validate this
            val start = startOffset + reference.position.startCharacter
            val end = startOffset + reference.position.endCharacter
            val lineNums = getReferenceLineNums(editor, start, end)

            val originalContentLines = originalCode
                .substring(reference.position.startCharacter, reference.position.endCharacter)
                .split("\n")

            addReferenceLogPanelEntry(reference, relativePath, lineNums, originalContentLines)

            insertHighLightContext(editor, start, end, reference)
        }
    }

    fun addReferenceLogPanelEntry(reference: InlineCompletionReference, relativePath: String?, lineNums: String?, originalContentLines: List<String>?) {
        codeReferenceComponents.contentPanel.apply {
            add(
                codeReferenceComponents.codeReferenceRecordPanel(reference, relativePath, lineNums),
                horizontalPanelConstraints,
                components.size - 1
            )

            // add each line of the original reference a JPanel to the tool window content panel
            originalContentLines?.forEach { line ->
                if (line.isEmpty()) return@forEach
                add(
                    codeReferenceComponents.codeContentPanel(line),
                    horizontalPanelConstraints,
                    components.size - 1
                )
            }
        }
    }

    fun insertCodeReference(states: InvocationContext, selectedIndex: Int) {
        val (requestContext, _, recommendationContext) = states
        val (_, editor, _, caretPosition) = requestContext
        val (_, completion) = recommendationContext.details[selectedIndex]
        insertCodeReference(completion.insertText, completion.references, editor, caretPosition)
    }

    fun insertCodeReference(states: InvocationContextNew, previews: List<PreviewContext>, selectedIndex: Int) {
        val detail = previews[selectedIndex].detail
        insertCodeReference(
            detail.completion.insertText,
            detail.completion.references,
            states.requestContext.editor,
            states.requestContext.caretPosition,
        )
    }

    fun insertCodeReference(editor: Editor, item: InlineCompletionItem?, offset: Int) {
        insertCodeReference(item?.insertText.orEmpty(), item?.references, editor, offset)
    }

    fun getReferenceLineNums(editor: Editor, start: Int, end: Int): String {
        val startLine = editor.document.getLineNumber(start)
        val endLine = editor.document.getLineNumber(end)
        val lineNums = if (startLine == endLine) {
            (startLine + 1).toString()
        } else {
            "${startLine + 1} to ${endLine + 1}"
        }
        return lineNums
    }

    private fun insertHighLightContext(editor: Editor, start: Int, end: Int, reference: InlineCompletionReference) {
        val codeContent = editor.document.getText(TextRange.create(start, end))
        val referenceContent = message(
            "codewhisperer.toolwindow.popup.text",
            reference.licenseName,
            reference.referenceName
        )
        val highlighter = editor.markupModel.addRangeHighlighter(
            start,
            end,
            HighlighterLayer.LAST + 1,
            null,
            HighlighterTargetArea.EXACT_RANGE
        ) as RangeHighlighterEx
        highlighters.add(CodeReferenceHighLightContext(editor, highlighter, codeContent, referenceContent))
    }

    private fun showPopup() {
        currentHighLightPopupContext?.let {
            val (context, popup) = it
            val (editor, highlighter) = context
            val point = getPopupPositionAboveText(editor, popup, highlighter.startOffset)
            popup.show(RelativePoint(point))
            highlighter.textAttributes = referenceTextAttribute
        }
    }

    private fun hidePopup() {
        currentHighLightPopupContext?.let {
            val (context, popup) = it
            popup.cancel()
            context.highlighter.textAttributes = null
        }
        currentHighLightPopupContext = null
    }

    fun addListeners(editor: Editor) {
        // If there already is a listener attached to the editor, don't add a duplicated one
        if (editor.getUserData(listenerDisposable) != null) {
            return
        }
        editor.addEditorMouseMotionListener(
            object : EditorMouseMotionListener {
                override fun mouseMoved(e: EditorMouseEvent) {
                    // If not hover on text, hide any highlight if applicable and exit
                    if (e.area != EditorMouseEventArea.EDITING_AREA || !e.isOverText) {
                        hidePopup()
                        return
                    }
                    currentHighLightPopupContext?.let {
                        // There's an active highlight in the editor
                        val (localEditor, highlighter, codeContent) = it.context

                        // Check if mouse is in the range and the content matches the original one, else hide
                        // this highlight
                        if (highlighter.contains(e.offset)) {
                            if (!isHighlighterRangeMatchCodeContent(localEditor, highlighter, codeContent)) {
                                hidePopup()
                            }
                            return
                        }
                        hidePopup()
                    }

                    // Remove invalid highlighters when we are about to iterate them
                    val toRemove = highlighters.filter { !it.highlighter.isValid }
                    toRemove.forEach {
                        highlighters.remove(it)
                    }

                    // No highlight should be visible at this point, we still need to find if mouse is hover on any text
                    // with references
                    highlighters.forEach {
                        val (localEditor, highlighter, codeContent, referenceContent) = it
                        if (highlighter.contains(e.offset)) {
                            if (!isHighlighterRangeMatchCodeContent(localEditor, highlighter, codeContent)) {
                                return
                            }

                            // find a valid range to highlight, show the highlight and popup, no need to check others
                            val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(
                                JPanel().apply {
                                    add(JLabel(referenceContent))
                                },
                                null
                            ).createPopup()
                            currentHighLightPopupContext = CodeReferenceHighLightPopupContext(it, popup)
                            showPopup()
                            return
                        }
                    }
                }
            },
            (editor as EditorImpl).disposable
        )
        editor.putUserData(listenerDisposable, true)
    }

    private fun RangeHighlighterEx.contains(offset: Int) = this.startOffset <= offset && offset < this.endOffset

    private fun isHighlighterRangeMatchCodeContent(
        editor: Editor,
        highlighter: RangeHighlighterEx,
        codeContent: String,
    ): Boolean =
        highlighter.isValid && highlighter.endOffset <= editor.document.textLength &&
            editor.document.getText(TextRange.create(highlighter.startOffset, highlighter.endOffset)) == codeContent

    companion object {
        fun getInstance(project: Project): CodeWhispererCodeReferenceManager = project.service()
        private val listenerDisposable = Key.create<Boolean>("codewhisperer.reference.listener.disposable")
    }
}

data class CodeReferenceHighLightContext(
    val editor: Editor,
    val highlighter: RangeHighlighterEx,
    val codeContent: String,
    val referenceContent: String,
)

data class CodeReferenceHighLightPopupContext(
    val context: CodeReferenceHighLightContext,
    val popup: JBPopup,
)
