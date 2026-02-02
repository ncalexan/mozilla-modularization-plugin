/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// SARIF format data classes (SARIF 2.1.0).
@Serializable
data class SarifReport(
    val version: String = "2.1.0",
    val `$schema`: String = "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json",
    val runs: List<SarifRun>
)

@Serializable
data class SarifRun(
    val tool: SarifTool,
    val results: List<SarifResult>
)

@Serializable
data class SarifTool(
    val driver: SarifDriver
)

@Serializable
data class SarifDriver(
    val name: String,
    val version: String = "1.0.0",
    val informationUri: String = "https://github.com/mozilla-mobile/firefox-android"
)

@Serializable
data class SarifResult(
    val ruleId: String,
    val level: String = "error",
    val message: SarifMessage,
    val locations: List<SarifLocation>
)

@Serializable
data class SarifMessage(
    val text: String
)

@Serializable
data class SarifLocation(
    val physicalLocation: SarifPhysicalLocation
)

@Serializable
data class SarifPhysicalLocation(
    val artifactLocation: SarifArtifactLocation,
    val region: SarifRegion
)

@Serializable
data class SarifArtifactLocation(
    val uri: String
)

@Serializable
data class SarifRegion(
    val startLine: Int,
    val startColumn: Int = 1
)

/**
 * Represents a single occurrence of a violation at a specific location.
 */
data class ViolationOccurrence(
    val description: String,
    val location: Pair<String, Int>,
    val level: String = "error",  // "error" or "warning"
    val isBaselined: Boolean = false
)

/**
 * Represents a specific dependency violation (e.g., "uses org.mozilla.fenix.theme.ThemeManager").
 */
data class ViolationDetail(
    val dependency: String,
    val occurrences: MutableList<ViolationOccurrence> = mutableListOf()
)

/**
 * Represents violations between sets within the same layer.
 */
data class IntraLayerViolationGroup(
    val layerId: LayerId,
    val sourceSetId: SetId,
    val targetSetId: SetId,
    val violations: MutableMap<String, ViolationDetail> = mutableMapOf()
) {
    fun addViolation(
        dependency: String,
        description: String,
        location: Pair<String, Int>,
        level: String = "error",
        isBaselined: Boolean = false
    ) {
        val detail = violations.getOrPut(dependency) {
            ViolationDetail(dependency)
        }
        detail.occurrences.add(ViolationOccurrence(description, location, level, isBaselined))
    }
}

/**
 * Represents violations from a set to a layer (interlayer dependencies).
 */
data class InterLayerViolationGroup(
    val setId: SetId,
    val sourceLayerId: LayerId,
    val targetLayerId: LayerId,
    val violations: MutableMap<String, ViolationDetail> = mutableMapOf()
) {
    fun addViolation(
        dependency: String,
        description: String,
        location: Pair<String, Int>,
        level: String = "error",
        isBaselined: Boolean = false
    ) {
        val detail = violations.getOrPut(dependency) {
            ViolationDetail(dependency)
        }
        detail.occurrences.add(ViolationOccurrence(description, location, level, isBaselined))
    }
}

/**
 * Container for all architecture violations.
 */
