/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.rust.ide.colors.RsColor
import org.rust.ide.highlight.RsHighlighter
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.types.ty.TyPrimitive
import org.rust.openapiext.isUnitTestMode

// Highlighting logic here should be kept in sync with tags in RustColorSettingsPage
class RsHighlightingAnnotator : RsAnnotatorBase() {

    override fun annotateInternal(element: PsiElement, holder: AnnotationHolder) {
        val (partToHighlight, color) = when {
            element is RsPatBinding && !element.isReferenceToConstant -> highlightNotReference(element)
            element is RsMacroCall -> highlightNotReference(element)
            element is RsModDeclItem -> highlightNotReference(element)
            element is RsReferenceElement -> highlightReference(element)
            else -> highlightNotReference(element)
        } ?: return

        val severity = if (isUnitTestMode) color.testSeverity else HighlightSeverity.INFORMATION
        holder.createAnnotation(severity, partToHighlight, null).textAttributes = color.textAttributesKey
    }

    private fun highlightReference(element: RsReferenceElement): Pair<TextRange, RsColor>? {
        // These should be highlighted as keywords by the lexer
        if (element is RsPath && element.kind != PathKind.IDENTIFIER) return null
        if (element is RsExternCrateItem && element.self != null) return null

        val isPrimitiveType = element is RsPath && TyPrimitive.fromPath(element) != null

        val color = when {
            isPrimitiveType -> RsColor.PRIMITIVE_TYPE
            element.parent is RsMacroCall -> RsColor.MACRO
            element is RsMethodCall -> RsColor.FUNCTION_CALL
            element is RsFieldLookup && element.identifier?.text == "await" && element.isEdition2018 -> RsColor.KEYWORD
            element is RsPath && element.parent?.parent is RsCallExpr -> {
                val ref = element.reference.resolve() ?: return null
                if (ref is RsFunction) {
                    if (ref.isAssocFn) RsColor.ASSOC_FUNCTION else RsColor.FUNCTION_CALL
                } else {
                    colorFor(ref)
                }
            }
            else -> {
                val ref = element.reference.resolve() ?: return null
                // Highlight the element dependent on what it's referencing.
                colorFor(ref)
            }
        }
        return color?.let { element.referenceNameElement.textRange to it }
    }

    private fun highlightNotReference(element: PsiElement): Pair<TextRange, RsColor>? {
        if (element is RsLitExpr) {
            if (element.parent is RsMetaItem) {
                val literal = element.firstChild
                val color = RsHighlighter.map(literal.elementType)
                    ?: return null // FIXME: `error` here perhaps?
                return literal.textRange to color
            }
            return null
        }

        // Although we remap tokens from identifier to keyword, this happens in the
        // parser's pass, so we can't use HighlightingLexer to color these
        if (element.elementType in RS_CONTEXTUAL_KEYWORDS) {
            return element.textRange to RsColor.KEYWORD
        }

        if (element is RsElement) {
            val color = colorFor(element)
            val part = partToHighlight(element)
            if (color != null && part != null) {
                return part to color
            }
        }
        return null
    }
}

// If possible, this should use only stubs because this will be called
// on elements in other files when highlighting references.
private fun colorFor(element: RsElement): RsColor? = when (element) {
    is RsMacro -> RsColor.MACRO

    is RsAttr -> RsColor.ATTRIBUTE
    is RsMacroCall -> RsColor.MACRO
    is RsSelfParameter -> RsColor.SELF_PARAMETER
    is RsTryExpr -> RsColor.Q_OPERATOR
    is RsTraitRef -> RsColor.TRAIT

    is RsEnumItem -> RsColor.ENUM
    is RsEnumVariant -> RsColor.ENUM_VARIANT
    is RsExternCrateItem -> RsColor.CRATE
    is RsConstant -> RsColor.CONSTANT
    is RsNamedFieldDecl -> RsColor.FIELD
    is RsFunction -> when (element.owner) {
        is RsAbstractableOwner.Foreign, is RsAbstractableOwner.Free -> RsColor.FUNCTION
        is RsAbstractableOwner.Trait, is RsAbstractableOwner.Impl -> RsColor.METHOD
    }
    is RsModDeclItem -> RsColor.MODULE
    is RsMod -> if (element.isCrateRoot) RsColor.CRATE else RsColor.MODULE
    is RsPatBinding -> {
        if (element.ancestorStrict<RsValueParameter>() != null) RsColor.PARAMETER else null
    }
    is RsStructItem -> when (element.kind) {
        RsStructKind.STRUCT -> RsColor.STRUCT
        RsStructKind.UNION -> RsColor.UNION
    }
    is RsTraitItem -> RsColor.TRAIT
    is RsTypeAlias -> RsColor.TYPE_ALIAS
    is RsTypeParameter -> RsColor.TYPE_PARAMETER
    is RsConstParameter -> RsColor.CONST_PARAMETER
    is RsMacroReference -> RsColor.FUNCTION
    is RsMacroBinding -> RsColor.FUNCTION
    else -> null
}

private fun partToHighlight(element: RsElement): TextRange? {
    if (element is RsMacro) {
        val range = element.identifier?.textRange ?: return null
        val excl = element.excl
        return range.union(excl.textRange)
    }

    if (element is RsMacroCall) {
        return element.excl.textRange
    }

    val name = when (element) {
        is RsAttr -> element
        is RsSelfParameter -> element.self
        is RsTryExpr -> element.q
        is RsTraitRef -> element.path.identifier

        is RsEnumItem -> element.identifier
        is RsEnumVariant -> element.identifier
        is RsExternCrateItem -> element.identifier
        is RsConstant -> element.identifier
        is RsNamedFieldDecl -> element.identifier
        is RsFunction -> element.identifier
        is RsMethodCall -> element.referenceNameElement
        is RsModDeclItem -> element.identifier
        is RsModItem -> element.identifier
        is RsPatBinding -> element.identifier
        is RsStructItem -> element.identifier
        is RsTraitItem -> element.identifier
        is RsTypeAlias -> element.identifier
        is RsTypeParameter -> element.identifier
        is RsConstParameter -> element.identifier
        is RsMacroBinding -> element.metaVarIdentifier
        else -> null
    }
    return name?.textRange
}
