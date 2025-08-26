package com.verdelog.verdeapp

import android.app.DownloadManager
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import androidx.core.net.toUri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        enableEdgeToEdge()
        setContent {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .background(Color.Black)
            ) { innerPadding ->
                Home(
                    url = "https://verdeapp.verdelog.com.br",
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

@Composable
fun Home(url: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d("WebView", "Página carregada: $url")

                        // Script para expor função de salvar blobs
                        val blobScript = """
                            (function() {
                                // Função para converter Blob → Base64 → Android
                                window.downloadFileFromBlob = function(blob, fileName) {
                                    const reader = new FileReader();
                                    reader.onload = function() {
                                        const base64Data = reader.result.split(',')[1];
                                        if (window.BlobDownloader && window.BlobDownloader.downloadBlob) {
                                            window.BlobDownloader.downloadBlob(base64Data, fileName);
                                        } else {
                                            console.error('BlobDownloader não disponível');
                                        }
                                    };
                                    reader.readAsDataURL(blob);
                                };

                                // Interceptar cliques em <a download>
                                document.addEventListener('click', function(e) {
                                    const target = e.target;
                                    if (target.tagName === 'A' && target.href.startsWith('blob:')) {
                                        e.preventDefault();
                                        const fileName = target.download || "download_" + Date.now();
                                        fetch(target.href).then(r => r.blob()).then(blob => {
                                            downloadFileFromBlob(blob, fileName);
                                        });
                                    }
                                }, true);
                            })();
                        """.trimIndent()

                        view?.evaluateJavascript(blobScript, null)
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        Log.e("WebView", "Erro ao carregar: ${error?.description} - URL: ${request?.url}")
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                        // Interceptar POST downloads
                        if (url.contains("/api/downloads/") && request.method == "POST") {
                            Log.d("WebView", "Interceptando POST download: $url")

                            try {
                                val connection = URL(url).openConnection() as HttpURLConnection
                                connection.requestMethod = "POST"
                                connection.doOutput = true
                                connection.instanceFollowRedirects = true

                                request.requestHeaders?.forEach { (key, value) ->
                                    if (!key.equals("host", true) && !key.equals("connection", true)) {
                                        connection.setRequestProperty(key, value)
                                    }
                                }

                                val cookieManager = CookieManager.getInstance()
                                val cookies = cookieManager.getCookie(url)
                                if (!cookies.isNullOrEmpty()) {
                                    connection.setRequestProperty("Cookie", cookies)
                                    Log.d("WebView", "Cookies enviados: $cookies")
                                }

                                val responseCode = connection.responseCode
                                Log.d("WebView", "Response code: $responseCode")

                                if (responseCode == 200) {
                                    val contentDisposition = connection.getHeaderField("Content-Disposition")
                                    val contentType = connection.contentType

                                    if (contentDisposition?.contains("attachment") == true) {
                                        val filename = contentDisposition.substringAfter("filename=\"").substringBefore("\"")

                                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                                        val file = File(downloadsDir, filename)

                                        connection.inputStream.use { input ->
                                            FileOutputStream(file).use { output ->
                                                input.copyTo(output)
                                            }
                                        }

                                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                        @Suppress("DEPRECATION")
                                        dm.addCompletedDownload(
                                            filename,
                                            "Download via POST",
                                            true,
                                            contentType,
                                            file.absolutePath,
                                            file.length(),
                                            true
                                        )

                                        connection.disconnect()
                                        return WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                                    }
                                }

                                connection.disconnect()
                            } catch (e: Exception) {
                                Log.e("WebView", "Erro ao interceptar POST", e)
                            }
                        }

                        return super.shouldInterceptRequest(view, request)
                    }
                }

                @Suppress("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowContentAccess = true
                @Suppress("DEPRECATION")
                settings.allowFileAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                WebView.setWebContentsDebuggingEnabled(true)
                clearCache(true)

                addJavascriptInterface(BlobDownloader(context), "BlobDownloader")

                Log.d("WebView", "Carregando URL: $url")

                // DownloadListener atualizado
                setDownloadListener { downloadUrl, userAgent, contentDisposition, mimeType, _ ->
                    Log.d("WebView", "DownloadListener chamado: $downloadUrl")

                    if (downloadUrl.startsWith("blob:")) {
                        Log.d("WebView", "Interceptado blob URL - o salvamento é feito pelo JS via BlobDownloader")
                        return@setDownloadListener
                    }

                    // URLs normais
                    val request = DownloadManager.Request(downloadUrl.toUri())
                    request.addRequestHeader("User-Agent", userAgent)
                    val fileName = URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType)
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    request.setTitle("Download")
                    request.setDescription("Baixando $fileName")

                    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadManager.enqueue(request)
                }

                loadUrl(url)
            }
        }
    )
}

class BlobDownloader(private val context: Context) {
    @JavascriptInterface
    fun downloadBlob(base64Data: String, fileName: String) {
        Log.d("BlobDownloader", "Recebido blob: $fileName")

        try {
            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val file = File(downloadsDir, fileName)

            FileOutputStream(file).use { fos -> fos.write(bytes) }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            @Suppress("DEPRECATION")
            dm.addCompletedDownload(
                fileName,
                "Arquivo baixado pelo app",
                true,
                URLConnection.guessContentTypeFromName(fileName),
                file.absolutePath,
                file.length(),
                true
            )

            Log.d("BlobDownloader", "Arquivo salvo em: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("BlobDownloader", "Erro ao salvar blob", e)
        }
    }
}
