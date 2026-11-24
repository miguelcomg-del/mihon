package eu.kanade.presentation.webview

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Message
import android.webkit.CookieManager
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
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
import kotlinx.coroutines.Job
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

private const val RELAX_TIMEOUT_MS = 7_000L
private const val MAX_RELOADS_PER_SITE = 2

enum class RelaxLevel { FULL, NETWORK_ONLY, OFF }

// SCRIPT 1: LOGIN (Falsifica navegador y oculta carteles de forma segura)
private const val CUSTOM_BROWSER_SPOOF = """
(function() {
    try {
        Object.defineProperty(navigator, 'webdriver', { get: () => false });
        Object.defineProperty(navigator, 'platform', { get: () => 'Linux armv8l' });
        Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
        if (!window.chrome) { window.chrome = { runtime: {} }; }
    } catch (e) {}

    var style = document.createElement('style');
    style.innerHTML = '.lector-block, #devtools-block-modal, #adblock-block-modal, #brave-block-modal { display: none !important; }';
    if (document.head) document.head.appendChild(style);

    function hideModals() {
        var phrases = ['Abre Aedex en tu navegador', 'Abrir en el navegador', 'Copiar enlace'];
        var divs = document.querySelectorAll('div, section, [role="dialog"]');
        divs.forEach(function(el) {
            if (el.style.display !== 'none') {
                var text = el.textContent || '';
                if (phrases.some(function(p) { return text.indexOf(p) !== -1; }) && text.length < 2000) {
                    el.style.display = 'none';
                }
            }
        });
    }
    var observer = new MutationObserver(hideModals);
    observer.observe(document.documentElement, { childList: true, subtree: true });
    setTimeout(hideModals, 100);

    window.googletag = window.googletag || {};
    window.googletag.apiReady = true;
    window.googletag.cmd = window.googletag.cmd || { push: function(f) { try { f(); } catch(e){} } };
    window.googletag.pubads = window.googletag.pubads || function() { return { addEventListener: function() {}, enableLazyLoad: function() {}, collapseEmptyDivs: function() {}, addService: function() {} }; };
    window.googletag.defineSlot = window.googletag.defineSlot || function() { return { addService: function() {} }; };
    window.googletag.defineOutOfPageSlot = window.googletag.defineOutOfPageSlot || function() { return { addService: function() {} }; };
    window.googletag.enableServices = window.googletag.enableServices || function() {};
    window.googletag.display = window.googletag.display || function() {};

    const originalFetch = window.fetch;
    window.fetch = async function(...args) {
        const url = args[0];
        if (typeof url === 'string' && (url.includes('securepubads') || url.includes('pagead2') || url.includes('doubleclick'))) {
            return new Response('', { status: 200 });
        }
        return originalFetch.apply(this, args);
    };
})();
"""

// SCRIPT 2: LECTOR (Bloquea borrado, CSS y MutationObserver para el manga)
private const val CUSTOM_READER_BYPASS = """
(function() {
    if (window.__readerBypassActive) return;
    window.__readerBypassActive = true;

    var style = document.createElement('style');
    style.innerHTML = '.ad, .ad-gpt, #ad-container, .lector-float-ad, .manga-canvas-shield, .lector-block, #adblock-block-modal, #brave-block-modal, #devtools-block-modal { display: none !important; }';
    if (document.head) document.head.appendChild(style);

    const originalRemove = Element.prototype.remove;
    Element.prototype.remove = function() {
        if (this.className === 'reading-content' || (this.classList && (this.classList.contains('manga-canvas-wrapper') || this.classList.contains('manga-page-canvas')))) {
            return; 
        }
        originalRemove.call(this);
    };

    var observer = new MutationObserver(function(mutations) {
        for (var i = 0; i < mutations.length; i++) {
            if (mutations[i].type === 'attributes' && mutations[i].attributeName === 'class') {
                var el = mutations[i].target;
                if (el === document.documentElement || el === document.body) {
                    el.classList.remove('adblock-blocked', 'brave-blocked', 'is-lector');
                }
            }
        }
    });
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
    observer.observe(document.body, { attributes: true, attributeFilter: ['class'] });
})();
"""

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
                val isFresh = cacheFile.exists() && (System.currentTimeMillis() - cacheFile.lastModified() < MAX_AGE_MILLIS)
                val text = if (isFresh) cacheFile.readText() else {
                    val downloaded = runCatching { download(REMOTE_URL) }.getOrNull()
                    when {
                        downloaded != null -> { runCatching { cacheFile.writeText(downloaded) }; downloaded }
                        cacheFile.exists() -> cacheFile.readText()
                        else -> null
                    }
                }
                if (text != null) hosts = parse(text)
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
            if (domain == "localhost" || domain == "localhost.localdomain" || domain == "local" || domain == "broadcasthost") continue
            set.add(domain)
        }
        return set
    }
}

