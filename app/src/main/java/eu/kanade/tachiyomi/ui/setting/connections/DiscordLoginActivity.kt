// AM (DISCORD) -->

// Original library from https://github.com/dead8309/KizzyRPC (Thank you)
// Thank you to the 最高 man for the refactored and simplified code
// https://github.com/saikou-app/saikou
package eu.kanade.tachiyomi.ui.setting.connections

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.lifecycleScope
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.connections.discord.DiscordAccount
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.io.File

class DiscordLoginActivity : BaseActivity() {

    private val connectionsManager: ConnectionsManager by injectLazy()
    private val connectionsPreferences: ConnectionsPreferences by injectLazy()
    private val networkHelper: NetworkHelper by injectLazy()

    private var isLoggingIn = false
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.discord_login_activity)
        val webView = findViewById<WebView>(R.id.webview)

        webView.apply {
            settings.javaScriptEnabled = true
            settings.databaseEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        val checkRunnable = object : Runnable {
            override fun run() {
                if (!isLoggingIn && !isFinishing) {
                    checkToken(webView)
                    handler.postDelayed(this, 1000)
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                checkToken(webView)
                if (url != null && (url.contains("/channels/") || url.contains("/app") || url.contains("/login"))) {
                    handler.post(checkRunnable)
                }
            }
        }

        webView.loadUrl("https://discord.com/login")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun checkToken(webView: WebView) {
        if (isLoggingIn) return
        val js = """
            (function() {
                try {
                    let localToken = window.localStorage.getItem('token');
                    if (localToken) {
                        localToken = localToken.replace(/^"|"$/g, '').trim();
                        if (localToken && localToken !== 'null' && localToken !== 'undefined' && localToken.length > 10) {
                            return localToken;
                        }
                    }
                } catch(e) {}
                try {
                    let token = null;
                    if (window.webpackChunkdiscord_app) {
                        window.webpackChunkdiscord_app.push([
                            [Symbol()],
                            {},
                            (req) => {
                                for (const id in req.c) {
                                    const exports = req.c[id]?.exports;
                                    if (!exports) continue;
                                    for (const key in exports) {
                                        if (exports[key]?.getToken && typeof exports[key].getToken === 'function') {
                                            const t = exports[key].getToken();
                                            if (t && typeof t === 'string' && t.length > 10) {
                                                token = t;
                                                return;
                                            }
                                        }
                                    }
                                    if (exports.getToken && typeof exports.getToken === 'function') {
                                        const t = exports.getToken();
                                        if (t && typeof t === 'string' && t.length > 10) {
                                            token = t;
                                            return;
                                        }
                                    }
                                }
                            }
                        ]);
                    }
                    if (token) return token;
                } catch(e) {}
                return null;
            })()
        """.trimIndent()

        webView.evaluateJavascript(js) { rawResult ->
            if (isLoggingIn) return@evaluateJavascript
            val cleanToken = rawResult?.trim('"')?.replace("\\\"", "\"")?.trim()
            if (!cleanToken.isNullOrBlank() && cleanToken != "null" && cleanToken != "undefined" && cleanToken.length > 10) {
                isLoggingIn = true
                handler.removeCallbacksAndMessages(null)
                login(cleanToken)
            }
        }
    }

    private fun login(token: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("https://discord.com/api/v10/users/@me")
                    .addHeader("Authorization", token)
                    .build()

                val response = networkHelper.client.newCall(request).execute()
                response.use { res ->
                    if (res.isSuccessful) {
                        val body = res.body.string()
                        val jsonObject = org.json.JSONObject(body)
                        val id = jsonObject.getString("id")
                        val username = jsonObject.optString("global_name", jsonObject.getString("username"))
                        val avatarId = jsonObject.optString("avatar")
                        val avatarUrl = if (avatarId.isNotEmpty()) {
                            "https://cdn.discordapp.com/avatars/$id/$avatarId.png"
                        } else {
                            null
                        }

                        val account = DiscordAccount(
                            id = id,
                            username = username,
                            avatarUrl = avatarUrl,
                            token = token,
                            isActive = true,
                        )
                        connectionsManager.discord.addAccount(account)
                        connectionsPreferences.connectionsToken(connectionsManager.discord).set(token)
                        connectionsPreferences.setConnectionsCredentials(
                            connectionsManager.discord,
                            username,
                            "Logged In",
                        )
                        connectionsManager.discord.restartRichPresence()

                        withContext(Dispatchers.Main) {
                            toast(MR.strings.login_success)
                            try {
                                applicationInfo.dataDir.let { File("$it/app_webview/").deleteRecursively() }
                            } catch (e: Exception) {
                                // Ignore cleanup errors
                            }
                            setResult(RESULT_OK)
                            finish()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            isLoggingIn = false
                            toast("Failed to authenticate Discord account (HTTP ${res.code})")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoggingIn = false
                    toast("Discord login error: ${e.message}")
                }
            }
        }
    }
}
// <-- AM (DISCORD)
