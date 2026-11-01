package vibe.tube.youtube.potoken

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs BotGuard in a hidden WebView whose origin is youtube.com, which is where the interpreter
 * expects to run. Large inputs (the program and the interpreter source) are handed over through a
 * bridge the page pulls from, never spliced into evaluated script text.
 */
class WebViewBotGuardRunner(context: Context) : BotGuardRunner {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val calls = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val arguments = ConcurrentHashMap<Long, List<String>>()
    private val nextId = AtomicLong()
    @Volatile private var webView: WebView? = null
    private var ready: CompletableDeferred<Unit>? = null

    private val bridge = Bridge()

    private inner class Bridge {
        @JavascriptInterface
        fun argument(id: Long, index: Int): String = arguments[id]?.getOrNull(index).orEmpty()

        @JavascriptInterface
        fun done(id: Long, ok: Boolean, payload: String) {
            val call = calls.remove(id) ?: return
            arguments.remove(id)
            if (ok) call.complete(payload) else call.completeExceptionally(IllegalStateException(payload.take(300)))
        }
    }

    override suspend fun attest(program: String, globalName: String, interpreter: String): String =
        run("attest", program, globalName, interpreter)

    override suspend fun mint(integrityToken: String, identifier: String): String = run("mint", integrityToken, identifier)

    override fun reset() {
        // State is cleared at once, only the old view's teardown waits for the main thread: clearing it
        // later would wipe the page the very next attest creates, and the following mint would find an empty one.
        val old = synchronized(this) {
            val view = webView
            webView = null
            ready = null
            view
        }
        calls.values.forEach { it.completeExceptionally(IllegalStateException("runner reset")) }
        calls.clear()
        arguments.clear()
        if (old != null) main.post { runCatching { old.stopLoading(); old.destroy() } }
    }

    private suspend fun run(operation: String, vararg args: String): String {
        awaitPage()
        val id = nextId.incrementAndGet()
        val call = CompletableDeferred<String>()
        calls[id] = call
        arguments[id] = args.toList()
        main.post { webView?.evaluateJavascript("window.__po.$operation($id)", null) ?: call.completeExceptionally(IllegalStateException("no page")) }
        try {
            return call.await()
        } finally {
            calls.remove(id)
            arguments.remove(id)
        }
    }

    private suspend fun awaitPage() {
        val pending = CompletableDeferred<Unit>()
        val existing = synchronized(this) { ready ?: pending.also { ready = it } }
        if (existing === pending) main.post { createPage(pending) }
        existing.await()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createPage(loaded: CompletableDeferred<Unit>) {
        try {
            val view = WebView(appContext)
            synchronized(this) { webView = view }
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.addJavascriptInterface(bridge, "Bridge")
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(PAGE_SCRIPT) { loaded.complete(Unit) }
                }
            }
            view.loadDataWithBaseURL("https://www.youtube.com/", "<!doctype html><html><body></body></html>", "text/html", "utf-8", null)
        } catch (e: Exception) {
            Log.w(TAG, "WebView unavailable: ${e.javaClass.simpleName}")
            loaded.completeExceptionally(e)
        }
    }

    private companion object {
        const val TAG = "PoToken"

        /** The page-side half. `attest` returns the VM's response; `mint` turns an integrity token and identifier into a PO token. */
        val PAGE_SCRIPT = """
            (function () {
              var signal = [];
              var minter = null, minterFor = null;
              var toBytes = function (s) {
                s = s.replace(/-/g, '+').replace(/_/g, '/').replace(/\./g, '=');
                while (s.length % 4) s += '=';
                var bin = atob(s), out = new Uint8Array(bin.length);
                for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
                return out;
              };
              var toToken = function (bytes) {
                var bin = '';
                for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
                return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_');
              };
              var finish = function (id, work) {
                Promise.resolve().then(work).then(
                  function (value) { Bridge.done(id, true, String(value)); },
                  function (error) { Bridge.done(id, false, String(error && error.stack || error)); }
                );
              };
              window.__po = {
                attest: function (id) {
                  finish(id, function () {
                    var program = Bridge.argument(id, 0), globalName = Bridge.argument(id, 1), script = Bridge.argument(id, 2);
                    (0, eval)(script);
                    var vm = window[globalName];
                    if (!vm || typeof vm.a !== 'function') throw new Error('interpreter did not define ' + globalName);
                    var snapshot = null;
                    vm.a(program, function (asyncSnapshot) { snapshot = asyncSnapshot; }, true, undefined, function () {}, [[], []]);
                    return new Promise(function (resolve, reject) {
                      var waited = 0;
                      var timer = setInterval(function () {
                        waited += 50;
                        if (snapshot) {
                          clearInterval(timer);
                          signal = [];
                          minter = null;
                          minterFor = null;
                          var guard = setTimeout(function () { reject(new Error('snapshot timed out')); }, 15000);
                          snapshot(function (response) { clearTimeout(guard); resolve(response); }, [undefined, undefined, signal, undefined]);
                        } else if (waited > 5000) {
                          clearInterval(timer);
                          reject(new Error('vm produced no snapshot function'));
                        }
                      }, 50);
                    });
                  });
                },
                mint: function (id) {
                  finish(id, function () {
                    var integrityToken = Bridge.argument(id, 0), identifier = Bridge.argument(id, 1);
                    // One minter per integrity token: every further getMinter call grows each token it
                    // mints, and the stream host refuses the oversized ones.
                    if (!minter || minterFor !== integrityToken) {
                      var getMinter = signal[0];
                      if (typeof getMinter !== 'function') throw new Error('no minter: attest first');
                      minter = Promise.resolve(getMinter(toBytes(integrityToken)));
                      minterFor = integrityToken;
                    }
                    return minter.then(function (mint) {
                      return mint(new TextEncoder().encode(identifier));
                    }).then(toToken);
                  });
                }
              };
            })();
        """.trimIndent()
    }
}