private val SAFE_AD_HOSTS = setOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
    "adsterra.com", "a-ads.com", "popads.net", "propellerads.com", "exoclick.com",
    "juicyads.com", "hilltopads.net", "adnxs.com"
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

    val relaxLevels = remember { mutableMapOf<String, RelaxLevel>() }
    val relaxCounts = remember { mutableMapOf<String, Int>() }
    var refreshTick by remember { mutableStateOf(0) }

    fun siteOf(target: String?): String = target?.let { runCatching { it.toUri().host }.getOrNull() }?.lowercase() ?: ""
    fun levelFor(target: String?): RelaxLevel = relaxLevels[siteOf(target)] ?: RelaxLevel.FULL

    var isActive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { isActive = false } }

    val headersHost = remember { url.toUri().host }
    val scriptHandlers = remember { mutableListOf<ScriptHandler>() }
    var relaxJob by remember { mutableStateOf<Job?>(null) }
    var committed by remember { mutableStateOf(false) }

    fun relax(target: String?, reason: String) {
        val site = siteOf(target)
        if (site.isEmpty()) return
        val current = relaxLevels[site] ?: RelaxLevel.FULL
        if (current == RelaxLevel.OFF) return
        if ((relaxCounts[site] ?: 0) >= MAX_RELOADS_PER_SITE) return
        relaxCounts[site] = (relaxCounts[site] ?: 0) + 1
        relaxLevels[site] = if (current == RelaxLevel.FULL) RelaxLevel.NETWORK_ONLY else RelaxLevel.OFF
        refreshTick++
        Toast.makeText(
            currentWindow.webView?.context ?: return,
            when (relaxLevels[site]) {
                RelaxLevel.NETWORK_ONLY -> "Bloqueo reducido en este sitio ($reason)"
                RelaxLevel.OFF -> "Bloqueo desactivado en este sitio ($reason)"
                else -> "Bloqueo actualizado"
            },
            Toast.LENGTH_SHORT,
        ).show()
        navigator.reload()
    }

    val webClient = remember(refreshTick) {
        object : AccompanistWebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host
                if (host == "blayvia.com" || host?.endsWith(".blayvia.com") == true || host == "aedexnox.com") {
                    return super.shouldInterceptRequest(view, request)
                }

                return try {
                    if (levelFor(view.url) == RelaxLevel.OFF) return super.shouldInterceptRequest(view, request)
                    val isBlocked = host != null && (SAFE_AD_HOSTS.any { host == it || host.endsWith(".$it") } || AdBlockHostList.isBlocked(host))
                    if (isBlocked) {
                        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    } else {
                        super.shouldInterceptRequest(view, request)
                    }
                } catch (e: Exception) {
                    super.shouldInterceptRequest(view, request)
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    currentUrl = it
                    onUrlChange(it)
                }
                AdBlockHostList.ensureLoaded(view.context.applicationContext)
                committed = false

                view.evaluateJavascript(CUSTOM_BROWSER_SPOOF, null)
                if (url?.contains("blayvia.com") == true || (url?.contains("/comic/") == true && !url.contains("/auth/"))) {
                    view.evaluateJavascript(CUSTOM_READER_BYPASS, null)
                }

                relaxJob?.cancel()
                if (levelFor(url ?: currentUrl) != RelaxLevel.OFF) {
                    val target = url ?: currentUrl
                    relaxJob = scope.launch {
                        delay(RELAX_TIMEOUT_MS)
                        if (!committed && isActive) relax(target, "no cargaba")
                    }
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                super.onPageCommitVisible(view, url)
                committed = true
                relaxJob?.cancel()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (!committed) committed = true
                relaxJob?.cancel()

                scope.launch {
                    val html = view.getHtml()
                    showCloudflareHelp = "window._cf_chl_opt" in html || "Ray ID is" in html
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                url?.let {
                    currentUrl = it
                    onUrlChange(it)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return false
                if (target.startsWith("intent://")) return true
                if (request.url.host != headersHost) return false
                if (target.startsWith("http")) {
                    if (target != view?.url) {
                        view?.loadUrl(target, headers)
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
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (isUserGesture) {
                    windowStack.push(WebViewWindow(resultMsg, WebViewNavigator(coroutineScope)))
                    return true
                }
                return false
            }

            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                if (!isActive) { result.confirm(); return true }
                return super.onJsAlert(view, url, message, result)
            }

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                if (!isActive) { result.cancel(); return true }
                return super.onJsConfirm(view, url, message, result)
            }

            override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                if (!isActive) { result.cancel(); return true }
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
        { if (windowStack.size == 1) onNavigateUp() else windowStack.pop() }
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
                                        onClick = { if (navigator.canGoBack) navigator.navigateBack() },
                                        enabled = navigator.canGoBack,
                                    ),
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_webview_forward),
                                        icon = MaterialSymbols.AutoMirroredRounded.ArrowForward,
                                        onClick = { if (navigator.canGoForward) navigator.navigateForward() },
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
                                        title = when (levelFor(currentUrl)) {
                                            RelaxLevel.FULL -> "Bloqueo: completo (toca para desactivar)"
                                            RelaxLevel.NETWORK_ONLY -> "Bloqueo: solo red (toca para desactivar)"
                                            RelaxLevel.OFF -> "Bloqueo: desactivado (toca para activar)"
                                        },
                                        onClick = {
                                            val site = siteOf(currentUrl)
                                            if (levelFor(currentUrl) == RelaxLevel.OFF) {
                                                relaxLevels.remove(site)
                                                relaxCounts.remove(site)
                                            } else {
                                                relaxLevels[site] = RelaxLevel.OFF
                                            }
                                            refreshTick++
                                            navigator.reload()
                                        },
                                    ),
                                ).toMutableList().apply {
                                    if (windowStack.size > 1) {
                                        add(0, AppBar.Action(
                                            title = stringResource(MR.strings.action_webview_close_tab),
                                            icon = MaterialSymbols.Rounded.TabClose,
                                            onClick = popState,
                                        ))
                                    }
                                },
                            )
                        },
                    )

                    if (showCloudflareHelp) {
                        Surface(modifier = Modifier.padding(8.dp)) {
                            WarningBanner(
                                textRes = MR.strings.information_cloudflare_help,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        uriHandler.openUri("https://mihon.app/docs/guides/troubleshooting/#cloudflare")
                                    },
                            )
                        }
                    }
                }
                when (val loadingState = currentWindow.state.loadingState) {
                    is LoadingState.Initializing -> LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    )
                    is LoadingState.Loading -> LinearProgressIndicator(
                        progress = { loadingState.progress },
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    )
                    else -> {}
                }
            }
        },
    ) { contentPadding ->
        key(currentWindow) {
            WebView(
                state = currentWindow.state,
                modifier = Modifier.fillMaxSize().padding(contentPadding),
                navigator = navigator,
                onCreated = { webView ->
                    webView.setDefaultSettings()

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(webView, true)
                    webView.settings.domStorageEnabled = true
                    webView.settings.databaseEnabled = true

                    if (BuildConfig.DEBUG && 0 != webView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }

                    webView.setUserAgent("Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

                    AdBlockHostList.ensureLoaded(webView.context.applicationContext)

                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        val handler = runCatching {
                            WebViewCompat.addDocumentStartJavaScript(
                                webView,
                                CUSTOM_BROWSER_SPOOF,
                                setOf("*"),
                            )
                        }.getOrNull()
                        handler?.let { scriptHandlers.add(it) }
                    }
                },
                onDispose = { webView ->
                    relaxJob?.cancel()
                    scriptHandlers.forEach { runCatching { it.remove() } }
                    scriptHandlers.clear()
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
                    currentWindow.webView ?: WebView(context).also { webView ->
                        currentWindow.webView = webView
                        currentWindow.popupMessage?.let { initializePopup(webView, it) }
                    }
                },
            )
        }
    }
}
