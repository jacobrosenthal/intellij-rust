/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.annotator

import com.intellij.codeInsight.daemon.impl.SeveritiesProvider
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import org.intellij.lang.annotations.Language
import org.rust.RsTestBase
import org.rust.fileTreeFromText
import org.rust.lang.core.macros.macroExpansionManager
import org.rust.openapiext.Testmark

abstract class RsAnnotationTestBase : RsTestBase() {

    protected fun doTest(vararg additionalFilenames: String) {
        myFixture.testHighlighting(fileName, *additionalFilenames)
    }

    protected fun checkHighlighting(@Language("Rust") text: String) =
        checkByText(text, checkWarn = false, checkWeakWarn = false, checkInfo = false, ignoreExtraHighlighting = true)

    protected fun checkInfo(@Language("Rust") text: String) =
        checkByText(text, checkWarn = false, checkWeakWarn = false, checkInfo = true)

    protected fun checkWarnings(@Language("Rust") text: String) =
        checkByText(text, checkWarn = true, checkWeakWarn = true, checkInfo = false)

    protected fun checkErrors(@Language("Rust") text: String) =
        checkByText(text, checkWarn = false, checkWeakWarn = false, checkInfo = false)

    protected fun checkByText(
        @Language("Rust") text: String,
        checkWarn: Boolean = true,
        checkInfo: Boolean = false,
        checkWeakWarn: Boolean = false,
        ignoreExtraHighlighting: Boolean = false,
        testmark: Testmark? = null
    ) = check(text,
        checkWarn = checkWarn,
        checkInfo = checkInfo,
        checkWeakWarn = checkWeakWarn,
        ignoreExtraHighlighting = ignoreExtraHighlighting,
        configure = this::configureByText,
        testmark = testmark)

    protected fun checkFixByText(
        fixName: String,
        @Language("Rust") before: String,
        @Language("Rust") after: String,
        checkWarn: Boolean = true,
        checkInfo: Boolean = false,
        checkWeakWarn: Boolean = false,
        testmark: Testmark? = null
    ) = checkFix(fixName, before, after,
        configure = this::configureByText,
        checkBefore = { myFixture.checkHighlighting(checkWarn, checkInfo, checkWeakWarn) },
        checkAfter = this::checkByText,
        testmark = testmark)

    protected fun checkFixByTextWithoutHighlighting(
        fixName: String,
        @Language("Rust") before: String,
        @Language("Rust") after: String,
        testmark: Testmark? = null
    ) = checkFix(fixName, before, after,
        configure = this::configureByText,
        checkBefore = {},
        checkAfter = this::checkByText,
        testmark = testmark)

    protected fun checkByFileTree(
        @Language("Rust") text: String,
        checkWarn: Boolean = true,
        checkInfo: Boolean = false,
        checkWeakWarn: Boolean = false,
        ignoreExtraHighlighting: Boolean = false,
        stubOnly: Boolean = true,
        testmark: Testmark? = null
    ) = check(text,
        checkWarn = checkWarn,
        checkInfo = checkInfo,
        checkWeakWarn = checkWeakWarn,
        ignoreExtraHighlighting = ignoreExtraHighlighting,
        configure = { configureByFileTree(it, stubOnly) },
        testmark = testmark)

    protected fun checkFixByFileTree(
        fixName: String,
        @Language("Rust") before: String,
        @Language("Rust") after: String,
        checkWarn: Boolean = true,
        checkInfo: Boolean = false,
        checkWeakWarn: Boolean = false,
        stubOnly: Boolean = true,
        testmark: Testmark? = null
    ) = checkFix(fixName, before, after,
        configure = { configureByFileTree(it, stubOnly) },
        checkBefore = { myFixture.checkHighlighting(checkWarn, checkInfo, checkWeakWarn) },
        checkAfter = this::checkByFileTree,
        testmark = testmark)

    protected fun checkFixByFileTreeWithoutHighlighting(
        fixName: String,
        @Language("Rust") before: String,
        @Language("Rust") after: String,
        stubOnly: Boolean = true,
        testmark: Testmark? = null
    ) = checkFix(fixName, before, after,
        configure = { configureByFileTree(it, stubOnly) },
        checkBefore = {},
        checkAfter = this::checkByFileTree,
        testmark = testmark)

