/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.HasAndroidTest
import com.android.build.api.variant.HasUnitTest
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.gradle.BasePlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * This custom plugin registers archUnit tasks for analyzing architecture violations.
 */
class ModularizationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withType(BasePlugin::class.java) {
            val androidComponents =
                project.extensions.getByType(AndroidComponentsExtension::class.java)
            androidComponents.onVariants { variant ->
                val archUnitTaskProvider = project.tasks.register<ArchUnitTask>(
                    "archUnit${variant.name.replaceFirstChar { it.uppercase() }}"
                )

                // Main classes (variant)
                variant.artifacts
                    .forScope(ScopedArtifacts.Scope.PROJECT)
                    .use(archUnitTaskProvider)
                    .toGet(
                        ScopedArtifact.CLASSES,
                        ArchUnitTask::mainJars,
                        ArchUnitTask::mainDirs,
                    )

                variant.components.forEach {
                    (it as? HasUnitTest)?.unitTest?.artifacts?.forScope(ScopedArtifacts.Scope.PROJECT)
                        ?.use(archUnitTaskProvider)?.toGet(
                            ScopedArtifact.CLASSES,
                            ArchUnitTask::unitTestJars,
                            ArchUnitTask::unitTestDirs,
                        )
                    (it as? HasAndroidTest)?.androidTest?.artifacts?.forScope(ScopedArtifacts.Scope.PROJECT)
                        ?.use(archUnitTaskProvider)?.toGet(
                            ScopedArtifact.CLASSES,
                            ArchUnitTask::androidTestJars,
                            ArchUnitTask::androidTestDirs,
                        )

                }

                archUnitTaskProvider.configure {
                    it.reportOutputFileHTML.convention(
                        project.layout.buildDirectory.file(
                            "reports/modularization/${variant.name}/report.html"
                        )
                    )
                    it.reportOutputFileSARIF.convention(
                        project.layout.buildDirectory.file(
                            "reports/modularization/${variant.name}/report.sarif.json"
                        )
                    )
                }

                // Register baseline creation task.
                val archUnitCreateBaselineTaskProvider = project.tasks.register<ArchUnitCreateBaselineTask>(
                    "archUnitCreateBaseline${variant.name.replaceFirstChar { it.uppercase() }}"
                )

                // Main classes (variant)
                variant.artifacts
                    .forScope(ScopedArtifacts.Scope.PROJECT)
                    .use(archUnitCreateBaselineTaskProvider)
                    .toGet(
                        ScopedArtifact.CLASSES,
                        ArchUnitCreateBaselineTask::mainJars,
                        ArchUnitCreateBaselineTask::mainDirs,
                    )

                variant.components.forEach {
                    (it as? HasUnitTest)?.unitTest?.artifacts?.forScope(ScopedArtifacts.Scope.PROJECT)
                        ?.use(archUnitCreateBaselineTaskProvider)?.toGet(
                            ScopedArtifact.CLASSES,
                            ArchUnitCreateBaselineTask::unitTestJars,
                            ArchUnitCreateBaselineTask::unitTestDirs,
                        )
                    (it as? HasAndroidTest)?.androidTest?.artifacts?.forScope(ScopedArtifacts.Scope.PROJECT)
                        ?.use(archUnitCreateBaselineTaskProvider)?.toGet(
                            ScopedArtifact.CLASSES,
                            ArchUnitCreateBaselineTask::androidTestJars,
                            ArchUnitCreateBaselineTask::androidTestDirs,
                        )
                }
            }
        }
    }
}
