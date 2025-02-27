/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

// BACKCOMPAT: 2019.1
@file:Suppress("DEPRECATION")

package org.rust.cargo.project.configurable

import com.intellij.codeInsight.hints.InlayParameterHintsExtension
import com.intellij.codeInsight.hints.Option
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.EnumComboBoxModel
import com.intellij.ui.ListCellRendererWrapper
import com.intellij.ui.components.JBCheckBox
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.settings.RustProjectSettingsService.MacroExpansionEngine
import org.rust.cargo.project.settings.ui.RustProjectSettingsPanel
import org.rust.cargo.toolchain.RustToolchain
import org.rust.ide.ui.layout
import org.rust.lang.RsLanguage
import org.rust.openapiext.CheckboxDelegate
import org.rust.openapiext.ComboBoxDelegate
import org.rust.openapiext.pathAsPath
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JList

class RsProjectConfigurable(
    project: Project
) : RsConfigurableBase(project), Configurable.NoScroll {

    private val rustProjectSettings = RustProjectSettingsPanel(
        project.cargoProjects.allProjects.firstOrNull()?.rootDir?.pathAsPath ?: Paths.get(".")
    )

    private val macroExpansionEngineComboBox: ComboBox<MacroExpansionEngine> =
        ComboBox(EnumComboBoxModel(MacroExpansionEngine::class.java)).apply {
            // BACKCOMPAT: 2019.1. Use SimpleListCellRenderer instead
            renderer = object : ListCellRendererWrapper<MacroExpansionEngine>() {
                override fun customize(list: JList<*>?, value: MacroExpansionEngine, index: Int, selected: Boolean, hasFocus: Boolean) {
                    setText(when (value) {
                        MacroExpansionEngine.DISABLED -> "Disable (use only if you have problems with macro expansions)"
                        MacroExpansionEngine.OLD -> "Expand with default engine"
                        MacroExpansionEngine.NEW -> "Expand with experimental engine (faster, but not yet stable)"
                    })
                }
            }
        }
    private var macroExpansionEngine: MacroExpansionEngine by ComboBoxDelegate(macroExpansionEngineComboBox)

    private val showTestToolWindowCheckbox: JBCheckBox = JBCheckBox()
    private var showTestToolWindow: Boolean by CheckboxDelegate(showTestToolWindowCheckbox)

    private val doctestInjectionCheckbox: JBCheckBox = JBCheckBox()
    private var doctestInjectionEnabled: Boolean by CheckboxDelegate(doctestInjectionCheckbox)

    private val hintProvider = InlayParameterHintsExtension.forLanguage(RsLanguage)
    private val hintCheckboxes: Map<String, JBCheckBox> =
        hintProvider.supportedOptions.associate { it.id to JBCheckBox() }

    override fun getDisplayName(): String = "Rust" // sync me with plugin.xml

    override fun createComponent(): JComponent = layout {
        rustProjectSettings.attachTo(this)
        row("Expand declarative macros:", macroExpansionEngineComboBox, """
            Allow plugin to process declarative macro invocations
            to extract information for name resolution and type inference.
        """)
        row("Show test tool window:", showTestToolWindowCheckbox, """
            Show test results in run tool window when testing session begins
            instead of raw console.
        """)
        row("Inject Rust language into documentation comments:", doctestInjectionCheckbox)
        val supportedHintOptions = hintProvider.supportedOptions
        if (supportedHintOptions.isNotEmpty()) {
            block("Hints") {
                for (option in supportedHintOptions) {
                    row("${option.name}:", checkboxForOption(option))
                }
            }
        }
    }

    override fun disposeUIResources() = Disposer.dispose(rustProjectSettings)

    override fun reset() {
        val toolchain = settings.toolchain ?: RustToolchain.suggest()

        rustProjectSettings.data = RustProjectSettingsPanel.Data(
            toolchain = toolchain,
            explicitPathToStdlib = settings.explicitPathToStdlib
        )
        macroExpansionEngine = settings.macroExpansionEngine
        showTestToolWindow = settings.showTestToolWindow
        doctestInjectionEnabled = settings.doctestInjectionEnabled

        for (option in hintProvider.supportedOptions) {
            checkboxForOption(option).isSelected = option.get()
        }
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        rustProjectSettings.validateSettings()

        for (option in hintProvider.supportedOptions) {
            option.set(checkboxForOption(option).isSelected)
        }

        settings.modify {
            it.toolchain = rustProjectSettings.data.toolchain
            it.explicitPathToStdlib = rustProjectSettings.data.explicitPathToStdlib
            it.macroExpansionEngine = macroExpansionEngine
            it.showTestToolWindow = showTestToolWindow
            it.doctestInjectionEnabled = doctestInjectionEnabled
        }
    }

    override fun isModified(): Boolean {
        val data = rustProjectSettings.data
        if (hintProvider.supportedOptions.any { checkboxForOption(it).isSelected != it.get() }) return true
        return data.toolchain?.location != settings.toolchain?.location
            || data.explicitPathToStdlib != settings.explicitPathToStdlib
            || macroExpansionEngine != settings.macroExpansionEngine
            || showTestToolWindow != settings.showTestToolWindow
            || doctestInjectionEnabled != settings.doctestInjectionEnabled
    }

    private fun checkboxForOption(opt: Option): JBCheckBox = hintCheckboxes[opt.id]!!
}
