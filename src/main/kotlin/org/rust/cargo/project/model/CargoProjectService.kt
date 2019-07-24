/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.project.model

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import org.jetbrains.annotations.TestOnly
import org.rust.cargo.CargoConstants
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.toolchain.RustToolchain
import org.rust.cargo.toolchain.RustcVersion
import org.rust.ide.notifications.showBalloon
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Stores a list of [CargoProject]s associated with the current IntelliJ [Project].
 * Use [Project.cargoProjects] to get an instance of the service.
 *
 * # Project model
 *
 * ```
 *                   [CargoProject]
 *                         |
 *                 [CargoWorkspace]
 *                   /          \
 *              [Package]   [Package]
 *                /   \         |
 *         [Target] [Target] [Target]
 *        (main.rs) (lib.rs) (lib.rs)
 * ```
 *
 * ## CargoProject
 *
 * [CargoProject] is basically a `Cargo.toml` file inside a project source folder
 * that is linked to the current IDE project via [attachCargoProject] (usually
 * there is a single project). Each valid [CargoProject] should contain
 * exactly one [CargoWorkspace] (see [CargoProject.workspace]). A workspace
 * can be null if project is not valid (project is in updating state, Cargo is
 * not installed, broken `Cargo.toml`, etc).
 *
 * ## CargoWorkspace
 *
 * [CargoWorkspace] is attached to each valid [CargoProject] and stores info
 * about its packages (see [CargoWorkspace.packages]). A workspace is acquired
 * from Cargo itself via `cargo metadata` command.
 *
 * ## Package
 *
 * [CargoWorkspace.Package] is a thing that can be considered as a dependency (has
 * a name and version) and can have dependencies. A package can consist of multiple
 * binary (executable) targets and/or one library target. Package is described by
 * `[package]` section of `Cargo.toml`
 *
 * ## Target
 *
 * [CargoWorkspace.Target] is a thing that can be compiled to some binary artifact,
 * e.g. executable binary, library, example library/executable, test executable.
 * Each target has crate root (see [CargoWorkspace.Target.crateRoot]) which is
 * usually `main.rs` or `lib.rs`.
 *
 * # FAQ
 *
 * Q: How is [CargoProject] different from [CargoWorkspace]?
 *
 * A: They are mostly the same, i.e. they are linked to each other. The most
 *   difference is that [CargoWorkspace] is acquired from external tool,
 *   and so can be `null` sometimes, when [CargoProject] is always persisted.
 *
 * Q: How [CargoWorkspace] relates to `[workspace]` section in `Cargo.toml`?
 *
 * A: Each Cargo project has a workspace even if there is no `[workspace]` in
 *   the `Cargo.toml`. With `[workspace]` the [CargoWorkspace] contains all
 *   `[workspace.members]` packages.
 *
 * Q: How is [CargoWorkspace.Package] different from [CargoWorkspace.Target]?
 *
 * A: [CargoWorkspace.Package] can consist of multiple [CargoWorkspace.Target].
 *   E.g. some package can consist of common library target, multiple binariy
 *   targets that use it, test targets, benchmark targets, etc.
 *
 * Q: What is `Cargo.toml` dependency in the terms of this model?
 *
 * A: Dependency (that is `foo = "1.0"` in `Cargo.toml`) is a
 *   [CargoWorkspace.Package] (of specified name and version) with one
 *   _library_ target. See [CargoWorkspace.Package.dependencies]
 *
 * Q: What is a Rust crate in the terms of this model?
 *
 * A: It is always [CargoWorkspace.Target]. In the case of `extern crate foo;`
 *   it is a library [CargoWorkspace.Target] (with a name `foo`) of some
 *   dependency package of the current package.
 *
 * Q: How is [CargoWorkspace.Package.name] different from [CargoWorkspace.Target.name]?
 *
 * A: [CargoWorkspace.Package.name] is a name of a dependency that should be mentioned in
 *   `[dependencies]` section of `Cargo.toml`. [CargoWorkspace.Target.name] is a name
 *   that visible in the Rust code, e.g. in `extern crate` syntax. Usually they are equal.
 *   A name of a package can be specified by `[package.name]` property in `Cargo.toml`.
 *   A name of a target can be specified in sections like `[lib]`, `[[bin]]`, etc.
 *   Also, name of a dependency target can be changed.
 *   See https://github.com/rust-lang/cargo/issues/5653
 *
 *
 * See https://github.com/rust-lang/cargo/blob/master/src/cargo/core/workspace.rs
 * See https://github.com/rust-lang/cargo/blob/master/src/cargo/core/package.rs
 * See https://github.com/rust-lang/cargo/blob/d0f82841/src/cargo/core/manifest.rs#L228
 */
interface CargoProjectsService {
    val allProjects: Collection<CargoProject>
    val hasAtLeastOneValidProject: Boolean

