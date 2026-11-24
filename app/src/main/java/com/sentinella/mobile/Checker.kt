package com.sentinella.mobile

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToLong

data class CheckResult(
    val ok: Boolean,
    val ping: Long?,
    val msg: String,
    val certExpiry: Long? = null,
)

object Checker {

    suspend fun check(m: Monitor): CheckResult = withContext(Dispatchers.IO) {
        try {
            when (m.type) {
                TYPE_HTTP -> http(m)
                else -> ping(m)
            }
        } catch (e: Exception) {
            CheckResult(false, null, describe(e))
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "Timeout: nessuna risposta"
        is UnknownHostException -> "Indirizzo sconosciuto (DNS)"
        is ConnectException -> "Connessione rifiutata"
        is NoRouteToHostException -> "Dispositivo non raggiungibile"
        is SSLException -> "Errore certificato: ${e.message ?: ""}".trim()
        else -> e.message ?: e.javaClass.simpleName
    }

    fun normalizeUrl(t: String): String {
        val s = t.trim()
        return if (s.contains("://")) s else "https://$s"
    }

    /** Per il ping serve solo il nome host: toglie eventuale http://, porta e percorso. */
    fun hostOf(t: String): String =
        t.trim().substringAfter("://").substringBefore("/").substringBefore(":").ifBlank { t.trim() }

    // ---------------- PING ----------------

    private fun ping(m: Monitor): CheckResult {
        val host = hostOf(m.target)
        val timeout = m.timeoutSec.coerceIn(1, 60)
        val start = SystemClock.elapsedRealtime()
        val proc = try {
            ProcessBuilder("/system/bin/ping", "-c", "1", "-W", timeout.toString(), host)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            return pingFallback(host, timeout)
        }
        val finished = proc.waitFor(timeout + 3L, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroy()
            return CheckResult(false, null, "Timeout: nessuna risposta")
        }
        val out = proc.inputStream.bufferedReader().readText()
        if (proc.exitValue() == 0) {
            val ms = Regex("time[=<]\\s*([\\d.]+)").find(out)?.groupValues?.get(1)?.toDoubleOrNull()
            val value = ms?.roundToLong()?.coerceAtLeast(0) ?: (SystemClock.elapsedRealtime() - start)
            return CheckResult(true, value, "OK")
        }
        return when {
            out.contains("unknown host", true) || out.contains("Name or service", true) ->
                CheckResult(false, null, "Indirizzo sconosciuto (DNS)")
            out.contains("unreachable", true) -> CheckResult(false, null, "Rete non raggiungibile")
            else -> CheckResult(false, null, "Nessuna risposta al ping")
        }
    }

    private fun pingFallback(host: String, timeout: Int): CheckResult {
        val addr = InetAddress.getByName(host)
        val s = SystemClock.elapsedRealtime()
        val ok = addr.isReachable(timeout * 1000)
        val ms = SystemClock.elapsedRealtime() - s
        return if (ok) CheckResult(true, ms, "OK") else CheckResult(false, null, "Nessuna risposta al ping")
    }

    // ---------------- HTTP(S) ----------------

    private val insecureFactory: SSLSocketFactory by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }.socketFactory
    }

    private fun http(m: Monitor): CheckResult {
        var url = URL(normalizeUrl(m.target))
        val timeout = m.timeoutSec.coerceIn(1, 60) * 1000
        val start = SystemClock.elapsedRealtime()
        var redirects = 0
        var cert: Long? = null
        while (true) {
            val c = url.openConnection() as HttpURLConnection
            if (c is HttpsURLConnection && m.ignoreTls) {
                c.sslSocketFactory = insecureFactory
                c.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.instanceFollowRedirects = false
            c.requestMethod = "GET"
            c.useCaches = false
            c.setRequestProperty("User-Agent", "Sentinella/1.0 (Android)")
            c.setRequestProperty("Accept", "*/*")
            try {
                val code = c.responseCode
                if (cert == null && c is HttpsURLConnection) {
                    cert = try {
                        (c.serverCertificates.firstOrNull() as? X509Certificate)?.notAfter?.time
                    } catch (_: Exception) {
                        null
                    }
                }
                if (code in 300..399 && redirects < 10) {
                    val loc = c.getHeaderField("Location")
                    if (loc != null) {
                        url = URL(url, loc)
                        redirects++
                        continue
                    }
                }
                try {
                    (if (code >= 400) c.errorStream else c.inputStream)?.use { it.read(ByteArray(2048)) }
                } catch (_: Exception) {
                }
                val ms = SystemClock.elapsedRealtime() - start
                val text = "$code ${c.responseMessage ?: ""}".trim()
                return if (code in 200..399) CheckResult(true, ms, text, cert)
                else CheckResult(false, null, "Errore HTTP $text", cert)
            } finally {
                c.disconnect()
            }
        }
    }
}