    protected fun checkFixIsUnavailable(
        fixName: String,
        @Language("Rust") text: String,
        checkWarn: Boolean = true,
        checkInfo: Boolean = false,
        checkWeakWarn: Boolean = false,
        ignoreExtraHighlighting: Boolean = false,
        testmark: Testmark? = null
    ) = checkFixIsUnavailable(fixName, text,
        checkWarn = checkWarn,
        checkInfo = checkInfo,
        checkWeakWarn = checkWeakWarn,
        ignoreExtraHighlighting = ignoreExtraHighlighting,
        configure = this::configureByText,
        testmark = testmark)

    protected fun checkFixIsUnavailableByFileTree(
        fixName: String,
        @Language("Rust") text: String,
        checkWarn: Boolean = true,
        checkInfo: Boolean = false,
        checkWeakWarn: Boolean = false,
        ignoreExtraHighlighting: Boolean = false,
        stubOnly: Boolean = true,
        testmark: Testmark? = null
    ) = checkFixIsUnavailable(fixName, text,
        checkWarn = checkWarn,
        checkInfo = checkInfo,
        checkWeakWarn = checkWeakWarn,
        ignoreExtraHighlighting = ignoreExtraHighlighting,
        configure = { configureByFileTree(it, stubOnly) },
        testmark = testmark)

    private fun check(
        @Language("Rust") text: String,
        checkWarn: Boolean,
        checkInfo: Boolean,
        checkWeakWarn: Boolean,
        ignoreExtraHighlighting: Boolean,
        configure: (String) -> Unit,
        testmark: Testmark? = null
    ) {
        val action: () -> Unit = {
            configure(text)
            project.macroExpansionManager.ensureUpToDate()
            myFixture.checkHighlighting(checkWarn, checkInfo, checkWeakWarn, ignoreExtraHighlighting)
        }
        testmark?.checkHit(action) ?: action()
    }

    private fun checkFix(
        fixName: String,
        @Language("Rust") before: String,
        @Language("Rust") after: String,
        configure: (String) -> Unit,
        checkBefore: () -> Unit,
        checkAfter: (String) -> Unit,
        testmark: Testmark? = null
    ) {
        val action: () -> Unit = {
            configure(before)
            project.macroExpansionManager.ensureUpToDate()
            checkBefore()
            applyQuickFix(fixName)
            checkAfter(after)
        }
        testmark?.checkHit(action) ?: action()
    }

    private fun checkFixIsUnavailable(
        fixName: String,
        @Language("Rust") text: String,
        checkWarn: Boolean,
        checkInfo: Boolean,
        checkWeakWarn: Boolean,
        ignoreExtraHighlighting: Boolean,
        configure: (String) -> Unit,
        testmark: Testmark? = null
    ) {
        check(text, checkWarn, checkInfo, checkWeakWarn, ignoreExtraHighlighting, configure, testmark)
        check(myFixture.filterAvailableIntentions(fixName).isEmpty()) {
            "Fix $fixName should not be possible to apply."
        }
    }

    private fun configureByFileTree(text: String, stubOnly: Boolean) {
        val testProject = configureByFileTree(text)
        if (stubOnly) {
            (myFixture as CodeInsightTestFixtureImpl)
                .setVirtualFileFilter { !it.path.endsWith(testProject.fileWithCaret) }
        }
    }

    private fun checkByText(text: String) {
        myFixture.checkResult(replaceCaretMarker(text.trimIndent()))
    }

    private fun checkByFileTree(text: String) {
        fileTreeFromText(replaceCaretMarker(text)).check(myFixture)
    }

    protected fun checkDontTouchAstInOtherFiles(@Language("Rust") text: String, checkInfo: Boolean = false, filePath: String? = null) {
        fileTreeFromText(text).create()
        val testFilePath = filePath ?: "main.rs"
        (myFixture as CodeInsightTestFixtureImpl) // meh
                    .setVirtualFileFilter { !it.path.endsWith(testFilePath) }

        myFixture.configureFromTempProjectFile(testFilePath)
        myFixture.testHighlighting(false, checkInfo, false)
    }

    protected fun registerSeverities(severities: List<HighlightSeverity>) {
        val testSeverityProvider = RsTestSeverityProvider(severities)
        SeveritiesProvider.EP_NAME.getPoint(null).registerExtension(testSeverityProvider, testRootDisposable)
    }
}
