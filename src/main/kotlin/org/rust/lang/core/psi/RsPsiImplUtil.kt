/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.rust.lang.core.macros.findMacroCallExpandedFrom
import org.rust.lang.core.psi.ext.*

/**
 * Mixin methods to implement PSI interfaces without copy pasting and
 * introducing monster base classes. Can be simplified when Kotlin supports
 * default methods in interfaces with mixed Kotlin-Java hierarchies (KT-9073 ).
 */
object RsPsiImplUtil {
    fun crateRelativePath(element: RsNamedElement): String? {
        val name = element.name ?: return null
        val qualifier = element.containingMod.crateRelativePath ?: return null
        return "$qualifier::$name"
    }

    fun modCrateRelativePath(mod: RsMod): String? {
        val segments = mod.superMods.asReversed().drop(1).map {
            it.modName ?: return null
        }
        if (segments.isEmpty()) return ""
        return "::" + segments.joinToString("::")
    }

    /**
     * Used by [RsTypeParameter] and [RsLifetimeParameter].
     * @return null if default scope should be used
     */
    fun getParameterUseScope(element: RsElement): SearchScope? {
        val owner = element.contextStrict<RsGenericDeclaration>()
        if (owner != null) return localOrMacroSearchScope(owner)

        return null
    }

    /**
     * @return null if default scope should be used
     */
    fun getDeclarationUseScope(element: RsVisible): SearchScope? =
        getDeclarationUseScope(element, RsVisibility.Public)

    private fun getDeclarationUseScope(
        element: RsVisible,
        restrictedVis: RsVisibility
    ): SearchScope? {
        when (element) {
            is RsEnumVariant -> return getDeclarationUseScope(element.parentEnum, restrictedVis)
            is RsFieldDecl -> return getDeclarationUseScope(
                element.contextStrict<RsFieldsOwner>() as RsVisible,
                element.visibility
            )
        }

        val owner = PsiTreeUtil.getContextOfType(
            element,
            true,
            RsItemElement::class.java,
            RsMod::class.java
        ) as? RsElement ?: return null

        return when (owner) {
            // Trait members inherit visibility from the trait
            is RsTraitItem -> getDeclarationUseScope(owner)

            is RsImplItem -> {
                // Members of `impl Trait for ...` inherit visibility from the implemented trait
                val traitRef = owner.traitRef
                if (traitRef != null) return getDeclarationUseScope(traitRef.resolveToTrait() ?: return null)

                // Inherent impl members
                getTopLevelDeclarationUseScope(element, owner.containingMod, restrictedVis)
            }

            is RsMod -> getTopLevelDeclarationUseScope(element, owner, restrictedVis)
            is RsForeignModItem -> getTopLevelDeclarationUseScope(element, owner.containingMod, restrictedVis)

            // In this case `owner` is function or code block, i.e. it's local scope
            else -> localOrMacroSearchScope(owner)
        }
    }

    private fun getTopLevelDeclarationUseScope(
        element: RsVisible,
        containingMod: RsMod,
        restrictedVis: RsVisibility
    ): SearchScope? {
        val restrictedMod = when (val visibility = restrictedVis.intersect(element.visibility)) {
            RsVisibility.Public -> return null
            RsVisibility.Private -> containingMod
            is RsVisibility.Restricted -> visibility.inMod
        }

        if (!restrictedMod.hasChildModules()) return localOrMacroSearchScope(containingMod)

        // TODO restrict scope to [restrictedMod]. We can't use `DirectoryScope` b/c file from any
        //   directory can be included via `#[path]` attribute.
        return null
    }

    /**
     * If the [scope] is inside a macro expansion, we can't use it as a local search scope
     * because elements inside the scope can be referenced from the macro call body via
     * [org.rust.lang.core.resolve.ref.RsMacroBodyReferenceDelegateImpl]. We use the macro
     * call as a search scope in this case
     */
    fun localOrMacroSearchScope(scope: PsiElement): LocalSearchScope =
        LocalSearchScope(scope.findMacroCallExpandedFrom() ?: scope)
}

private fun RsMod.hasChildModules(): Boolean =
    expandedItemsExceptImplsAndUses.any { it is RsModDeclItem || it is RsModItem && it.hasChildModules() }
