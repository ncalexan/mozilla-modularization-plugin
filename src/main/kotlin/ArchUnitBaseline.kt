/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Represents a baseline rule for architecture violations.
 */
sealed class BaselineRule {
    abstract val context: String
    abstract val targets: MutableSet<String>

    /**
     * Intralayer rule: violations between two sets within the same layer.
     */
    data class Intralayer(
        val sourceSetId: SetId,
        val targetSetId: SetId,
        val layerId: LayerId,
        override val context: String,
        override val targets: MutableSet<String> = mutableSetOf()
    ) : BaselineRule()

    /**
     * Interlayer rule: violations from a set in one layer to another layer.
     */
    data class Interlayer(
        val setId: SetId,
        val sourceLayerId: LayerId,
        val targetLayerId: LayerId,
        override val context: String,
        override val targets: MutableSet<String> = mutableSetOf()
    ) : BaselineRule()
}

/**
 * Container for all baseline rules.
 */
data class Baseline(
    val intralayerRules: MutableList<BaselineRule.Intralayer> = mutableListOf(),
    val interlayerRules: MutableList<BaselineRule.Interlayer> = mutableListOf()
)

/**
 * Checks if an intralayer target is baselined.
 */
fun Baseline.isIntralayerBaselined(layerId: LayerId, sourceSetId: SetId, targetSetId: SetId, target: String): Boolean {
    return intralayerRules
        .find { it.layerId == layerId && it.sourceSetId == sourceSetId && it.targetSetId == targetSetId }
        ?.targets
        ?.contains(target)
        ?: false
}

/**
 * Checks if an interlayer target is baselined.
 */
fun Baseline.isInterlayerBaselined(setId: SetId, sourceLayerId: LayerId, targetLayerId: LayerId, target: String): Boolean {
    return interlayerRules
        .find { it.setId == setId && it.sourceLayerId == sourceLayerId && it.targetLayerId == targetLayerId }
        ?.targets
        ?.contains(target)
        ?: false
}

/**
 * Reads a baseline from an XML file.
 */
fun readBaseline(inputStream: InputStream): Baseline {
    val baseline = Baseline()
    val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val doc = docBuilder.parse(inputStream)
    doc.documentElement.normalize()

    // Parse IntralayerRules.
    val intralayerNodes = doc.getElementsByTagName("IntralayerRules")
    if (intralayerNodes.length > 0) {
        val intralayerElement = intralayerNodes.item(0) as Element
        val ruleNodes = intralayerElement.getElementsByTagName("Rule")
        for (i in 0 until ruleNodes.length) {
            val ruleElement = ruleNodes.item(i) as Element
            val sourceSetId = SetId(ruleElement.getAttribute("source_set"))
            val targetSetId = SetId(ruleElement.getAttribute("target_set"))
            val layerId = LayerId(ruleElement.getAttribute("layer"))
            val context = ruleElement.getAttribute("context")

            val targets = mutableSetOf<String>()
            val targetNodes = ruleElement.getElementsByTagName("target")
            for (j in 0 until targetNodes.length) {
                val targetElement = targetNodes.item(j) as Element
                targets.add(targetElement.textContent)
            }

            baseline.intralayerRules.add(
                BaselineRule.Intralayer(
                    sourceSetId = sourceSetId,
                    targetSetId = targetSetId,
                    layerId = layerId,
                    context = context,
                    targets = targets
                )
            )
        }
    }

    // Parse InterlayerRules.
    val interlayerNodes = doc.getElementsByTagName("InterlayerRules")
    if (interlayerNodes.length > 0) {
        val interlayerElement = interlayerNodes.item(0) as Element
        val ruleNodes = interlayerElement.getElementsByTagName("Rule")
        for (i in 0 until ruleNodes.length) {
            val ruleElement = ruleNodes.item(i) as Element
            val sourceSetId = SetId(ruleElement.getAttribute("source_set"))
            val sourceLayerId = LayerId(ruleElement.getAttribute("source_layer"))
            val targetLayerId = LayerId(ruleElement.getAttribute("target_layer"))
            val context = ruleElement.getAttribute("context")

            val targets = mutableSetOf<String>()
            val targetNodes = ruleElement.getElementsByTagName("target")
            for (j in 0 until targetNodes.length) {
                val targetElement = targetNodes.item(j) as Element
                targets.add(targetElement.textContent)
            }

            baseline.interlayerRules.add(
                BaselineRule.Interlayer(
                    setId = sourceSetId,
                    sourceLayerId = sourceLayerId,
                    targetLayerId = targetLayerId,
                    context = context,
                    targets = targets
                )
            )
        }
    }

    return baseline
}

/**
 * Writes a baseline to an XML file.
 */
fun writeBaseline(baseline: Baseline, outputStream: OutputStream) {
    val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val doc: Document = docBuilder.newDocument()

    // Root element.
    val root = doc.createElement("ArchUnitBaseline")
    doc.appendChild(root)

    // IntralayerRules section.
    val intralayerElement = doc.createElement("IntralayerRules")
    root.appendChild(intralayerElement)

    baseline.intralayerRules.sortedWith(
        compareBy({ it.layerId.id }, { it.sourceSetId.id }, { it.targetSetId.id })
    ).forEach { rule ->
        val ruleElement = doc.createElement("Rule")
        ruleElement.setAttribute("source_set", rule.sourceSetId.id)
        ruleElement.setAttribute("target_set", rule.targetSetId.id)
        ruleElement.setAttribute("layer", rule.layerId.id)
        ruleElement.setAttribute("context", rule.context)

        rule.targets.sorted().forEach { target ->
            val targetElement = doc.createElement("target")
            targetElement.textContent = target
            ruleElement.appendChild(targetElement)
        }

        intralayerElement.appendChild(ruleElement)
    }

    // InterlayerRules section.
    val interlayerElement = doc.createElement("InterlayerRules")
    root.appendChild(interlayerElement)

    baseline.interlayerRules.sortedWith(
        compareBy({ it.sourceLayerId.id }, { it.targetLayerId.id }, { it.setId.id })
    ).forEach { rule ->
        val ruleElement = doc.createElement("Rule")
        ruleElement.setAttribute("source_set", rule.setId.id)
        ruleElement.setAttribute("source_layer", rule.sourceLayerId.id)
        ruleElement.setAttribute("target_layer", rule.targetLayerId.id)
        ruleElement.setAttribute("context", rule.context)

        rule.targets.sorted().forEach { target ->
            val targetElement = doc.createElement("target")
            targetElement.textContent = target
            ruleElement.appendChild(targetElement)
        }

        interlayerElement.appendChild(ruleElement)
    }

    // Write to file.
    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(OutputKeys.INDENT, "yes")
    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
    transformer.setOutputProperty(OutputKeys.STANDALONE, "yes")
    transformer.transform(DOMSource(doc), StreamResult(outputStream))
}
