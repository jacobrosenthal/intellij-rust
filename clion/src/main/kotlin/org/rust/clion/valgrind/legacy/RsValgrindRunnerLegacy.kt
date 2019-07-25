/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.clion.valgrind.legacy

import com.intellij.execution.configurations.RunProfile
import com.intellij.openapi.util.SystemInfo
import com.jetbrains.cidr.cpp.valgrind.ValgrindExecutor
import org.rust.cargo.runconfig.legacy.RsAsyncRunner

private const val ERROR_MESSAGE_TITLE: String = "Unable to run Valgrind"

class RsValgrindRunnerLegacy : RsAsyncRunner(ValgrindExecutor.EXECUTOR_ID, ERROR_MESSAGE_TITLE) {
    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        (SystemInfo.isMac || SystemInfo.isLinux) && super.canRun(executorId, profile)

    companion object {
        const val RUNNER_ID: String = "RsValgrindRunnerLegacy"
    }
}
