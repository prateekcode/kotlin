/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.compiler.plugin.extensions

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.extensions.ProcessSourcesBeforeCompilingExtension
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionProvider
import org.jetbrains.kotlin.scripting.resolve.KtFileScriptSource
import kotlin.script.experimental.api.ScriptAcceptedLocation
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.acceptedLocations
import kotlin.script.experimental.api.ide

class ScriptingProcessSourcesBeforeCompilingExtension(val project: Project) : ProcessSourcesBeforeCompilingExtension {

    override fun processSources(sources: Collection<KtFile>, configuration: CompilerConfiguration): Collection<KtFile> {
        val versionSettings = configuration.languageVersionSettings
        val optInAllowScriptsInSourceRoots = configuration.getBoolean(CommonConfigurationKeys.ALLOW_ANY_SCRIPTS_IN_SOURCE_ROOTS)
        val shouldSkip = versionSettings.supportsFeature(LanguageFeature.SkipUnsupportedScriptsInSourceRoots)
        val definitionProvider by lazy(LazyThreadSafetyMode.NONE) { ScriptDefinitionProvider.getInstance(project) }
        val messageCollector = configuration.getNotNull(MESSAGE_COLLECTOR_KEY)
        return sources.filter { ktFile ->
            if (ktFile.isScript()) {
                if (!optInAllowScriptsInSourceRoots) {
                    val definition = definitionProvider?.findDefinition(KtFileScriptSource(ktFile))
                    val isSourceRootCompatible =
                        definition?.compilationConfiguration?.get(ScriptCompilationConfiguration.ide.acceptedLocations)?.let { locations ->
                            locations.any {
                                it == ScriptAcceptedLocation.Sources || it == ScriptAcceptedLocation.Tests
                            }
                        } ?: false
                    if (!shouldSkip && !isSourceRootCompatible) {
                        messageCollector.report(
                            CompilerMessageSeverity.WARNING,
                            "Script '${ktFile.name}' is not supposed to be used along with regular Kotlin sources, and will be ignored in the future versions"
                        )
                    }
                    !shouldSkip || isSourceRootCompatible
                } else true
            } else true
        }
    }
}