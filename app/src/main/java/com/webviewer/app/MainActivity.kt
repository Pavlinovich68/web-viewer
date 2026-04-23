package com.webviewer.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.WebResourceError
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.webviewer.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PrefsManager
    private var siteUrl: String = ""
    private lateinit var siteUri: Uri
    private var popupBlockRules: List<PopupBlockRule> = emptyList()
    private val credentialBridge by lazy { CredentialBridge(prefsManager) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PrefsManager(this)

        siteUrl = prefsManager.siteUrl ?: run {
            navigateToSetup()
            return
        }
        siteUri = Uri.parse(siteUrl)
        popupBlockRules = parsePopupBlockRules(prefsManager.popupBlockRules)

        setupWebView()
        setupPullToRefresh()
        restoreCookies()
        setupSettingsGesture()

        if (savedInstanceState == null) {
            binding.webView.loadUrl(siteUrl)
            Toast.makeText(this, R.string.settings_long_press_hint, Toast.LENGTH_LONG).show()
        }
    }

    private fun showSettingsSheet() {
        val bottomSheet = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_settings, null)

        sheetView.findViewById<TextView>(R.id.tvCurrentUrl).text = siteUrl
        sheetView.findViewById<TextView>(R.id.tvPopupBlockingSummary).text =
            getPopupBlockingSummaryText()

        sheetView.findViewById<MaterialButton>(R.id.btnChangeUrl).setOnClickListener {
            bottomSheet.dismiss()
            val intent = Intent(this, SetupActivity::class.java)
            intent.putExtra(SetupActivity.EXTRA_CHANGE_URL, true)
            startActivity(intent)
            finish()
        }

        sheetView.findViewById<MaterialButton>(R.id.btnPopupBlocking).setOnClickListener {
            bottomSheet.dismiss()
            showPopupBlockingDialog()
        }

        sheetView.findViewById<MaterialButton>(R.id.btnRefreshPage).setOnClickListener {
            bottomSheet.dismiss()
            reloadCurrentPage(showToast = true)
        }

        sheetView.findViewById<MaterialButton>(R.id.btnClearDataSheet).setOnClickListener {
            bottomSheet.dismiss()
            AlertDialog.Builder(this)
                .setTitle(R.string.confirm_clear_auth_title)
                .setMessage(R.string.confirm_clear_auth_message)
                .setPositiveButton(R.string.yes) { _, _ ->
                    prefsManager.clearAuthData()
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
                    binding.webView.clearCache(true)
                    binding.webView.clearHistory()
                    reloadCurrentPage(showToast = false)
                    Toast.makeText(this, getString(R.string.btn_clear_data), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.no, null)
                .show()
        }

        bottomSheet.setContentView(sheetView)
        bottomSheet.show()
    }

    private fun showPopupBlockingDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_popup_blocking, null)
        val editRules = dialogView.findViewById<TextInputEditText>(R.id.editPopupRules)
        editRules.setText(prefsManager.popupBlockRules)

        AlertDialog.Builder(this)
            .setTitle(R.string.popup_blocking_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val normalizedRules = normalizePopupRules(editRules.text?.toString().orEmpty())
                prefsManager.popupBlockRules = normalizedRules
                popupBlockRules = parsePopupBlockRules(normalizedRules)
                Toast.makeText(this, R.string.popup_blocking_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupSettingsGesture() {
        binding.webView.setOnLongClickListener {
            showSettingsSheet()
            true
        }
    }

    private fun setupPullToRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            reloadCurrentPage(showToast = false)
        }
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            binding.webView.canScrollVertically(-1)
        }
    }

    private fun reloadCurrentPage(showToast: Boolean) {
        if (showToast) {
            Toast.makeText(this, R.string.page_refreshed, Toast.LENGTH_SHORT).show()
        }
        binding.webView.reload()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView

        @Suppress("DEPRECATION")
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportZoom(true)
            // Устанавливаем десктопный User-Agent для лучшей совместимости
            userAgentString = userAgentString.replace("; wv", "")
        }

        // Включаем сохранение cookies
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(credentialBridge, JS_BRIDGE_NAME)

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.swipeRefresh.isRefreshing = true
                binding.progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                // Сохраняем cookies после каждой загрузки
                saveCookies()
                if (isTrustedPage(url)) {
                    // Пытаемся авто-заполнить форму авторизации только на доверенной странице
                    tryAutoFillCredentials(view)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase()
                return if (scheme == "http" || scheme == "https") {
                    false
                } else {
                    openExternalUrl(uri)
                    true
                }
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler?,
                host: String?,
                realm: String?
            ) {
                val savedUser = prefsManager.username
                val savedPass = prefsManager.password

                if (!savedUser.isNullOrEmpty() && !savedPass.isNullOrEmpty()) {
                    // Подставляем сохранённые данные для HTTP Auth
                    handler?.proceed(savedUser, savedPass)
                } else {
                    // Показываем диалог для ввода данных
                    showHttpAuthDialog(handler, host, realm)
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.ssl_error_title)
                    .setMessage(R.string.ssl_error_message)
                    .setPositiveButton(R.string.ssl_error_continue) { _, _ -> handler?.proceed() }
                    .setNegativeButton(R.string.cancel) { _, _ -> handler?.cancel() }
                    .setCancelable(false)
                    .show()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    binding.swipeRefresh.isRefreshing = false
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        R.string.error_page_load,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                if (newProgress == 100) {
                    binding.swipeRefresh.isRefreshing = false
                    binding.progressBar.visibility = View.GONE
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val popupProbeWebView = WebView(this@MainActivity)
                popupProbeWebView.settings.javaScriptEnabled = false
                popupProbeWebView.webViewClient = object : WebViewClient() {
                    private var handled = false

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return true
                        handled = true
                        handlePopupRequest(uri)
                        cleanupPopupProbe(view)
                        return true
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (handled) return
                        val uri = url?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return
                        handled = true
                        handlePopupRequest(uri)
                        cleanupPopupProbe(view)
                    }
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popupProbeWebView
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    /**
     * Попытка авто-заполнить поля авторизации на странице.
     * Ищет стандартные поля input[type=password] и input[type=text/email] рядом.
     */
    private fun tryAutoFillCredentials(view: WebView?) {
        val username = prefsManager.username ?: return
        val password = prefsManager.password ?: return
        if (username.isEmpty() || password.isEmpty()) return

        // Безопасно экранируем для JS
        val safeUser = username.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "")
        val safePass = password.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "")

        val js = """
            (function() {
                var passwordFields = document.querySelectorAll('input[type="password"]');
                if (passwordFields.length === 0) return;
                
                passwordFields.forEach(function(passField) {
                    passField.value = '$safePass';
                    passField.dispatchEvent(new Event('input', {bubbles: true}));
                    passField.dispatchEvent(new Event('change', {bubbles: true}));
                    
                    // Ищем поле логина в той же форме
                    var form = passField.closest('form');
                    var container = form || document;
                    
                    var userField = container.querySelector(
                        'input[type="email"], input[type="text"], input[name*="user"], ' +
                        'input[name*="login"], input[name*="email"], input[id*="user"], ' +
                        'input[id*="login"], input[id*="email"]'
                    );
                    
                    if (userField) {
                        userField.value = '$safeUser';
                        userField.dispatchEvent(new Event('input', {bubbles: true}));
                        userField.dispatchEvent(new Event('change', {bubbles: true}));
                    }
                });
            })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)

        // Также перехватываем отправку форм для сохранения введённых данных
        injectCredentialCaptureScript(view)
    }

    /**
     * Внедряет скрипт для перехвата отправки форм с паролем.
     * При отправке — отправляет данные обратно в приложение.
     */
    private fun injectCredentialCaptureScript(view: WebView?) {
        val js = """
            (function() {
                if (window.__credCaptureInjected) return;
                window.__credCaptureInjected = true;
                
                document.addEventListener('submit', function(e) {
                    var form = e.target;
                    var passField = form.querySelector('input[type="password"]');
                    if (!passField || !passField.value) return;
                    
                    var userField = form.querySelector(
                        'input[type="email"], input[type="text"], input[name*="user"], ' +
                        'input[name*="login"], input[name*="email"], input[id*="user"], ' +
                        'input[id*="login"], input[id*="email"]'
                    );
                    
                    var username = userField ? userField.value : '';
                    var password = passField.value;
                    
                    if (username && password) {
                        window.AndroidBridge.onCredentialsSubmitted(username, password);
                    }
                }, true);
            })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    /**
     * JS-интерфейс для получения учётных данных из WebView.
     */
    class CredentialBridge(private val prefsManager: PrefsManager) {
        @JavascriptInterface
        fun onCredentialsSubmitted(username: String, password: String) {
            if (username.isNotBlank() && password.isNotBlank()) {
                prefsManager.username = username
                prefsManager.password = password
            }
        }
    }

    /**
     * Диалог HTTP Basic/Digest авторизации.
     */
    private fun showHttpAuthDialog(
        handler: HttpAuthHandler?,
        host: String?,
        realm: String?
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_http_auth, null)
        val editUser = dialogView.findViewById<android.widget.EditText>(R.id.editUsername)
        val editPass = dialogView.findViewById<android.widget.EditText>(R.id.editPassword)
        val checkSave = dialogView.findViewById<android.widget.CheckBox>(R.id.checkSave)

        val authTarget = buildString {
            append(host ?: siteUrl)
            if (!realm.isNullOrBlank()) {
                append(" (")
                append(realm)
                append(")")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.http_auth_title)
            .setMessage(getString(R.string.http_auth_message, authTarget))
            .setView(dialogView)
            .setPositiveButton(R.string.http_auth_login) { _, _ ->
                val user = editUser.text.toString()
                val pass = editPass.text.toString()
                if (checkSave.isChecked) {
                    prefsManager.username = user
                    prefsManager.password = pass
                }
                handler?.proceed(user, pass)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                handler?.cancel()
            }
            .setCancelable(false)
            .show()
    }

    private fun openExternalUrl(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handlePopupRequest(uri: Uri) {
        if (shouldBlockPopup(uri)) {
            Toast.makeText(this, R.string.popup_blocking_blocked, Toast.LENGTH_SHORT).show()
            return
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            binding.webView.loadUrl(uri.toString())
        } else {
            openExternalUrl(uri)
        }
    }

    private fun cleanupPopupProbe(view: WebView?) {
        view?.apply {
            stopLoading()
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            destroy()
        }
    }

    private fun shouldBlockPopup(uri: Uri): Boolean {
        return popupBlockRules.any { it.matches(uri) }
    }

    private fun getPopupBlockingSummaryText(): String {
        return if (popupBlockRules.isEmpty()) {
            getString(R.string.popup_blocking_summary_off)
        } else {
            getString(R.string.popup_blocking_summary_on, popupBlockRules.size)
        }
    }

    private fun normalizePopupRules(rawRules: String): String {
        return rawRules
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n")
    }

    private fun parsePopupBlockRules(rawRules: String): List<PopupBlockRule> {
        return rawRules
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                when {
                    line.startsWith("host:", ignoreCase = true) -> {
                        val value = line.substringAfter(":", "").trim().lowercase()
                        value.takeIf { it.isNotEmpty() }?.let { PopupBlockRule.Host(it) }
                    }
                    line.startsWith("url:", ignoreCase = true) -> {
                        val value = line.substringAfter(":", "").trim().lowercase()
                        value.takeIf { it.isNotEmpty() }?.let { PopupBlockRule.UrlContains(it) }
                    }
                    '/' !in line && '?' !in line -> {
                        PopupBlockRule.Host(line.lowercase())
                    }
                    else -> {
                        PopupBlockRule.UrlContains(line.lowercase())
                    }
                }
            }
            .toList()
    }

    /** Сохранить cookies */
    private fun saveCookies() {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(siteUrl)
        if (!cookies.isNullOrEmpty()) {
            prefsManager.cookies = cookies
        }
        cookieManager.flush()
    }

    /** Восстановить cookies */
    private fun restoreCookies() {
        val savedCookies = prefsManager.cookies ?: return
        val cookieManager = CookieManager.getInstance()
        savedCookies.split(";").forEach { cookie ->
            cookieManager.setCookie(siteUrl, cookie.trim())
        }
        cookieManager.flush()
    }

    private fun isTrustedPage(url: String?): Boolean {
        val currentUri = url?.let(Uri::parse) ?: return false
        val currentScheme = currentUri.scheme?.lowercase()
        val baseScheme = siteUri.scheme?.lowercase()
        val currentHost = currentUri.host?.lowercase() ?: return false
        val baseHost = siteUri.host?.lowercase() ?: return false
        if (currentScheme != "http" && currentScheme != "https") return false
        if (baseScheme != "http" && baseScheme != "https") return false
        return currentHost == baseHost || currentHost.endsWith(".$baseHost")
    }

    private fun navigateToSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.webView.canGoBack()) {
            binding.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        binding.webView.restoreState(savedInstanceState)
    }

    override fun onDestroy() {
        saveCookies()
        binding.webView.apply {
            stopLoading()
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            removeJavascriptInterface(JS_BRIDGE_NAME)
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }

    private sealed class PopupBlockRule {
        abstract fun matches(uri: Uri): Boolean

        class Host(private val value: String) : PopupBlockRule() {
            override fun matches(uri: Uri): Boolean {
                val host = uri.host?.lowercase() ?: return false
                return host == value || host.endsWith(".$value")
            }
        }

        class UrlContains(private val value: String) : PopupBlockRule() {
            override fun matches(uri: Uri): Boolean {
                return uri.toString().lowercase().contains(value)
            }
        }
    }

    companion object {
        private const val JS_BRIDGE_NAME = "AndroidBridge"
    }
}
