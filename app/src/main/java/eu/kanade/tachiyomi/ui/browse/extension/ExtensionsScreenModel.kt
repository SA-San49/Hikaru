package eu.kanade.tachiyomi.ui.browse.extension

import android.app.Application
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.extension.interactor.GetExtensionsByType
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.SEARCH_DEBOUNCE_MILLIS
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

class ExtensionsScreenModel(
    preferences: SourcePreferences = Injekt.get(),
    basePreferences: BasePreferences = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val getExtensions: GetExtensionsByType = Injekt.get(),
) : StateScreenModel<ExtensionsScreenModel.State>(State()) {

    private val currentDownloads = MutableStateFlow<Map<String, InstallStep>>(hashMapOf())
    private val displayNameCache = ConcurrentHashMap<String, String>()

    init {
        val context = Injekt.get<Application>()
        val getDisplayName: (String) -> String = { lang ->
            displayNameCache.getOrPut(lang) {
                LocaleHelper.getSourceDisplayName(lang, context)
            }
        }
        val extensionMapper: (Map<String, InstallStep>) -> ((Extension) -> ExtensionUiModel.Item) = { map ->
            {
                val author = when (it) {
                    is Extension.Available -> it.author
                    is Extension.Installed -> it.author
                    is Extension.Untrusted -> it.author
                }
                val langDisplay = if (it is Extension.Installed && it.lang.isNotEmpty()) {
                    getDisplayName(it.lang)
                } else {
                    null
                }
                ExtensionUiModel.Item(
                    extension = it,
                    installStep = map[it.pkgName] ?: InstallStep.Idle,
                    repoName = author,
                    langDisplayName = langDisplay,
                )
            }
        }
        val queryFilter: (String) -> ((Extension) -> Boolean) = { query ->
            filter@{ extension ->
                if (query.isEmpty()) return@filter true
                query.split(",").any { _input ->
                    val input = _input.trim()
                    if (input.isEmpty()) return@any false
                    when (extension) {
                        is Extension.Available -> {
                            extension.sources.any {
                                it.name.contains(input, ignoreCase = true) ||
                                    it.baseUrl.contains(input, ignoreCase = true) ||
                                    it.id == input.toLongOrNull()
                            } ||
                                extension.name.contains(input, ignoreCase = true)
                        }
                        is Extension.Installed -> {
                            extension.sources.any {
                                it.name.contains(input, ignoreCase = true) ||
                                    it.id == input.toLongOrNull() ||
                                    if (it is HttpSource) {
                                        it.baseUrl.contains(
                                            input,
                                            ignoreCase = true,
                                        )
                                    } else {
                                        false
                                    }
                            } ||
                                extension.name.contains(input, ignoreCase = true)
                        }
                        is Extension.Untrusted -> extension.name.contains(
                            input,
                            ignoreCase = true,
                        )
                    }
                }
            }
        }

        screenModelScope.launchIO {
            combine(
                state.map { it.searchQuery }.distinctUntilChanged().debounce(SEARCH_DEBOUNCE_MILLIS),
                state.map { it.nsfwOnly }.distinctUntilChanged(),
                currentDownloads.distinctUntilChanged(),
                getExtensions.subscribe().distinctUntilChanged(),
            ) { query: String?, nsfwOnly: Boolean, downloads: Map<String, InstallStep>, extensions: eu.kanade.domain.extension.model.Extensions ->
                withContext(Dispatchers.Default) {
                    val (_updates, _installed, _available, _untrusted) = extensions
                    val searchQuery = query ?: ""
                    val filterFn = queryFilter(searchQuery)
                    val mapperFn = extensionMapper(downloads)

                    val itemsGroups = linkedMapOf<ExtensionUiModel.Header, ImmutableList<ExtensionUiModel.Item>>()

                    val updates = _updates
                        .filter { !nsfwOnly || it.isNsfw }
                        .filter(filterFn)
                        .map(mapperFn)
                        .toImmutableList()
                    if (updates.isNotEmpty()) {
                        itemsGroups[ExtensionUiModel.Header.Resource(MR.strings.ext_updates_pending)] = updates
                    }

                    val installed = _installed
                        .filter { !nsfwOnly || it.isNsfw }
                        .filter(filterFn)
                        .map(mapperFn)
                    val untrusted = _untrusted
                        .filter { !nsfwOnly || it.isNsfw }
                        .filter(filterFn)
                        .map(mapperFn)
                    if (installed.isNotEmpty() || untrusted.isNotEmpty()) {
                        itemsGroups[ExtensionUiModel.Header.Resource(MR.strings.ext_installed)] = (installed + untrusted).toImmutableList()
                    }

                    yield()

                    val languagesWithExtensions = _available
                        .filter { !nsfwOnly || it.isNsfw }
                        .filter(filterFn)
                        .groupBy { it.lang }
                        .toSortedMap(LocaleHelper.comparator)
                        .map { (lang, exts) ->
                            ExtensionUiModel.Header.Text(getDisplayName(lang)) to exts.map(mapperFn).toImmutableList()
                        }

                    if (languagesWithExtensions.isNotEmpty()) {
                        itemsGroups.putAll(languagesWithExtensions)
                    }

                    itemsGroups.toImmutableMap()
                }
            }
                .collectLatest {
                    mutableState.update { state ->
                        if (!state.isLoading && state.items == it) {
                            state
                        } else {
                            state.copy(
                                isLoading = false,
                                items = it,
                            )
                        }
                    }
                }
        }
        screenModelScope.launchIO { findAvailableExtensions() }

        preferences.animeExtensionUpdatesCount().changes()
            .onEach { mutableState.update { state -> state.copy(updates = it) } }
            .launchIn(screenModelScope)

        basePreferences.extensionInstaller().changes()
            .onEach { mutableState.update { state -> state.copy(installer = it) } }
            .launchIn(screenModelScope)
    }

    fun search(query: String?) {
        mutableState.update {
            it.copy(searchQuery = query)
        }
    }

    fun toggleNsfwOnly() {
        mutableState.update { it.copy(nsfwOnly = !it.nsfwOnly) }
    }

    fun updateAllExtensions() {
        screenModelScope.launchIO {
            state.value.items.values.flatten()
                .map { it.extension }
                .filterIsInstance<Extension.Installed>()
                .filter { it.hasUpdate }
                .forEach(::updateExtension)
        }
    }

    fun installExtension(extension: Extension.Available) {
        screenModelScope.launchIO {
            extensionManager.installExtension(extension).collectToInstallUpdate(extension)
        }
    }

    fun updateExtension(extension: Extension.Installed) {
        screenModelScope.launchIO {
            extensionManager.updateExtension(extension).collectToInstallUpdate(extension)
        }
    }

    fun cancelInstallUpdateExtension(extension: Extension) {
        extensionManager.cancelInstallUpdateExtension(extension)
    }

    private fun addDownloadState(extension: Extension, installStep: InstallStep) {
        currentDownloads.update { it + Pair(extension.pkgName, installStep) }
    }

    private fun removeDownloadState(extension: Extension) {
        currentDownloads.update { it - extension.pkgName }
    }

    private suspend fun Flow<InstallStep>.collectToInstallUpdate(extension: Extension) =
        this
            .onEach { installStep -> addDownloadState(extension, installStep) }
            .onCompletion { removeDownloadState(extension) }
            .collect()

    fun uninstallExtension(extension: Extension) {
        extensionManager.uninstallExtension(extension)
    }

    fun findAvailableExtensions() {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true) }
            extensionManager.findAvailableExtensions()

            // Short delay so pull-to-refresh feedback remains clear without sluggish startup
            delay(250.milliseconds)

            mutableState.update { it.copy(isRefreshing = false) }
        }
    }

    fun trustExtension(extension: Extension.Untrusted) {
        screenModelScope.launch {
            extensionManager.trust(extension)
        }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val items: ItemGroups = persistentMapOf(),
        val updates: Int = 0,
        val installer: BasePreferences.ExtensionInstaller? = null,
        val searchQuery: String? = null,
        val nsfwOnly: Boolean = false,
    ) {
        val isEmpty = items.isEmpty()
    }
}

typealias ItemGroups = ImmutableMap<ExtensionUiModel.Header, ImmutableList<ExtensionUiModel.Item>>

object ExtensionUiModel {
    sealed interface Header {
        data class Resource(val textRes: StringResource) : Header
        data class Text(val text: String) : Header
    }
    data class Item(
        val extension: Extension,
        val installStep: InstallStep,
        val repoName: String? = null,
        val langDisplayName: String? = null,
    )
}
