package eu.kanade.presentation.webview

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Message
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cafe.adriel.voyager.core.stack.mutableStateStackOf
import com.kevinnzou.web.AccompanistWebChromeClient
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.LoadingState
import com.kevinnzou.web.WebContent
import com.kevinnzou.web.WebView
import com.kevinnzou.web.WebViewNavigator
import com.kevinnzou.web.WebViewState
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.getHtml
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.setUserAgent
import eu.kanade.tachiyomi.util.system.toast
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.TabClose
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

// Quita el aviso "Bloqueador de anuncios detectado" si aparece, sin esperar un intervalo fijo.
private const val ANTI_ADBLOCK_NAG_SWEEPER = """
(function() {
    if (window.__mihonAdblockSweeperStarted) return;
    window.__mihonAdblockSweeperStarted = true;
    function sweep() {
        try {
            var sel = '[id*="adblock" i],[class*="adblock" i],[id*="ad-block" i],[class*="ad-block" i],[id*="detect-ad" i],[class*="detect-ad" i]';
            document.querySelectorAll(sel).forEach(function(el) { el.remove(); });

            var phrase = 'Bloqueador de anuncios detectado';
            var candidates = Array.prototype.filter.call(
                document.querySelectorAll('div,section'),
                function(el) { return (el.textContent || '').indexOf(phrase) !== -1; },
            );
            var innermost = candidates.filter(function(el) {
                return !candidates.some(function(other) { return other !== el && el.contains(other); });
            });
            innermost.forEach(function(el) {
                if ((el.textContent || '').length < 3000) { el.remove(); }
            });

            [document.documentElement, document.body].forEach(function(el) {
                if (!el) return;
                el.style.overflow = '';
                if (el.style.position === 'fixed') el.style.position = '';
            });
        } catch (e) {}
    }
    sweep();
    var count = 0;
    var id = setInterval(function() {
        sweep();
        count++;
        if (count > 40) clearInterval(id);
    }, 500);
})();
"""

// Detecta si el propio sitio muestra una pantalla de "bloqueador de anuncios detectado"
// (por clase/id, no por contenido real), para que la app apague SU PROPIO bloqueo de
// anuncios automáticamente y recargue — lo mismo que el usuario haría a mano con el
// botón "Ya lo desactivé, volver a cargar" del propio sitio.
private const val ADBLOCK_SITE_BLOCK_DETECTOR = """
(function() {
    try {
        var htmlCls = document.documentElement.className || '';
        var bodyCls = (document.body && document.body.className) || '';
        if (/adblock|antiadblock|noads|ad-detected/i.test(htmlCls) || /adblock|antiadblock|noads|ad-detected/i.test(bodyCls)) {
            return 'true';
        }
        var modals = document.querySelectorAll('[id*="adblock" i],[class*="adblock" i]');
        for (var i = 0; i < modals.length; i++) {
            var el = modals[i];
            var style = getComputedStyle(el);
            if (style.display !== 'none' && style.visibility !== 'hidden' && el.offsetParent !== null) {
                return 'true';
            }
        }
        return 'false';
    } catch (e) {
        return 'false';
    }
})();
"""

// Lista pública de dominios de publicidad (misma filosofía que AdAway).
// Se descarga una vez y se cachea 7 días; si falla, no bloquea nada extra
// (la lista corta de abajo sigue funcionando igual).
private object AdBlockHostList {
    @Volatile private var hosts: Set<String>? = null
    @Volatile private var loading = false

    private const val REMOTE_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val CACHE_FILE_NAME = "mihon_adblock_hosts.txt"
    private const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

    fun isBlocked(host: String?): Boolean {
        val set = hosts ?: return false
        var current: String = host ?: return false
        if (current.isEmpty()) return false
        while (true) {
            if (set.contains(current)) return true
            val dot = current.indexOf('.')
            if (dot < 0) return false
            current = current.substring(dot + 1)
        }
    }

    fun ensureLoaded(context: Context) {
        if (hosts != null || loading) return
        loading = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
                val isFresh = cacheFile.exists() &&
                    (System.currentTimeMillis() - cacheFile.lastModified() < MAX_AGE_MILLIS)

                val text = if (isFresh) {
                    cacheFile.readText()
                } else {
                    val downloaded = runCatching { download(REMOTE_URL) }.getOrNull()
                    when {
                        downloaded != null -> {
                            runCatching { cacheFile.writeText(downloaded) }
                            downloaded
                        }
                        cacheFile.exists() -> cacheFile.readText()
                        else -> null
                    }
                }

                if (text != null) {
                    hosts = parse(text)
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "AdBlockHostList: fallo al cargar la lista" }
            } finally {
                loading = false
            }
        }
    }

    private fun download(urlStr: String): String {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        return connection.inputStream.use { it.bufferedReader().readText() }
    }

    private fun parse(text: String): Set<String> {
        val set = HashSet<String>(200_000)
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) continue
            if (parts[0] != "0.0.0.0" && parts[0] != "127.0.0.1") continue
            val domain = parts[1].lowercase()
            if (domain == "localhost" || domain == "localhost.localdomain" ||
                domain == "local" || domain == "broadcasthost"
            ) {
                continue
            }
            set.add(domain)
        }
        return set
    }
}

