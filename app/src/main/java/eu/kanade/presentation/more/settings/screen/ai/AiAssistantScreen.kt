package eu.kanade.presentation.more.settings.screen.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.presentation.anime.components.MarkdownRender
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.more.settings.screen.ai.AiAssistantScreenModel
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private data class AssistantQuickPrompt(
    val title: String,
    val prompt: String,
)

private val assistantQuickPrompts = listOf(
    AssistantQuickPrompt("My library", "Summarize my library and recommend a few titles from my collection."),
    AssistantQuickPrompt("Watch history", "Summarize my recent watch history."),
    AssistantQuickPrompt("Browse sources", "Show my visible browse sources and recent source activity."),
    AssistantQuickPrompt("Airing schedule", "What anime are airing next from my cached schedule?"),
    AssistantQuickPrompt("Saved feeds", "Summarize my saved feed setup and searches."),
)

class AiAssistantScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { AiAssistantScreenModel() }
        val state by screenModel.state.collectAsState()
        val sessions by screenModel.sessions.collectAsState()
        val preferences = remember { Injekt.get<AiPreferences>() }
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val context = androidx.compose.ui.platform.LocalContext.current
        val listState = rememberLazyListState()
        val snackbarHostState = remember { SnackbarHostState() }
        var input by remember { mutableStateOf("") }
        val engine by preferences.aiEngine().changes().collectAsState(initial = preferences.aiEngine().get())
        val aiEnabled by preferences.enableAi().changes().collectAsState(initial = preferences.enableAi().get())
        val assistantEnabled by preferences.enableAiAssistant().changes().collectAsState(initial = preferences.enableAiAssistant().get())

        LaunchedEffect(state.messages.size, state.streamingMessage?.length?.div(48), state.isLoading) {
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            if (lastIndex >= 0) listState.scrollToItem(lastIndex)
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    drawerTonalElevation = 0.dp,
                ) {
                    Text(
                        text = "Chats",
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        label = { Text("New chat") },
                        selected = false,
                        onClick = {
                            screenModel.createNewSession()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                    LazyColumn(modifier = Modifier.fillMaxHeight()) {
                        items(sessions, key = { it.id }) { session ->
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.History, contentDescription = null) },
                                label = {
                                    Text(
                                        text = session.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                selected = state.activeSessionId == session.id,
                                onClick = {
                                    screenModel.switchSession(session.id)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                                badge = {
                                    IconButton(
                                        onClick = { screenModel.deleteSession(session.id) },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete chat",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            },
        ) {
            Scaffold(
                topBar = {
                    AppBar(
                        title = "Diagnostic Assistant",
                        subtitle = providerName(engine),
                        navigateUp = { navigator.pop() },
                        actions = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Chat history")
                            }
                        },
                    )
                },
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                contentWindowInsets = WindowInsets(0),
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding())
                        .imePadding(),
                ) {
                    AssistantProviderStatus(
                        provider = providerName(engine),
                        aiEnabled = aiEnabled,
                        assistantEnabled = assistantEnabled,
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (state.messages.isEmpty() && !state.isLoading) {
                            item(key = "welcome") {
                                WelcomePanel(
                                    onPrompt = { prompt ->
                                        screenModel.sendMessage(prompt)
                                    },
                                )
                            }
                        }
                        items(state.messages, key = { it.id }) { message ->
                            if (message.role == "user") {
                                UserMessage(message.content)
                            } else {
                                AssistantMessage(
                                    content = message.content,
                                    onCopy = { copyText ->
                                        context.copyToClipboard("Hikaru Assistant", copyText)
                                        scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                                    },
                                )
                            }
                        }
                        state.streamingMessage?.let { content ->
                            item(key = "streaming") {
                                StreamingAssistantMessage(content)
                            }
                        }
                        if (state.isLoading && state.streamingMessage.isNullOrBlank()) {
                            item(key = "thinking") {
                                ProcessingIndicator()
                            }
                        }
                    }
                    ChatInput(
                        value = input,
                        onValueChange = { input = it },
                        isLoading = state.isLoading,
                        onSend = {
                            screenModel.sendMessage(input)
                            input = ""
                        },
                        modifier = Modifier.navigationBarsPadding(),
                    )
                }
            }
        }
    }

    @Composable
    private fun AssistantProviderStatus(
        provider: String,
        aiEnabled: Boolean,
        assistantEnabled: Boolean,
    ) {
        val enabled = aiEnabled && assistantEnabled
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (enabled) "$provider selected" else "AI assistant is disabled",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (enabled) {
                            "Prompts and enabled local context are sent to this provider."
                        } else {
                            "Enable AI Integration and AI Assistant in AI settings to chat."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    @Composable
    private fun WelcomePanel(onPrompt: (String) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(10.dp).size(22.dp),
                            )
                        }
                        Text(
                            text = "What would you like to explore?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = "Ask about diagnostics or your library, watch history, browse sources, cached airing schedule, and saved feeds. Context is included only when relevant and enabled in AI settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = "Quick prompts",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(assistantQuickPrompts, key = { it.title }) { quickPrompt ->
                    AssistChip(
                        onClick = { onPrompt(quickPrompt.prompt) },
                        label = { Text(quickPrompt.title) },
                    )
                }
            }
        }
    }

    @Composable
    private fun ChatInput(
        value: String,
        onValueChange: (String) -> Unit,
        isLoading: Boolean,
        onSend: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text("Ask a question…") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    maxLines = 5,
                    enabled = !isLoading,
                )
                IconButton(
                    onClick = onSend,
                    enabled = value.isNotBlank() && !isLoading,
                    modifier = Modifier
                        .padding(bottom = 2.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (value.isNotBlank() && !isLoading) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (value.isNotBlank() && !isLoading) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    @Composable
    private fun UserMessage(content: String) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "You",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                modifier = Modifier.widthIn(max = 520.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = content,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }

    @Composable
    private fun AssistantMessage(content: String, onCopy: (String) -> Unit) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Hikaru Assistant",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onCopy(content) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy response",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            SelectionContainer {
                MarkdownRender(content = content)
            }
        }
    }

    @Composable
    private fun StreamingAssistantMessage(content: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 3.dp).size(16.dp),
            )
            SelectionContainer {
                Text(
                    text = content,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    @Composable
    private fun ProcessingIndicator() {
        Row(
            modifier = Modifier.padding(start = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Thinking…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun providerName(engine: String): String = when (engine.trim().lowercase()) {
    "gemini" -> "Google Gemini"
    "deepseek" -> "DeepSeek"
    "opencode" -> "OpenCode Zen"
    "literouter" -> "LiteRouter"
    "tokenreply" -> "TokenReply"
    "openai" -> "OpenAI"
    "anthropic" -> "Anthropic Claude"
    "openrouter" -> "OpenRouter"
    "together" -> "Together AI"
    "groq" -> "Groq"
    else -> "AI provider"
}
