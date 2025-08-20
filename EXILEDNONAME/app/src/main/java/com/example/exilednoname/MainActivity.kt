package com.example.exilednoname

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts

class JsBridge(val context: ComponentActivity) {
    @JavascriptInterface
    fun saveBase64File(base64Data: String, filename: String) {
        try {
            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!path.exists()) path.mkdirs()
            val file = File(path, filename)
            file.outputStream().use { it.write(bytes) }
            context.runOnUiThread {
                Toast.makeText(context, "File saved: $filename", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            context.runOnUiThread {
                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                // Permission granted
            } else {
                // Permission denied
            }
        }

    private val PERMISSION_REQUEST_CODE = 1001
    private val FILE_CHOOSER_REQUEST_CODE = 2001
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ✅ Permission untuk Android < 11
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    PERMISSION_REQUEST_CODE
                )
            }
        }

        val webView: WebView = findViewById(R.id.webview)
        val progressBar: ProgressBar = findViewById(R.id.progressBar)
        val swipeRefresh: SwipeRefreshLayout = findViewById(R.id.swipeRefresh)

        progressBar.progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.addJavascriptInterface(JsBridge(this), "Android")

        // ✅ WebChromeClient untuk upload file
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress == 100) {
                    progressBar.visibility = android.view.View.GONE
                } else {
                    progressBar.visibility = android.view.View.VISIBLE
                }
            }
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "image/*" // hanya untuk avatar
                startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = android.view.View.VISIBLE
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = android.view.View.GONE
                swipeRefresh.isRefreshing = false
                super.onPageFinished(view, url)
            }
        }

        // ✅ Tangkap download
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
                val jsCode = """
                    (function() {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$url', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            var blob = xhr.response;
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                var base64data = reader.result.split(',')[1];
                                var filename = "EXILEDNONAME";
                                // coba ambil dari Content-Disposition
                                var cd = xhr.getResponseHeader("Content-Disposition");
                                if(cd) {
                                    var match = cd.match(/filename="?(.+?)"?$/);
                                    if(match) filename = match[1];
                                }
                                // jika tidak ada extension, pakai MIME type
                                if(!filename.includes('.')) {
                                    var ext = "bin";
                                    if(blob.type === "application/pdf") ext = "pdf";
                                    else if(blob.type === "application/vnd.ms-excel") ext = "xls";
                                    else if(blob.type === "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") ext = "xlsx";
                                    filename += "." + ext;
                                }
                                Android.saveBase64File(base64data, filename);
                            };
                            reader.readAsDataURL(blob);
                        };
                        xhr.send();
                    })();
                """.trimIndent()
                webView.evaluateJavascript(jsCode, null)
            } else {
                try {
                    val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    val request = DownloadManager.Request(Uri.parse(url))
                    request.setTitle(filename)
                    request.setDescription("Downloading file...")
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)

                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)

                    Toast.makeText(this, "Downloading: $filename", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Setup SwipeRefreshLayout
        swipeRefresh.setOnRefreshListener {
            webView.reload() // reload halaman saat swipe
        }

        // 🚀 Load halaman login/website
        webView.loadUrl("https://exilednoname.com/login")
    }

    // Tangani hasil file picker untuk avatar
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return
            val results = if (resultCode == RESULT_OK && data != null) {
                if (data.clipData != null) {
                    val count = data.clipData!!.itemCount
                    Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                } else data.data?.let { arrayOf(it) } ?: arrayOf()
            } else arrayOf<Uri>()
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