    fun findProjectForFile(file: VirtualFile): CargoProject?
    fun findPackageForFile(file: VirtualFile): CargoWorkspace.Package?

    /**
     * @param manifest a path to `Cargo.toml` manifest of the project we want to link
     */
    fun attachCargoProject(manifest: Path): Boolean
    fun detachCargoProject(cargoProject: CargoProject)
    fun refreshAllProjects(): CompletableFuture<List<CargoProject>>
    fun discoverAndRefresh(): CompletableFuture<List<CargoProject>>

    @TestOnly
    fun createTestProject(rootDir: VirtualFile, ws: CargoWorkspace, rustcInfo: RustcInfo? = null)

    @TestOnly
    fun setRustcInfo(rustcInfo: RustcInfo)

    @TestOnly
    fun setEdition(edition: CargoWorkspace.Edition)

    @TestOnly
    fun discoverAndRefreshSync(): List<CargoProject> {
        val projects = discoverAndRefresh().get(1, TimeUnit.MINUTES)
            ?: error("Timeout when refreshing a test Cargo project")
        if (projects.isEmpty()) error("Failed to update a test Cargo project")
        return projects
    }

    companion object {
        val CARGO_PROJECTS_TOPIC: Topic<CargoProjectsListener> = Topic(
            "cargo projects changes",
            CargoProjectsListener::class.java
        )
    }

    interface CargoProjectsListener {
        fun cargoProjectsUpdated(projects: Collection<CargoProject>)
    }
}

val Project.cargoProjects get() = service<CargoProjectsService>()

/**
 * See docs for [CargoProjectsService].
 *
 * Instances of this class are immutable and will be re-created on each project refresh.
 * This class implements [UserDataHolderEx] interface and therefore any data can be attached
 * to it. Note that since instances of this class are re-created on each project refresh,
 * user data will be flushed on project refresh too
 */
interface CargoProject : UserDataHolderEx {
    val project: Project
    val manifest: Path
    val rootDir: VirtualFile?
    val workspaceRootDir: VirtualFile?

    val presentableName: String
    val workspace: CargoWorkspace?

    val rustcInfo: RustcInfo?

    val workspaceStatus: UpdateStatus
    val stdlibStatus: UpdateStatus
    val rustcInfoStatus: UpdateStatus

    val mergedStatus: UpdateStatus get() = workspaceStatus
        .merge(stdlibStatus)
        .merge(rustcInfoStatus)

    sealed class UpdateStatus(private val priority: Int) {
        object UpToDate : UpdateStatus(0)
        object NeedsUpdate : UpdateStatus(1)
        class UpdateFailed(val reason: String) : UpdateStatus(2)

        fun merge(status: UpdateStatus): UpdateStatus = if (priority >= status.priority) this else status
    }
}

data class RustcInfo(val sysroot: String, val version: RustcVersion?)

fun guessAndSetupRustProject(project: Project, explicitRequest: Boolean = false): Boolean {
    if (!explicitRequest) {
        val alreadyTried = run {
            val key = "org.rust.cargo.project.model.PROJECT_DISCOVERY"
            val properties = PropertiesComponent.getInstance(project)
            val alreadyTried = properties.getBoolean(key)
            properties.setValue(key, true)
            alreadyTried
        }
        if (alreadyTried) return false
    }

    val toolchain = project.rustSettings.toolchain
    if (toolchain == null || !toolchain.looksLikeValidToolchain()) {
        discoverToolchain(project)
        return true
    }
    if (!project.cargoProjects.hasAtLeastOneValidProject) {
        project.cargoProjects.discoverAndRefresh()
        return true
    }
    return false
}

private fun discoverToolchain(project: Project) {
    val toolchain = RustToolchain.suggest() ?: return
    ApplicationManager.getApplication().invokeLater {
        if (project.isDisposed) return@invokeLater

        val oldToolchain = project.rustSettings.toolchain
        if (oldToolchain != null && oldToolchain.looksLikeValidToolchain()) {
            return@invokeLater
        }

        runWriteAction {
            project.rustSettings.modify { it.toolchain = toolchain }
        }

        val tool = if (toolchain.isRustupAvailable) "rustup" else "Cargo at ${toolchain.presentableLocation}"
        project.showBalloon("Using $tool", NotificationType.INFORMATION)
        project.cargoProjects.discoverAndRefresh()
    }
}

fun ContentEntry.setup(contentRoot: VirtualFile) {
    val makeVfsUrl = { dirName: String -> FileUtil.join(contentRoot.url, dirName) }
    CargoConstants.ProjectLayout.sources.map(makeVfsUrl).forEach {
        addSourceFolder(it, /* test = */ false)
    }
    CargoConstants.ProjectLayout.tests.map(makeVfsUrl).forEach {
        addSourceFolder(it, /* test = */ true)
    }
    addExcludeFolder(makeVfsUrl(CargoConstants.ProjectLayout.target))
}
