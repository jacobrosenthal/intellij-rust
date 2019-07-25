/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.debugger.runconfig

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.XDebugProcessConfiguratorStarter
import com.intellij.xdebugger.impl.ui.XDebugSessionData
import com.jetbrains.cidr.cpp.toolchains.CPPToolchains
import com.jetbrains.cidr.cpp.toolchains.CPPToolchainsConfigurable
import com.jetbrains.cidr.toolchains.OSType
import org.rust.cargo.runconfig.CargoRunStateBase
import org.rust.cargo.runconfig.RsExecutableRunner
import org.rust.debugger.settings.RsDebuggerSettings

private const val ERROR_MESSAGE_TITLE: String = "Unable to run debugger"

class RsDebugRunner : RsExecutableRunner(DefaultDebugExecutor.EXECUTOR_ID, ERROR_MESSAGE_TITLE) {
    override fun getRunnerId(): String = RUNNER_ID

    override fun showRunContent(
        state: CargoRunStateBase,
        environment: ExecutionEnvironment,
        runExecutable: GeneralCommandLine
    ): RunContentDescriptor {
        val runParameters = RsDebugRunParameters(environment.project, runExecutable)
        return XDebuggerManager.getInstance(environment.project)
            .startSession(environment, object : XDebugProcessConfiguratorStarter() {
                override fun start(session: XDebugSession): XDebugProcess =
                    RsLocalDebugProcess(runParameters, session, state.consoleBuilder, state::computeSysroot).apply {
                        ProcessTerminatedListener.attach(processHandler, environment.project)
                        val settings = RsDebuggerSettings.getInstance()
                        loadPrettyPrinters(settings.lldbRenderers, settings.gdbRenderers)
                        val commitHash = state.cargoProject?.rustcInfo?.version?.commitHash
                        if (commitHash != null) {
                            loadRustcSources(commitHash)
                        }
                        start()
                    }

                override fun configure(data: XDebugSessionData?) {}
            })
            .runContentDescriptor
    }

    override fun checkToolchainSupported(project: Project, state: CargoRunStateBase): Boolean {
        if (CPPToolchains.getInstance().osType == OSType.WIN && "msvc" in state.rustVersion().rustc?.host.orEmpty()) {
            project.showErrorDialog("MSVC toolchain is not supported. Please use GNU toolchain.")
            return false
        }
        return true
    }

    override fun checkToolchainConfigured(project: Project): Boolean {
        val toolchains = CPPToolchains.getInstance()
        val toolchain = toolchains.defaultToolchain
        if (toolchain == null) {
            val option = Messages.showDialog(
                project,
                "Debug toolchain is not configured.",
                ERROR_MESSAGE_TITLE,
                arrayOf("Configure"),
                Messages.OK,
                Messages.getErrorIcon()
            )
            if (option == Messages.OK) {
                ShowSettingsUtil.getInstance().showSettingsDialog(
                    project,
                    CPPToolchainsConfigurable::class.java,
                    null
                )
            }
            return false
        }
        return true
    }

    companion object {
        const val RUNNER_ID: String = "RsDebugRunner"
    }
}
