/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.buildtool

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.util.concurrency.FutureResult
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.runconfig.buildtool.CargoBuildManager.showBuildNotification
import org.rust.cargo.runconfig.command.workingDirectory
import java.nio.file.Path
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class CargoBuildContext(
    val cargoProject: CargoProject,
    val environment: ExecutionEnvironment,
    val taskName: String,
    val progressTitle: String,
    val isTestBuild: Boolean
) {
    val buildId: String = "build:" + UUID.randomUUID()

    val project: Project get() = cargoProject.project
    val workingDirectory: Path get() = cargoProject.workingDirectory

    val result: FutureResult<CargoBuildResult> = FutureResult()

    private val buildSemaphore: Semaphore = project.getUserData(BUILD_SEMAPHORE_KEY)
        ?: (project as UserDataHolderEx).putUserDataIfAbsent(BUILD_SEMAPHORE_KEY, Semaphore(1))

    lateinit var indicator: ProgressIndicator
    lateinit var processHandler: ProcessHandler

    val started: Long = System.currentTimeMillis()
    var finished: Long = started
    private val duration: Long get() = finished - started

    var errors: Int = 0
    var warnings: Int = 0

    fun waitAndStart(): Boolean {
        indicator.pushState()
        try {
            indicator.text = "Waiting for the current build to finish..."
            indicator.text2 = ""
            while (true) {
                indicator.checkCanceled()
                try {
                    if (buildSemaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) break
                } catch (e: InterruptedException) {
                    throw ProcessCanceledException()
                }
            }
        } catch (e: ProcessCanceledException) {
            canceled()
            return false
        } finally {
            indicator.popState()
        }
        return true
    }

    fun finished(succeeded: Boolean) {
        finished = System.currentTimeMillis()

        buildSemaphore.release()

        val finishMessage: String
        val finishDetails: String?

        // We report successful builds with errors or warnings correspondingly
        val messageType = if (indicator.isCanceled) {
            finishMessage = "$taskName canceled"
            finishDetails = null
            MessageType.INFO
        } else {
            val hasWarningsOrErrors = errors > 0 || warnings > 0
            finishMessage = if (succeeded) "$taskName finished" else "$taskName failed"
            finishDetails = if (hasWarningsOrErrors) {
                val errorsString = if (errors == 1) "error" else "errors"
                val warningsString = if (warnings == 1) "warning" else "warnings"
                "$errors $errorsString and $warnings $warningsString"
            } else {
                null
            }

            when {
                !succeeded -> MessageType.ERROR
                hasWarningsOrErrors -> MessageType.WARNING
                else -> MessageType.INFO
            }
        }

        result.set(CargoBuildResult(
            succeeded = succeeded,
            canceled = indicator.isCanceled,
            started = started,
            duration = duration,
            errors = errors,
            warnings = warnings,
            message = if (succeeded) "$taskName successful" else "$taskName failed"
        ))

        showBuildNotification(project, messageType, finishMessage, finishDetails, duration)
    }

    fun canceled() {
        finished = System.currentTimeMillis()

        result.set(CargoBuildResult(
            succeeded = false,
            canceled = true,
            started = started,
            duration = duration,
            errors = errors,
            warnings = warnings,
            message = "$taskName canceled"
        ))
    }

    companion object {
        private val BUILD_SEMAPHORE_KEY: Key<Semaphore> = Key.create("BUILD_SEMAPHORE_KEY")
    }
}
