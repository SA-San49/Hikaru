package eu.kanade.tachiyomi.ui.browse.source

import android.app.Application
import eu.kanade.presentation.browse.SourceUiModel
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.model.NodeStatus
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.domain.source.model.Source
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

class SourceUiModelMapper(
    private val context: Application = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
) {
    private val displayNameCache = ConcurrentHashMap<String, String>()

    private fun getCachedDisplayName(lang: String): String {
        return displayNameCache.getOrPut(lang) {
            LocaleHelper.getSourceDisplayName(lang, context)
        }
    }

    fun map(
        source: Source,
        headerKey: String = "",
        isNsfw: Boolean = source.isNsfw,
        status: NodeStatus? = null,
    ): SourceUiModel.Item {
        val extensionName = extensionManager.getExtensionNameForSource(source.id)
        val sourceLangString = getCachedDisplayName(source.lang)
        val nameLower = source.name.lowercase()
        val isBdix = false
        
        val sourceClass = source.javaClass.simpleName
        val isApi = nameLower.contains("api") || 
                    nameLower.contains("json") || 
                    sourceClass.contains("Api") || 
                    sourceClass.contains("Json")

        val secondaryText = buildString {
            append(sourceLangString)
            if (extensionName != null && extensionName != source.name) {
                append(" • ")
                append(extensionName)
            }
        }

        return SourceUiModel.Item(
            source = source,
            headerKey = headerKey,
            isNsfw = isNsfw,
            status = status,
            isBdix = isBdix,
            isApi = isApi,
            isStub = source.isStub,
            displayName = source.name.ifBlank { source.id.toString() },
            secondaryText = secondaryText,
        )
    }

    fun mapHeader(lang: String): SourceUiModel.Header {
        return SourceUiModel.Header(
            language = lang,
            displayName = getCachedDisplayName(lang),
        )
    }
}
