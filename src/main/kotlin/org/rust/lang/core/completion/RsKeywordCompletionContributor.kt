/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.completion

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.patterns.PsiElementPattern
import com.intellij.patterns.StandardPatterns.or
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.util.ProcessingContext
import org.rust.lang.core.RsPsiPattern
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.RsElementTypes.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.psiElement
import org.rust.lang.core.resolve.ImplLookup
import org.rust.lang.core.types.infer.lookupFutureOutputTy
import org.rust.lang.core.types.ty.Ty
import org.rust.lang.core.types.ty.TyUnknown
import org.rust.lang.core.types.type
import org.rust.lang.core.withPrevSiblingSkipping
import org.rust.lang.core.withSuperParent

/**
 * Completes Rust keywords
 *
 * TODO: checkout  org.jetbrains.kotlin.idea.completion.KeywordCompletion, it has some super cool ideas
 */
class RsKeywordCompletionContributor : CompletionContributor(), DumbAware {

    init {
        extend(CompletionType.BASIC, declarationPattern(),
            RsKeywordCompletionProvider("const", "enum", "extern", "fn", "impl", "mod", "pub", "static", "struct", "trait", "type", "unsafe", "use"))
        extend(CompletionType.BASIC, pubDeclarationPattern(),
            RsKeywordCompletionProvider("const", "enum", "extern", "fn", "mod", "static", "struct", "trait", "type", "unsafe", "use"))
        extend(CompletionType.BASIC, externDeclarationPattern(),
            RsKeywordCompletionProvider("crate", "fn"))
        extend(CompletionType.BASIC, unsafeDeclarationPattern(),
            RsKeywordCompletionProvider("fn", "impl", "trait", "extern"))
        extend(CompletionType.BASIC, newCodeStatementPattern(),
            RsKeywordCompletionProvider("return", "let"))
        extend(CompletionType.BASIC, letPattern(),
            RsKeywordCompletionProvider("mut"))
        extend(CompletionType.BASIC, loopFlowCommandPatern(),
            RsKeywordCompletionProvider("break", "continue"))
        extend(CompletionType.BASIC, wherePattern(),
            RsKeywordCompletionProvider("where"))
        extend(CompletionType.BASIC, constParameterBeginningPattern(),
            RsKeywordCompletionProvider("const"))

        extend(CompletionType.BASIC, elsePattern(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val elseBuilder = LookupElementBuilder
                    .create("else")
                    .bold()
                    .withTailText(" {...}")
                    .withInsertHandler { ctx, _ ->
                        ctx.document.insertString(ctx.selectionEndOffset, " {  }")
                        EditorModificationUtil.moveCaretRelatively(ctx.editor, 3)

                    }

                val elseIfBuilder = conditionLookupElement("else if")

                // `else` is more common than `else if`
                result.addElement(elseBuilder.withPriority(KEYWORD_PRIORITY * 1.0001))
                result.addElement(elseIfBuilder.withPriority(KEYWORD_PRIORITY))
            }
        })