data class ArchitectureViolations(
    val intralayerViolations: MutableList<IntraLayerViolationGroup> = mutableListOf(),
    val interlayerViolations: MutableMap<LayerId, MutableList<InterLayerViolationGroup>> = mutableMapOf()
) {
    fun addIntralayerViolation(
        layerId: LayerId,
        sourceSetId: SetId,
        targetSetId: SetId,
        dependency: String,
        description: String,
        location: Pair<String, Int>,
        level: String = "error",
        isBaselined: Boolean = false
    ) {
        val group =
            intralayerViolations.find {
                it.sourceSetId == sourceSetId && it.targetSetId == targetSetId && it.layerId == layerId
            }
                ?: IntraLayerViolationGroup(
                    layerId,
                    sourceSetId,
                    targetSetId
                ).also { intralayerViolations.add(it) }
        group.addViolation(dependency, description, location, level, isBaselined)
    }

    fun addInterlayerViolation(
        setId: SetId,
        sourceLayerId: LayerId,
        targetLayerId: LayerId,
        dependency: String,
        description: String,
        location: Pair<String, Int>,
        level: String = "error",
        isBaselined: Boolean = false
    ) {
        val layerGroups = interlayerViolations.getOrPut(sourceLayerId) { mutableListOf() }
        val group = layerGroups.find { it.setId == setId && it.targetLayerId == targetLayerId }
            ?: InterLayerViolationGroup(setId, sourceLayerId, targetLayerId).also {
                layerGroups.add(
                    it
                )
            }
        group.addViolation(dependency, description, location, level, isBaselined)
    }

    fun hasViolations(): Boolean {
        return intralayerViolations.isNotEmpty() || interlayerViolations.isNotEmpty()
    }

    fun getTotalViolationCount(): Int {
        val intralayerCount = intralayerViolations.sumOf { group ->
            group.violations.values.sumOf { it.occurrences.size }
        }
        val interlayerCount = interlayerViolations.values.sumOf { layerGroups ->
            layerGroups.sumOf { group ->
                group.violations.values.sumOf { it.occurrences.size }
            }
        }
        return intralayerCount + interlayerCount
    }

    fun getErrorCount(): Int {
        val intralayerCount = intralayerViolations.sumOf { group ->
            group.violations.values.sumOf { detail ->
                detail.occurrences.count { it.level == "error" }
            }
        }
        val interlayerCount = interlayerViolations.values.sumOf { layerGroups ->
            layerGroups.sumOf { group ->
                group.violations.values.sumOf { detail ->
                    detail.occurrences.count { it.level == "error" }
                }
            }
        }
        return intralayerCount + interlayerCount
    }

    fun getWarningCount(): Int {
        val intralayerCount = intralayerViolations.sumOf { group ->
            group.violations.values.sumOf { detail ->
                detail.occurrences.count { it.level == "warning" }
            }
        }
        val interlayerCount = interlayerViolations.values.sumOf { layerGroups ->
            layerGroups.sumOf { group ->
                group.violations.values.sumOf { detail ->
                    detail.occurrences.count { it.level == "warning" }
                }
            }
        }
        return intralayerCount + interlayerCount
    }

    fun getNewViolationCount(): Int {
        val intralayerCount = intralayerViolations.sumOf { group ->
            group.violations.values.sumOf { detail ->
                detail.occurrences.count { !it.isBaselined }
            }
        }
        val interlayerCount = interlayerViolations.values.sumOf { layerGroups ->
            layerGroups.sumOf { group ->
                group.violations.values.sumOf { detail ->
                    detail.occurrences.count { !it.isBaselined }
                }
            }
        }
        return intralayerCount + interlayerCount
    }

    fun getBaselinedViolationCount(): Int {
        val intralayerCount = intralayerViolations.sumOf { group ->
            group.violations.values.sumOf { detail ->
                detail.occurrences.count { it.isBaselined }
            }
        }
        val interlayerCount = interlayerViolations.values.sumOf { layerGroups ->
            layerGroups.sumOf { group ->
                group.violations.values.sumOf { detail ->
                    detail.occurrences.count { it.isBaselined }
                }
            }
        }
        return intralayerCount + interlayerCount
    }

    fun getNewErrorCount(): Int {
        val intralayerCount = intralayerViolations.sumOf { group ->
            group.violations.values.sumOf { detail ->
                detail.occurrences.count { !it.isBaselined && it.level == "error" }
            }
        }
        val interlayerCount = interlayerViolations.values.sumOf { layerGroups ->
            layerGroups.sumOf { group ->
                group.violations.values.sumOf { detail ->
                    detail.occurrences.count { !it.isBaselined && it.level == "error" }
                }
            }
        }
        return intralayerCount + interlayerCount
    }

    fun getNewWarningCount(): Int {
        val intralayerCount = intralayerViolations.sumOf { group ->
            group.violations.values.sumOf { detail ->
                detail.occurrences.count { !it.isBaselined && it.level == "warning" }
            }
        }
        val interlayerCount = interlayerViolations.values.sumOf { layerGroups ->
            layerGroups.sumOf { group ->
                group.violations.values.sumOf { detail ->
                    detail.occurrences.count { !it.isBaselined && it.level == "warning" }
                }
            }
        }
        return intralayerCount + interlayerCount
    }
}

/**
 * Generates HTML and SARIF reports from architecture violations.
 */
class ArchUnitReportGenerator {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun generateHtmlReport(violations: ArchitectureViolations, outputFile: File) {
        outputFile.parentFile.mkdirs()

