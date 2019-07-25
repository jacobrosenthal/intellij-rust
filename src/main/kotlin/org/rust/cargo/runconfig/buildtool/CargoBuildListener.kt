/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.buildtool

import com.intellij.build.BuildProgressListener
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.FinishBuildEventImpl
import com.intellij.build.events.impl.StartBuildEventImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.execution.actions.StopProcessAction
import com.intellij.execution.process.*
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtil
import org.rust.cargo.CargoConstants
import org.rust.cargo.runconfig.RsAnsiEscapeDecoder
import org.rust.cargo.runconfig.RsExecutableRunner.Companion.binaries
import org.rust.cargo.runconfig.createFilters

@Suppress("UnstableApiUsage")
class CargoBuildListener(
    private val context: CargoBuildContext,
    private val buildProgressListener: BuildProgressListener
) : ProcessAdapter(), AnsiEscapeDecoder.ColoredTextAcceptor {
    private val decoder: AnsiEscapeDecoder = RsAnsiEscapeDecoder()

    private val buildOutputParser: CargoBuildEventsConverter = CargoBuildEventsConverter(context)

    private val instantReader = BuildOutputInstantReaderImpl(
        context.buildId,
        context.buildId,
        buildProgressListener,
        listOf(buildOutputParser)
    )

    private val lineBuffer: MutableList<String> = mutableListOf()

    override fun startNotified(event: ProcessEvent) {
        val descriptor = DefaultBuildDescriptor(
            context.buildId,
            "Run Cargo command",
            context.workingDirectory.toString(),
            context.started
        )
        val buildStarted = StartBuildEventImpl(descriptor, "${context.taskName} running...")
            .withExecutionFilters(*createFilters(context.cargoProject).toTypedArray())
            .withRestartAction(createStopAction(event.processHandler))
        buildProgressListener.onEvent(context.buildId, buildStarted)
    }

    override fun processTerminated(event: ProcessEvent) {
        instantReader.closeAndGetFuture().whenComplete { _, _ ->
            val succeeded = event.exitCode == 0
            context.environment.binaries = buildOutputParser.binaries.takeIf { succeeded }

            val buildFinished = FinishBuildEventImpl(
                context.buildId,
                null,
                context.finished,
                if (succeeded) "${context.taskName} successful" else "${context.taskName} failed",
                if (succeeded) SuccessResultImpl() else FailureResultImpl(null as Throwable?)
            )
            buildProgressListener.onEvent(context.buildId, buildFinished)

            context.finished(succeeded)

            val targetPath = context.workingDirectory.resolve(CargoConstants.ProjectLayout.target)
            val targetDir = VfsUtil.findFile(targetPath, true) ?: return@whenComplete
            VfsUtil.markDirtyAndRefresh(true, true, true, targetDir)
        }
    }

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        val text = event.text.replaceSuffix("\r", "\n")
        lineBuffer.add(text)

        if (text.endsWith('\n')) {
            val wholeLine = lineBuffer.joinToString("")
            lineBuffer.clear()

            // progress_message [K actual_message
            val lines = wholeLine.split("[K").takeUnless { it.isEmpty() } ?: listOf(wholeLine)

            // don't print progress message
            buildOutputParser.parseOutput(lines.last(), outputType == ProcessOutputTypes.STDOUT) {
                buildProgressListener.onEvent(context.buildId, it)
            }

            for (line in lines) {
                val lineWithSeparator = if (line.endsWith('\n')) line else line + '\n'
                decoder.escapeText(lineWithSeparator, outputType, this)
            }
        }
    }

    override fun coloredTextAvailable(text: String, outputType: Key<*>) {
        instantReader.append(text)
    }

    companion object {
        private fun createStopAction(processHandler: ProcessHandler): StopProcessAction {
            val stopAction = StopProcessAction("Stop", "Stop", processHandler)
            ActionUtil.copyFrom(stopAction, IdeActions.ACTION_STOP_PROGRAM)
            stopAction.registerCustomShortcutSet(stopAction.shortcutSet, null /* TODO: use a BuildView */)
            return stopAction
        }

        private fun String.replaceSuffix(oldSuffix: String, newSuffix: String): String =
            if (endsWith(oldSuffix)) replaceRange(length - oldSuffix.length, length, newSuffix) else this
    }
}
