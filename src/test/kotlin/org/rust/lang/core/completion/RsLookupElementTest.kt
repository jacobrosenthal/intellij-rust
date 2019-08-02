/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.completion

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.psi.NavigatablePsiElement
import org.intellij.lang.annotations.Language
import org.rust.RsTestBase
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsTupleFieldDecl
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.RsNamedElement
import org.rust.lang.core.resolve.SimpleScopeEntry

class RsLookupElementTest : RsTestBase() {
    fun `test fn`() = check("""
        fn foo(x: i32) -> Option<String> {}
          //^
    """, tailText = "(x: i32)", typeText = "Option<String>")

    fun `test trait method`() = check("""
        trait T {
            fn foo(&self, x: i32) {}
              //^
        }
    """, tailText = "(&self, x: i32)", typeText = "()")

    fun `test trait by method`() = check("""
        trait T {
            fn foo(&self, x: i32);
        }
        struct S;
        impl T for S {
            fn foo(&self, x: i32) {
              //^
                unimplemented!()
            }
        }
    """, tailText = "(&self, x: i32) of T", typeText = "()")

    fun `test const item`() = check("""
        const C: S = unimplemented!();
            //^
    """, typeText = "S")

    fun `test static item`() = check("""
        static C: S = unimplemented!();
             //^
    """, typeText = "S")

    fun `test tuple struct`() = check("""
        struct S(f32, i64);
             //^
    """, tailText = "(f32, i64)")

    fun `test multi-line tuple struct`() = check("""
        struct S(
             //^
            f32,
            i64
        );
    """, tailText = "(f32, i64)")

    fun `test struct`() = check("""
        struct S { field: String }
             //^
    """, tailText = " { ... }")

    fun `test enum`() = check("""
        enum E { X, Y }
           //^
    """)

    fun `test enum struct variant`() = check("""
        enum E { X {} }
               //^
    """, tailText = " { ... }", typeText = "E")

    fun `test enum tuple variant`() = check("""
        enum E { X(i32, String) }
               //^
    """, tailText = "(i32, String)", typeText = "E")

    fun `test multi-line enum tuple variant`() = check("""
        enum E {
            X(
          //^
                i32,
                String
            )
        }
    """, tailText = "(i32, String)", typeText = "E")

    fun `test named field`() = check("""
        struct S { field: String }
                   //^
    """, typeText = "String")

    fun `test tuple field`() = checkInner<RsTupleFieldDecl>("""
        struct S(String);
                 //^
    """, typeText = "String")

    fun `test macro simple`() = check("""
        macro_rules! test {
            ($ test:expr) => ($ test)
                //^
        }
    """, tailText = null, typeText = "expr")

    fun `test macro definition`() = check("""
        macro_rules! test { () => () }
                     //^
    """, tailText = "!", typeText = null)

    fun `test deprecated fn`() = check("""
        #[deprecated]
        fn foo() {}
          //^
    """, tailText = "()", typeText = "()", isStrikeout = true)

    fun `test deprecated const item`() = check("""
        #[deprecated]
        const C: S = unimplemented!();
            //^
    """, typeText = "S", isStrikeout = true)

    fun `test deprecated enum`() = check("""
        #[deprecated]
        enum E { X, Y }
           //^
    """, isStrikeout = true)

    fun `test mod`() {
        myFixture.configureByText("foo.rs", "")
        val lookup = createLookupElement(
            SimpleScopeEntry("foo", myFixture.file as RsFile),
            RsCompletionContext()
        )
        val presentation = LookupElementPresentation()

        lookup.renderElement(presentation)
        assertNotNull(presentation.icon)
        assertEquals("foo", presentation.itemText)
    }

    private fun check(
        @Language("Rust") code: String,
        tailText: String? = null,
        typeText: String? = null,
        isStrikeout: Boolean = false
    ) = checkInner<RsNamedElement>(code, tailText, typeText, isStrikeout)

    private inline fun <reified T> checkInner(
        @Language("Rust") code: String,
        tailText: String? = null,
        typeText: String? = null,
        isStrikeout: Boolean = false
    ) where T : NavigatablePsiElement, T : RsElement {
        InlineFile(code)
        val element = findElementInEditor<T>()
        val lookup = createLookupElement(
            SimpleScopeEntry(element.name!!, element as RsElement),
            RsCompletionContext()
        )
        val presentation = LookupElementPresentation()

        lookup.renderElement(presentation)
        assertNotNull(presentation.icon)
        assertEquals(tailText, presentation.tailText)
        assertEquals(typeText, presentation.typeText)
        assertEquals(isStrikeout, presentation.isStrikeout)
    }
}