        val html = buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"en\">")
            appendLine("<head>")
            appendLine("    <meta charset=\"UTF-8\">")
            appendLine("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
            appendLine("    <title>Modularization Architecture Report</title>")
            appendLine("    <style>")
            appendLine(generateCss())
            appendLine("    </style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("    <div class=\"container\">")
            appendLine("        <h1>Modularization Architecture Report</h1>")

            if (!violations.hasViolations()) {
                appendLine("        <div class=\"success\">")
                appendLine("            <h2>✓ No Violations Found</h2>")
                appendLine("            <p>All architecture rules are satisfied.</p>")
                appendLine("        </div>")
            } else {
                val newCount = violations.getNewViolationCount()
                val baselinedCount = violations.getBaselinedViolationCount()
                val newErrors = violations.getNewErrorCount()
                val newWarnings = violations.getNewWarningCount()

                appendLine("        <div class=\"summary\">")
                appendLine("            <h2>Summary</h2>")
                appendLine("            <p><strong>New Violations:</strong> $newCount ($newErrors error(s), $newWarnings warning(s))</p>")
                appendLine("            <p><strong>Baselined Violations:</strong> $baselinedCount</p>")
                appendLine("        </div>")

                // New intralayer violations.
                if (violations.intralayerViolations.any { group ->
                        group.violations.values.any { detail -> detail.occurrences.any { !it.isBaselined } }
                    }) {
                    appendLine("        <div class=\"section\">")
                    appendLine("            <h2>New Intralayer Violations (Set Independence)</h2>")
                    appendLine("            <p class=\"description\">Sets within the same layer should be independent of each other.</p>")
                    // Sort groups by sourceSetId, then targetSetId.
                    violations.intralayerViolations.sortedWith(
                        compareBy(
                            { it.layerId.id },
                            { it.sourceSetId.id },
                            { it.targetSetId.id })
                    ).forEach { group ->
                        generateViolationGroup(
                            group,
                            "Set \"${group.sourceSetId.id}\" should not depend on Set \"${group.targetSetId.id}\" in Layer \"${group.layerId.id}\"",
                            false  // showOnlyNew = true, showOnlyBaselined = false
                        )
                    }
                    appendLine("        </div>")
                }

                // New interlayer violations.
                if (violations.interlayerViolations.values.any { groups ->
                        groups.any { group -> group.violations.values.any { detail -> detail.occurrences.any { !it.isBaselined } } }
                    }) {
                    appendLine("        <div class=\"section\">")
                    appendLine("            <h2>New Interlayer Violations (Layer Dependencies)</h2>")
                    appendLine("            <p class=\"description\">Layers should only depend on layers below them in the architecture.</p>")
                    // Sort by source layer, then by groups within each layer.
                    violations.interlayerViolations.toSortedMap(
                        compareBy(
                            { it.id }
                        )
                    )
                        .forEach { (sourceLayerId, groups) ->
                            appendLine("            <h3>$sourceLayerId</h3>")
                            // Sort groups by setId, then targetLayerId.
                            groups.sortedWith(
                                compareBy(
                                    { it.setId.id },
                                    { it.sourceLayerId.id },
                                    { it.targetLayerId.id })
                            )
                                .forEach { group ->
                                    generateViolationGroup(
                                        group,
                                        "Set \"${group.setId.id}\" in Layer \"${group.sourceLayerId.id}\" should not depend on Layer \"${group.targetLayerId.id}\"",
                                        false  // showOnlyNew = true, showOnlyBaselined = false
                                    )
                                }
                        }
                    appendLine("        </div>")
                }

                // Baselined violations section.
                if (baselinedCount > 0) {
                    appendLine("        <div class=\"section\">")
                    appendLine("            <h2>Baselined Violations</h2>")
                    appendLine("            <p class=\"description\">These violations are in the baseline and will not fail the build.</p>")

                    // Baselined intralayer violations.
                    if (violations.intralayerViolations.any { group ->
                            group.violations.values.any { detail -> detail.occurrences.any { it.isBaselined } }
                        }) {
                        appendLine("            <h3>Intralayer Violations</h3>")
                        violations.intralayerViolations.sortedWith(
                            compareBy(
                                { it.layerId.id },
                                { it.sourceSetId.id },
                                { it.targetSetId.id })
                        ).forEach { group ->
                            generateViolationGroup(
                                group,
                                "Set \"${group.sourceSetId}\" should not depend on Set \"${group.targetSetId}\"",
                                true  // showOnlyNew = false, showOnlyBaselined = true
                            )
                        }
                    }

                    // Baselined interlayer violations.
                    if (violations.interlayerViolations.values.any { groups ->
                            groups.any { group -> group.violations.values.any { detail -> detail.occurrences.any { it.isBaselined } } }
                        }) {
                        appendLine("            <h3>Interlayer Violations</h3>")
                        violations.interlayerViolations.toSortedMap(compareBy { it.id })
                            .forEach { (sourceLayerId, groups) ->
                                appendLine("            <h4>$sourceLayerId</h4>")
                                groups.sortedWith(
                                    compareBy(
                                        { it.setId.id },
                                        { it.sourceLayerId.id },
                                        { it.targetLayerId.id })
                                )
                                    .forEach { group ->
                                        generateViolationGroup(
                                            group,
                                            "Set \"${group.setId}\" should not depend on Layer \"${group.targetLayerId}\"",
                                            true  // showOnlyNew = false, showOnlyBaselined = true
                                        )
                                    }
                            }
                    }

                    appendLine("        </div>")
                }
            }

            appendLine("    </div>")
            appendLine("</body>")
            appendLine("</html>")
        }