        extend(CompletionType.BASIC, pathExpressionPattern(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                for (keyword in CONDITION_KEYWORDS) {
                    result.addElement(conditionLookupElement(keyword).withPriority(KEYWORD_PRIORITY))
                }
            }
        })

        extend(CompletionType.BASIC, postfixAwaitPattern(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(
                parameters: CompletionParameters,
                context: ProcessingContext,
                result: CompletionResultSet
            ) {
                val awaitTy = context.get(AWAIT_TY) ?: return
                val awaitBuilder = LookupElementBuilder
                    .create("await")
                    .bold()
                    .withTypeText(awaitTy.toString())
                result.addElement(awaitBuilder.withPriority(KEYWORD_PRIORITY * 1.0001))
            }
        })
    }

    private fun conditionLookupElement(lookupString: String): LookupElementBuilder {
        return LookupElementBuilder
            .create(lookupString)
            .bold()
            .withTailText(" {...}")
            .withInsertHandler { context, _ ->
                val isLetExpr = context.file.findElementAt(context.tailOffset - 1)
                    ?.ancestorStrict<RsLetDecl>()
                    ?.let { it.expr?.text == lookupString } == true
                val hasSemicolon = context.nextCharIs(';')

                var tail = "  { }"
                if (isLetExpr && !hasSemicolon) tail += ';'
                context.document.insertString(context.selectionEndOffset, tail)
                EditorModificationUtil.moveCaretRelatively(context.editor, 1)
            }
    }

    private fun declarationPattern(): PsiElementPattern.Capture<PsiElement> =
        baseDeclarationPattern().and(statementBeginningPattern())

    private fun pubDeclarationPattern(): PsiElementPattern.Capture<PsiElement> =
        baseDeclarationPattern().and(statementBeginningPattern("pub"))

    private fun externDeclarationPattern(): PsiElementPattern.Capture<PsiElement> =
        baseDeclarationPattern().and(statementBeginningPattern("extern"))

    private fun unsafeDeclarationPattern(): PsiElementPattern.Capture<PsiElement> =
        baseDeclarationPattern().and(statementBeginningPattern("unsafe"))

    private fun newCodeStatementPattern(): PsiElementPattern.Capture<PsiElement> =
        baseCodeStatementPattern().and(statementBeginningPattern())

    private fun letPattern(): PsiElementPattern.Capture<PsiElement> =
        baseCodeStatementPattern().and(statementBeginningPattern("let"))

    private fun loopFlowCommandPatern(): PsiElementPattern.Capture<PsiElement> =
        RsPsiPattern.inAnyLoop.and(newCodeStatementPattern())

    private fun baseDeclarationPattern(): PsiElementPattern.Capture<PsiElement> =
        psiElement()
            .withParent(or(psiElement<RsPath>(), psiElement<RsModItem>(), psiElement<RsFile>()))

    private fun baseCodeStatementPattern(): PsiElementPattern.Capture<PsiElement> =
        psiElement()
            .inside(psiElement<RsFunction>())
            .andNot(psiElement().withParent(RsModItem::class.java))

    private fun statementBeginningPattern(vararg startWords: String): PsiElementPattern.Capture<PsiElement> =
        psiElement(IDENTIFIER).and(RsPsiPattern.onStatementBeginning(*startWords))

    private fun elsePattern(): PsiElementPattern.Capture<PsiElement> {
        val braceAfterIf = psiElement(RBRACE).withSuperParent(2, psiElement(IF_EXPR))
        return psiElement().afterLeafSkipping(RsPsiPattern.whitespace, braceAfterIf)
    }

    private fun wherePattern(): PsiElementPattern.Capture<PsiElement> {
        val typeParameters = psiElement<RsTypeParameterList>()

        val function = psiElement<RsFunction>()
            .withLastChildSkipping(RsPsiPattern.error, or(psiElement<RsValueParameterList>(), psiElement<RsRetType>()))
            .andOr(psiElement().withChild(psiElement<RsTypeParameterList>()),
                psiElement().withParent(RsMembers::class.java))

        val struct = psiElement<RsStructItem>()
            .withChild(typeParameters)
            .withLastChildSkipping(RsPsiPattern.error, or(typeParameters, psiElement<RsTupleFields>()))

        val enum = psiElement<RsEnumItem>()
            .withLastChildSkipping(RsPsiPattern.error, typeParameters)

        val typeAlias = psiElement<RsTypeAlias>()
            .withLastChildSkipping(RsPsiPattern.error, typeParameters)
            .andNot(psiElement().withParent(RsMembers::class.java))

        val trait = psiElement<RsTraitItem>()
            .withLastChildSkipping(RsPsiPattern.error, or(psiElement(IDENTIFIER), typeParameters))

        val impl = psiElement<RsImplItem>()
            .withLastChildSkipping(RsPsiPattern.error, psiElement<RsTypeReference>())

        return psiElement()
            .withPrevSiblingSkipping(RsPsiPattern.whitespace, or(function, struct, enum, typeAlias, trait, impl))
    }

    private fun pathExpressionPattern(): PsiElementPattern.Capture<PsiElement> {
        val parent = psiElement<RsPath>()
            .with(object : PatternCondition<RsPath>("RsPath") {
                override fun accepts(t: RsPath, context: ProcessingContext?): Boolean {
                    return t.path == null && t.typeQual == null
                }
            })

        return psiElement(IDENTIFIER)
            .withParent(parent)
            .withSuperParent<RsPathExpr>(2)
            .inside(psiElement<RsFunction>())
    }

    private fun postfixAwaitPattern(): PsiElementPattern.Capture<PsiElement> {
        val parent = psiElement<RsFieldLookup>()
            .with(object : PatternCondition<RsFieldLookup>("RsPostfixAwait") {
                override fun accepts(t: RsFieldLookup, context: ProcessingContext?): Boolean {
                    if (context == null || !t.isEdition2018) return false
                    val receiver = t.receiver.safeGetOriginalOrSelf()
                    val lookup = ImplLookup.relativeTo(receiver)
                    val awaitTy = receiver.type.lookupFutureOutputTy(lookup)
                    if (awaitTy is TyUnknown) return false
                    context.put(AWAIT_TY, awaitTy)
                    return true
                }
            })

        return psiElement(IDENTIFIER).withParent(parent)
    }

    private fun constParameterBeginningPattern(): PsiElementPattern.Capture<PsiElement> {
        val parent = psiElement<RsTypeParameter>()
            .with(object : PatternCondition<RsTypeParameter>("RsConstParameterBeginning") {
                override fun accepts(t: RsTypeParameter, context: ProcessingContext?): Boolean {
                    val leftSibling = t.leftSiblings.firstOrNull { it !is PsiWhiteSpace }
                    if (leftSibling != null && leftSibling.elementType != LT && leftSibling.elementType != COMMA) {
                        return false
                    }

                    val rightSibling = t.rightSiblings.firstOrNull { it is RsElement }
                    if (rightSibling is RsTypeParameter || rightSibling is RsLifetimeParameter) {
                        return false
                    }

                    return true
                }
            })

        return psiElement(IDENTIFIER).withParent(parent)
    }

    companion object {
        @JvmField
        val CONDITION_KEYWORDS: List<String> = listOf("if", "match")
        private val AWAIT_TY: Key<Ty> = Key.create("AWAIT_TY")
    }
}
