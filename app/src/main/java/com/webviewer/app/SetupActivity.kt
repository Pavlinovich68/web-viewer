package com.webviewer.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebStorage
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.webviewer.app.databinding.ActivitySetupBinding

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefsManager: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PrefsManager(this)

        // Если URL уже задан и нет флага "изменить" — сразу открываем WebView
        if (!prefsManager.isFirstLaunch && !intent.getBooleanExtra(EXTRA_CHANGE_URL, false)) {
            openMainActivity()
            return
        }

        // Если URL уже был задан — показываем его и кнопку очистки
        prefsManager.siteUrl?.let { url ->
            binding.editUrl.setText(url)
            binding.btnClearData.visibility = View.VISIBLE
        }

        binding.btnSave.setOnClickListener {
            val rawUrl = binding.editUrl.text.toString().trim()

            if (rawUrl.isEmpty()) {
                binding.editUrl.error = getString(R.string.error_empty_url)
                return@setOnClickListener
            }

            val url = normalizeUrl(rawUrl)

            if (!isSupportedWebUrl(url)) {
                binding.editUrl.error = getString(R.string.error_invalid_url)
                return@setOnClickListener
            }

            prefsManager.siteUrl = url
            openMainActivity()
        }

        binding.btnClearData.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.confirm_clear_title)
                .setMessage(R.string.confirm_clear_message)
                .setPositiveButton(R.string.yes) { _, _ ->
                    prefsManager.clearAll()
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    binding.editUrl.text?.clear()
                    binding.btnClearData.visibility = View.GONE
                }
                .setNegativeButton(R.string.no, null)
                .show()
        }
    }

    private fun normalizeUrl(input: String): String {
        return if (!input.startsWith("http://") && !input.startsWith("https://")) {
            "https://$input"
        } else {
            input
        }
    }

    private fun isSupportedWebUrl(url: String): Boolean {
        if (!URLUtil.isValidUrl(url)) return false
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        return (scheme == "http" || scheme == "https") && !host.isNullOrBlank()
    }

    private fun openMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_CHANGE_URL = "change_url"
    }
}