// Lista corta y segura: dominios de anuncios directos que nunca gatillan revelar contenido.
private val SAFE_AD_HOSTS = setOf(
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "adservice.google.com",
    "adsterra.com",
    "a-ads.com",
    "popads.net",
    "propellerads.com",
    "exoclick.com",
    "juicyads.com",
    "hilltopads.net",
    "adnxs.com",
)

class WebViewWindow(webContent: WebContent, val navigator: WebViewNavigator) {
    var state by mutableStateOf(WebViewState(webContent))
    var popupMessage: Message? = null
        private set
    var webView: WebView? = null

    constructor(popupMessage: Message, navigator: WebViewNavigator) : this(WebContent.NavigatorOnly, navigator) {
        this.popupMessage = popupMessage
    }
}

@Composable
fun WebViewScreenContent(
    onNavigateUp: () -> Unit,
    initialTitle: String?,
    url: String,
    defaultUserAgentProvider: () -> String,
    onShare: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onClearCookies: (String) -> Unit,
    headers: Map<String, String> = emptyMap(),
    onUrlChange: (String) -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()

    val windowStack = remember {
        mutableStateStackOf(
            WebViewWindow(
                WebContent.Url(url = url, additionalHttpHeaders = headers),
                WebViewNavigator(coroutineScope),
            ),
        )
    }

    val currentWindow = windowStack.lastItemOrNull!!
    val navigator = currentWindow.navigator

    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var currentUrl by remember { mutableStateOf(url) }
    var showCloudflareHelp by remember { mutableStateOf(false) }
    var isAdBlockEnabled by remember { mutableStateOf(true) }
    var loadGeneration by remember { mutableStateOf(0) }

    fun isAdRequest(host: String?): Boolean {
        val safeHost: String = host ?: return false
        if (SAFE_AD_HOSTS.any { safeHost == it || safeHost.endsWith(".$it") }) return true
        return AdBlockHostList.isBlocked(safeHost)
    }

    var isActive by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose { isActive = false }
    }

    val headersHost = remember { url.toUri().host }

    val webClient = remember {
        object : AccompanistWebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                if (isAdBlockEnabled && isAdRequest(request.url.host)) {
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    currentUrl = it
                    onUrlChange(it)
                }

                AdBlockHostList.ensureLoaded(view.context.applicationContext)

                if (isAdBlockEnabled) {
                    loadGeneration++
                    val myGeneration = loadGeneration
                    scope.launch {
                        delay(8_000)
                        if (myGeneration == loadGeneration && isAdBlockEnabled) {
                            isAdBlockEnabled = false
                            Toast.makeText(
                                view.context,
                                "Bloqueo de anuncios desactivado para esta página (tardaba en cargar)",
                                Toast.LENGTH_LONG,
                            ).show()
                            navigator.reload()
                        }
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                loadGeneration++
                val myGeneration = loadGeneration

                if (isAdBlockEnabled) {
                    view.evaluateJavascript(ANTI_ADBLOCK_NAG_SWEEPER, null)

                    // El sitio puede tardar ~1.5-2 s en decidir que "hay un bloqueador" y
                    // esconder su contenido; lo revisamos después de ese margen.
                    scope.launch {
                        delay(2_500)
                        if (myGeneration == loadGeneration && isAdBlockEnabled) {
                            view.evaluateJavascript(ADBLOCK_SITE_BLOCK_DETECTOR) { result ->
                                val detected = result?.trim('"') == "true"
                                if (detected && isAdBlockEnabled) {
                                    isAdBlockEnabled = false
                                    Toast.makeText(
                                        view.context,
                                        "Bloqueo de anuncios desactivado para esta página (el sitio lo requiere)",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    navigator.reload()
                                }
                            }
                        }
                    }
                }

                scope.launch {
                    val html = view.getHtml()
                    showCloudflareHelp = "window._cf_chl_opt" in html || "Ray ID is" in html
                }
            }

            override fun doUpdateVisitedHistory(
                view: WebView,
                url: String?,
                isReload: Boolean,
            ) {
                super.doUpdateVisitedHistory(view, url, isReload)
                url?.let {
                    currentUrl = it
                    onUrlChange(it)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                if (url.startsWith("intent://")) return true

                if (request.url.host != headersHost) return false

                if (url.startsWith("http") || url.startsWith("https")) {
                    if (url != view?.url) {
                        view?.loadUrl(url, headers)
                        return true
                    }
                }

                return false
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                logcat(LogPriority.ERROR) {
                    "WebView renderer gone: crashed=${detail.didCrash()}, " +
                        "priority=${detail.rendererPriorityAtExit()}, url=$currentUrl"
                }
                view.context.toast(MR.strings.information_webview_crashed, Toast.LENGTH_LONG)
                onNavigateUp()
                return true
            }
        }
    }

    val webChromeClient = remember {
        object : AccompanistWebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                if (isUserGesture) {
                    windowStack.push(WebViewWindow(resultMsg, WebViewNavigator(coroutineScope)))
                    return true
                }
                return false
            }

            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                if (!isActive) {
                    result.confirm()
                    return true
                }
                return super.onJsAlert(view, url, message, result)
            }

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                if (!isActive) {
                    result.cancel()
                    return true
                }
                return super.onJsConfirm(view, url, message, result)
            }

            override fun onJsPrompt(
                view: WebView,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult,
            ): Boolean {
                if (!isActive) {
                    result.cancel()
                    return true
                }
                return super.onJsPrompt(view, url, message, defaultValue, result)
            }
        }
    }

    fun initializePopup(webView: WebView, message: Message): WebView {
        val transport = message.obj as WebView.WebViewTransport
        transport.webView = webView
        message.sendToTarget()
        return webView
    }

    val popState = remember<() -> Unit> {
        {
            if (windowStack.size == 1) {
                onNavigateUp()
            } else {
                windowStack.pop()
            }
        }
    }

    BackHandler(windowStack.size > 1, popState)

    Scaffold(
        topBar = {
            Box {
                Column {
                    AppBar(
                        title = currentWindow.state.pageTitle ?: initialTitle,
                        subtitle = currentUrl,
                        navigateUp = onNavigateUp,
                        navigationIcon = MaterialSymbols.Rounded.Close,
                        actions = {
                            AppBarActions(
                                listOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_webview_back),
                                        icon = MaterialSymbols.AutoMirroredRounded.ArrowBack,
                                        onClick = {
                                            if (navigator.canGoBack) {
                                                navigator.navigateBack()
                                            }
                                        },
                                        enabled = navigator.canGoBack,
                                    ),
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_webview_forward),
                                        icon = MaterialSymbols.AutoMirroredRounded.ArrowForward,
                                        onClick = {
                                            if (navigator.canGoForward) {
                                                navigator.navigateForward()
                                            }
                                        },
                                        enabled = navigator.canGoForward,
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_webview_refresh),
                                        onClick = { navigator.reload() },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_share),
                                        onClick = { onShare(currentUrl) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_open_in_browser),
                                        onClick = { onOpenInBrowser(currentUrl) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.pref_clear_cookies),
                                        onClick = { onClearCookies(currentUrl) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = if (isAdBlockEnabled) "Desactivar bloqueo de anuncios" else "Activar bloqueo de anuncios",
                                        onClick = {
                                            isAdBlockEnabled = !isAdBlockEnabled
                                            navigator.reload()
                                        },
                                    ),
                                ).toMutableList().apply {
                                    if (windowStack.size > 1) {
                                        add(
                                            0,
                                            AppBar.Action(
                                                title = stringResource(MR.strings.action_webview_close_tab),
                                                icon = MaterialSymbols.Rounded.TabClose,
                                                onClick = popState,
                                            ),
                                        )
                                    }
                                },
                            )
                        },
                    )

                    if (showCloudflareHelp) {
                        Surface(
                            modifier = Modifier.padding(8.dp),
                        ) {
                            WarningBanner(
                                textRes = MR.strings.information_cloudflare_help,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        uriHandler.openUri(
                                            "https://mihon.app/docs/guides/troubleshooting/#cloudflare",
                                        )
                                    },
                            )
                        }
                    }
                }
                when (val loadingState = currentWindow.state.loadingState) {
                    is LoadingState.Initializing -> LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )
                    is LoadingState.Loading -> LinearProgressIndicator(
                        progress = { loadingState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )
                    else -> {}
                }
            }
        },
    ) { contentPadding ->
        key(currentWindow) {
            WebView(
                state = currentWindow.state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                navigator = navigator,
                onCreated = { webView ->
                    webView.setDefaultSettings()

                    if (BuildConfig.DEBUG &&
                        0 != webView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE
                    ) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }

                    webView.setUserAgent(headers["user-agent"] ?: defaultUserAgentProvider())
                },
                onDispose = { webView ->
                    val window = windowStack.items.find { it.webView == webView }
                    if (window == null) {
                        webView.destroy()
                    } else {
                        window.state.content = WebContent.NavigatorOnly
                    }
                },
                client = webClient,
                chromeClient = webChromeClient,
                factory = { context ->
                    currentWindow.webView
                        ?: WebView(context).also { webView ->
                            currentWindow.webView = webView
                            currentWindow.popupMessage?.let {
                                initializePopup(webView, it)
                            }
                        }
                },
            )
        }
    }
}