        outputFile.writeText(html)
    }

    private fun StringBuilder.generateViolationGroup(
        group: IntraLayerViolationGroup,
        title: String,
        showOnlyBaselined: Boolean
    ) {
        generateViolationGroupImpl(group.violations, title, showOnlyBaselined)
    }

    private fun StringBuilder.generateViolationGroup(
        group: InterLayerViolationGroup,
        title: String,
        showOnlyBaselined: Boolean
    ) {
        generateViolationGroupImpl(group.violations, title, showOnlyBaselined)
    }

    private fun StringBuilder.generateViolationGroupImpl(
        violations: Map<String, ViolationDetail>,
        title: String,
        showOnlyBaselined: Boolean
    ) {
        // Filter occurrences based on baseline status.
        val filteredViolations = violations.mapNotNull { (dependency, detail) ->
            val filteredOccurrences = detail.occurrences.filter { occurrence ->
                if (showOnlyBaselined) occurrence.isBaselined else !occurrence.isBaselined
            }
            if (filteredOccurrences.isNotEmpty()) {
                dependency to filteredOccurrences
            } else {
                null
            }
        }.toMap()

        // Only render if there are filtered violations.
        if (filteredViolations.isEmpty()) {
            return
        }

        val cssClass = if (showOnlyBaselined) "violation-group-baselined" else "violation-group"
        appendLine("            <div class=\"$cssClass\">")
        appendLine("                <h4>$title</h4>")
        // Sort dependencies alphabetically.
        filteredViolations.toSortedMap().forEach { (dependency, occurrences) ->
            appendLine("                <div class=\"violation-detail\">")
            appendLine(
                "                    <div class=\"dependency\">Uses: <code>${
                    escapeHtml(
                        dependency
                    )
                }</code></div>"
            )
            appendLine("                    <ul class=\"occurrences\">")
            // Sort occurrences by location.
            occurrences.sortedWith(compareBy({ it.location.first }, { it.location.second }))
                .forEach { occurrence ->
                    appendLine("                        <li>")
                    appendLine(
                        "                            <div class=\"occurrence-location\">${
                            escapeHtml(
                                occurrence.location.first
                            )
                        }:${escapeHtml(occurrence.location.second.toString())}</div>"
                    )
                    appendLine(
                        "                            <div class=\"occurrence-description\">${
                            escapeHtml(
                                occurrence.description
                            )
                        }</div>"
                    )
                    appendLine("                        </li>")
                }
            appendLine("                    </ul>")
            appendLine("                </div>")
        }
        appendLine("            </div>")
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun generateCss(): String {
        return """
            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
                line-height: 1.6;
                color: #333;
                background-color: #f5f5f5;
                margin: 0;
                padding: 20px;
            }
            .container {
                max-width: 1200px;
                margin: 0 auto;
                background-color: white;
                padding: 30px;
                border-radius: 8px;
                box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            }
            h1 {
                color: #2c3e50;
                border-bottom: 3px solid #3498db;
                padding-bottom: 10px;
                margin-bottom: 30px;
            }
            h2 {
                color: #34495e;
                margin-top: 30px;
                margin-bottom: 15px;
            }
            h3 {
                color: #7f8c8d;
                margin-top: 25px;
                margin-bottom: 10px;
            }
            h4 {
                color: #e74c3c;
                margin-top: 0;
                margin-bottom: 15px;
                font-size: 1.1em;
            }
            .summary {
                background-color: #fff3cd;
                border-left: 4px solid #ffc107;
                padding: 15px;
                margin-bottom: 30px;
                border-radius: 4px;
            }
            .success {
                background-color: #d4edda;
                border-left: 4px solid #28a745;
                padding: 15px;
                margin-bottom: 30px;
                border-radius: 4px;
            }
            .section {
                margin-bottom: 40px;
            }
            .description {
                color: #6c757d;
                font-style: italic;
                margin-bottom: 20px;
            }
            .violation-group {
                background-color: #fff5f5;
                border-left: 4px solid #e74c3c;
                padding: 20px;
                margin-bottom: 20px;
                border-radius: 4px;
            }
            .violation-group-baselined {
                background-color: #f5f5f5;
                border-left: 4px solid #95a5a6;
                padding: 20px;
                margin-bottom: 20px;
                border-radius: 4px;
                opacity: 0.7;
            }
            .violation-detail {
                margin-bottom: 20px;
                padding: 10px;
                background-color: white;
                border-radius: 4px;
            }
            .dependency {
                font-weight: bold;
                color: #2c3e50;
                margin-bottom: 10px;
            }
            code {
                background-color: #f8f9fa;
                padding: 2px 6px;
                border-radius: 3px;
                font-family: 'Courier New', Courier, monospace;
                font-size: 0.9em;
                color: #d63384;
            }
            .occurrences {
                list-style-type: none;
                padding-left: 0;
                margin: 0;
            }
            .occurrences li {
                padding: 10px;
                margin-bottom: 5px;
                background-color: #f8f9fa;
                border-radius: 4px;
                border-left: 3px solid #6c757d;
            }
            .occurrence-description {
                color: #495057;
                margin-bottom: 5px;
            }
            .occurrence-location {
                color: #6c757d;
                font-size: 0.9em;
                font-family: 'Courier New', Courier, monospace;
            }
        """.trimIndent()
    }

    /**
     * Generates a SARIF report from architecture violations.
     */
    fun generateSarifReport(violations: ArchitectureViolations, outputFile: File) {
        outputFile.parentFile.mkdirs()

        val results = mutableListOf<SarifResult>()

        // Process intralayer violations (only new violations, not baselined).
        violations.intralayerViolations.sortedWith(
            compareBy(
                { it.layerId.id },
                { it.sourceSetId.id },
                { it.targetSetId.id })
        )
            .forEach { group ->
                group.violations.toSortedMap().forEach { (dependency, detail) ->
                    detail.occurrences
                        .filter { !it.isBaselined }  // Only include new violations.
                        .sortedWith(
                            compareBy(
                                { it.location.first },
                                { it.location.second })
                        ).forEach { occurrence ->
                            results.add(
                                SarifResult(
                                    ruleId = "intralayer-dependency/${group.sourceSetId}-${group.targetSetId}",
                                    level = occurrence.level,
                                    message = SarifMessage(
                                        text = "Set '${group.sourceSetId}' should not depend on Set '${group.targetSetId}': ${occurrence.description}"
                                    ),
                                    locations = listOf(
                                        SarifLocation(
                                            physicalLocation = SarifPhysicalLocation(
                                                artifactLocation = SarifArtifactLocation(uri = occurrence.location.first),
                                                region = SarifRegion(
                                                    startLine = maxOf(
                                                        1,
                                                        occurrence.location.second
                                                    ),
                                                    startColumn = 1
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        }
                }
            }

        // Process interlayer violations (only new violations, not baselined).
        violations.interlayerViolations.toSortedMap(compareBy { it.id })
            .forEach { (sourceLayerId, groups) ->
                groups.sortedWith(
                    compareBy(
                        { it.setId.id },
                        { it.sourceLayerId.id },
                        { it.targetLayerId.id })
                ).forEach { group ->
                    group.violations.toSortedMap().forEach { (dependency, detail) ->
                        detail.occurrences
                            .filter { !it.isBaselined }  // Only include new violations.
                            .sortedWith(
                                compareBy(
                                    { it.location.first },
                                    { it.location.second })
                            ).forEach { occurrence ->
                                results.add(
                                    SarifResult(
                                        ruleId = "interlayer-dependency/${group.setId}-${group.targetLayerId}",
                                        level = occurrence.level,
                                        message = SarifMessage(
                                            text = "$sourceLayerId: Set '${group.setId}' should not depend on Layer '${group.targetLayerId}': ${occurrence.description}"
                                        ),
                                        locations = listOf(
                                            SarifLocation(
                                                physicalLocation = SarifPhysicalLocation(
                                                    artifactLocation = SarifArtifactLocation(uri = occurrence.location.first),
                                                    region = SarifRegion(
                                                        startLine = maxOf(
                                                            1,
                                                            occurrence.location.second
                                                        ),
                                                        startColumn = 1
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            }
                    }
                }
            }

        val sarif = SarifReport(
            runs = listOf(
                SarifRun(
                    tool = SarifTool(
                        driver = SarifDriver(
                            name = "ArchUnit Modularization Checker"
                        )
                    ),
                    results = results
                )
            )
        )

        outputFile.writeText(json.encodeToString(sarif))
    }
}
