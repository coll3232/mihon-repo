package eu.kanade.tachiyomi.extension.ko.newtokitoki25

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import android.content.SharedPreferences
import android.app.Application
import android.content.Context
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NewToki : HttpSource(), ConfigurableSource {

    override val name = "toon Ki"

    private val defaultBaseUrl = "https://toki25.com"
    private val baseUrlPrefKey = "baseUrl_v2"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    override val baseUrl: String
        get() = preferences.getString(baseUrlPrefKey, defaultBaseUrl) ?: defaultBaseUrl

    override val lang = "ko"

    override val supportsLatest = true

    private val dynamicUserAgent: String by lazy {
        try {
            val klass = network.javaClass
            try {
                val method = klass.getMethod("defaultUserAgentProvider")
                method.invoke(network) as String
            } catch (_: Throwable) {
                val method = klass.getMethod("getDefaultUserAgentProvider")
                val provider = method.invoke(network)
                if (provider != null) {
                    val invokeMethod = provider.javaClass.getMethod("invoke")
                    invokeMethod.invoke(provider) as String
                } else {
                    throw Exception("Null provider")
                }
            }
        } catch (_: Throwable) {
            try {
                android.webkit.WebSettings.getDefaultUserAgent(Injekt.get<Application>())
            } catch (_: Throwable) {
                "Mozilla/5.0 (Linux; Android 13; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }
        }
    }

    private fun guessNextHost(host: String): String? {
        val regex = Regex("""(.*?toki)(\d+)\.com""")
        val match = regex.find(host)
        if (match != null) {
            val prefix = match.groupValues[1]
            val num = match.groupValues[2].toInt()
            val nextNum = num + 1
            return "$prefix$nextNum.com"
        }
        return null
    }

    private fun isDomainPattern(host: String): Boolean {
        return host.contains(Regex("""toki\d+\.com"""))
    }

    private val domainInterceptor = Interceptor { chain ->
        val request = chain.request()
        val oldBaseUrl = baseUrl
        
        val staticHost = defaultBaseUrl.toHttpUrl().host
        val currentHost = oldBaseUrl.toHttpUrl().host
        var newRequest = request
        if (request.url.host == staticHost && currentHost != staticHost) {
            val newUrl = request.url.newBuilder().host(currentHost).build()
            newRequest = request.newBuilder().url(newUrl).build()
        }

        var response: Response
        try {
            response = chain.proceed(newRequest)
        } catch (e: java.io.IOException) {
            val currentHostName = newRequest.url.host
            val nextHost = guessNextHost(currentHostName)
            if (nextHost != null) {
                try {
                    val newBaseUrl = "https://$nextHost"
                    preferences.edit().putString(baseUrlPrefKey, newBaseUrl).apply()
                    
                    val retriedUrl = newRequest.url.newBuilder().host(nextHost).build()
                    val retriedRequest = newRequest.newBuilder().url(retriedUrl).build()
                    response = chain.proceed(retriedRequest)
                } catch (e2: java.io.IOException) {
                    val defaultHost = defaultBaseUrl.toHttpUrl().host
                    if (nextHost != defaultHost) {
                        preferences.edit().putString(baseUrlPrefKey, defaultBaseUrl).apply()
                        val fallbackUrl = newRequest.url.newBuilder().host(defaultHost).build()
                        val fallbackRequest = newRequest.newBuilder().url(fallbackUrl).build()
                        response = chain.proceed(fallbackRequest)
                    } else {
                        throw e2
                    }
                }
            } else if (currentHostName != defaultBaseUrl.toHttpUrl().host) {
                val defaultHost = defaultBaseUrl.toHttpUrl().host
                preferences.edit().putString(baseUrlPrefKey, defaultBaseUrl).apply()
                
                val retriedUrl = newRequest.url.newBuilder().host(defaultHost).build()
                val retriedRequest = newRequest.newBuilder().url(retriedUrl).build()
                response = chain.proceed(retriedRequest)
            } else {
                throw e
            }
        }

        val finalUrl = response.request.url
        val currentBaseUrlHost = baseUrl.toHttpUrl().host
        if (finalUrl.host != currentBaseUrlHost && isDomainPattern(finalUrl.host)) {
            val newBaseUrl = "https://${finalUrl.host}"
            preferences.edit().putString(baseUrlPrefKey, newBaseUrl).apply()
        }

        response
    }

    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("User-Agent", dynamicUserAgent)
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
            .add("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(domainInterceptor)
        .addInterceptor { chain ->
            val request = chain.request()
            val urlString = request.url.toString()
            if (urlString.contains("#seed=")) {
                val seed = urlString.substringAfter("#seed=")
                val cleanUrl = urlString.substringBefore("#seed=")
                val newRequest = request.newBuilder()
                    .url(cleanUrl)
                    .build()
                val response = chain.proceed(newRequest)
                if (response.isSuccessful && seed.isNotEmpty()) {
                    val rawBytes = response.body!!.bytes()
                    val descrambled = descrambleImage(rawBytes, seed)
                    response.newBuilder()
                        .body(descrambled.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                } else {
                    response
                }
            } else {
                chain.proceed(request)
            }
        }
        .build()

    // ---------------- Popular Manga ----------------
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/rank?kind=webtoon", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body!!.string())
        return parseMangaList(document)
    }

    // ---------------- Latest Updates ----------------
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/ing?page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body!!.string())
        return parseMangaList(document)
    }

    // ---------------- Search Manga ----------------
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search?q=$encodedQuery&field=title&match=contains&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body!!.string())
        return parseMangaList(document)
    }

    // ---------------- Common Manga List Parser ----------------
    private fun parseMangaList(document: org.jsoup.nodes.Document): MangasPage {
        val mangas = mutableListOf<SManga>()
        val links = document.select("a[href*=/webtoon/]")
        val seenIds = mutableSetOf<String>()

        for (link in links) {
            val href = link.attr("href")
            val cleanHref = href.substringBefore("?").substringBefore("#")
            val segments = cleanHref.split("/").filter { it.isNotEmpty() }
            if (segments.size != 2 || segments[0] != "webtoon") continue

            val id = segments[1]
            if (seenIds.contains(id)) continue
            seenIds.add(id)

            val img = link.selectFirst("img") ?: continue
            val title = img.attr("alt").takeIf { it.isNotEmpty() } ?: link.text()
            val coverUrl = img.attr("src")

            val manga = SManga.create().apply {
                url = cleanHref
                this.title = title
                thumbnail_url = coverUrl
            }
            mangas.add(manga)
        }
        // If results are found, assume there might be a next page (can be refined)
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    // ---------------- Manga Details ----------------
    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body!!.string())
        return SManga.create().apply {
            title = document.selectFirst("h1.hero-v2-title")?.text() ?: ""
            author = document.select("div.hero-v2-author a").joinToString(", ") { it.text() }
            description = document.selectFirst("p.hero-v2-desc")?.text() ?: ""
            genre = document.select("div.hero-v2-tags a").joinToString(", ") { it.text().replace("#", "") }
            
            val img = document.selectFirst("div.hero-v2-thumb img")
            thumbnail_url = img?.attr("src")
            
            val statusText = document.select(".pill-status").text()
            status = when {
                statusText.contains("연재중") -> SManga.ONGOING
                statusText.contains("완결") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ---------------- Chapter List ----------------
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body!!.string())
        val chapters = mutableListOf<SChapter>()
        val epRows = document.select("ul.ep-list-v2 li.ep-row-v2")

        for (row in epRows) {
            val link = row.selectFirst("a.ep-row-v2-link") ?: continue
            val href = link.attr("href")
            val titleElement = row.selectFirst("div.ep-row-v2-title strong") ?: continue
            val title = titleElement.text()

            val chapter = SChapter.create().apply {
                url = href
                name = title
                val dateText = row.selectFirst("span.ep-row-v2-date")?.text()
                if (dateText != null) {
                    date_upload = parseChapterDate(dateText)
                }
            }
            chapters.add(chapter)
        }
        return chapters
    }

    private fun parseChapterDate(dateStr: String): Long {
        return try {
            val sdf = java.text.SimpleDateFormat("yy.MM.dd", java.util.Locale.US)
            sdf.parse(dateStr.trim())?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // ---------------- Page List (Images API Resolution) ----------------
    override fun pageListParse(response: Response): List<Page> {
        android.util.Log.d("NewTokiDebug", "pageListParse start: ${response.request.url}")
        try {
            // Create a clean OkHttpClient to bypass the app-wide UserAgentInterceptor and CloudflareInterceptor
            val cleanClient = network.client.newBuilder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .apply {
                    interceptors().clear()
                    networkInterceptors().clear()
                }
                .build()

            val htmlContent = response.body!!.string()
            android.util.Log.d("NewTokiDebug", "HTML content length: ${htmlContent.length}")
            val refererUrl = response.request.url.toString()

            // 1. Extract Next.js payload tokens (handle escaped quotes like \"imagesToken\":\"...\")
            val imagesToken = regexExtract(htmlContent, "imagesToken\\\\?[\"']\\s*:\\s*\\\\?[\"']([^\\\\\"']+)")
            if (imagesToken == null) {
                android.util.Log.e("NewTokiDebug", "Failed to parse imagesToken. HTML snippet: ${htmlContent.take(500)}")
                throw Exception("Failed to parse imagesToken from page")
            }
            android.util.Log.d("NewTokiDebug", "Parsed imagesToken: $imagesToken")

            // 2. Decode JWT payload to get workId and episodeId
            val tokenParts = imagesToken.split(".")
            if (tokenParts.size < 2) {
                throw Exception("Invalid imagesToken format")
            }
            val base64Str = tokenParts[0].let { it + "=".repeat((4 - it.length % 4) % 4) }
            val jwtPayloadStr = String(Base64.decode(base64Str, Base64.URL_SAFE or Base64.NO_WRAP))
            val jwtPayload = JSONObject(jwtPayloadStr)
            val workId = jwtPayload.getString("w")
            val episodeId = jwtPayload.getString("e")
            android.util.Log.d("NewTokiDebug", "Decoded JWT: workId=$workId, episodeId=$episodeId")

            // Determine correct base URL from current request (handles cross-domain redirects)
            val apiBaseUrl = "${response.request.url.scheme}://${response.request.url.host}"

            // 2. Fetch nv session cookie if missing or expired
            var nvCookie = ""
            val cookies = network.client.cookieJar.loadForRequest(response.request.url)
            for (cookie in cookies) {
                if (cookie.name == "nv" && cookie.value.length >= 40) {
                    nvCookie = cookie.value
                }
            }
            android.util.Log.d("NewTokiDebug", "Initial nv cookie from jar: $nvCookie")

            if (nvCookie.isEmpty()) {
                android.util.Log.d("NewTokiDebug", "nv cookie empty. Requesting nv-issue...")
                val issueRequest = Request.Builder()
                    .url("$apiBaseUrl/api/nv-issue")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .headers(headers)
                    .build()
                val issueResponse = cleanClient.newCall(issueRequest).execute()
                android.util.Log.d("NewTokiDebug", "nv-issue response code: ${issueResponse.code}")
                if (!issueResponse.isSuccessful) {
                    val errBody = issueResponse.body?.string() ?: ""
                    android.util.Log.e("NewTokiDebug", "nv-issue failed. body: $errBody")
                }
                val setCookies = issueResponse.headers("Set-Cookie")
                for (c in setCookies) {
                    if (c.startsWith("nv=")) {
                        nvCookie = c.substringAfter("nv=").substringBefore(";")
                        val parsedCookie = okhttp3.Cookie.parse(response.request.url, c)
                        if (parsedCookie != null) {
                            network.client.cookieJar.saveFromResponse(response.request.url, listOf(parsedCookie))
                            android.util.Log.d("NewTokiDebug", "Saved new nv cookie: $nvCookie")
                        }
                        break
                    }
                }
            }

            if (nvCookie.isEmpty()) {
                throw Exception("Failed to acquire nv validation session cookie")
            }

            // 3. Generate 24-byte random nonce and Base64Url encode it
            val nonceBytes = ByteArray(24)
            SecureRandom().nextBytes(nonceBytes)
            val nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)

            // Use the exact User-Agent from the response to match the cf_clearance cookie
            val actualUserAgent = response.request.header("User-Agent") ?: dynamicUserAgent
            android.util.Log.d("NewTokiDebug", "actualUserAgent: $actualUserAgent")

            // 4. Compute HMAC-SHA256 proof signature
            val message = "$imagesToken.$nonce.$actualUserAgent"
            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(nvCookie.toByteArray(Charsets.UTF_8), "HmacSHA256")
            mac.init(secretKey)
            val signatureBytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            val proof = Base64.encodeToString(signatureBytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)

            // 5. Send POST request to /api/webtoon-images
            val jsonPayload = JSONObject().apply {
                put("workId", workId)
                put("episodeId", episodeId)
                put("token", imagesToken)
                put("nonce", nonce)
                put("proof", proof)
            }.toString()

            // Combine CookieJar cookies (e.g. cf_clearance) with the nv cookie manually to ensure none are missed
            val allCookiesList = network.client.cookieJar.loadForRequest(response.request.url)
            android.util.Log.d("NewTokiDebug", "All cookies from jar: ${allCookiesList.joinToString("; ") { "${it.name}=${it.value}" }}")
            var combinedCookies = allCookiesList.joinToString("; ") { "${it.name}=${it.value}" }
            if (!combinedCookies.contains("nv=")) {
                combinedCookies += (if (combinedCookies.isNotEmpty()) "; " else "") + "nv=$nvCookie"
            }

            val imagesRequest = Request.Builder()
                .url("$apiBaseUrl/api/webtoon-images")
                .post(jsonPayload.toRequestBody("application/json".toMediaType()))
                .headers(headers)
                .header("x-images-client", "viewer-v1")
                .header("User-Agent", actualUserAgent)
                .header("Cookie", combinedCookies)
                .header("Referer", refererUrl)
                .header("Origin", apiBaseUrl)
                .header("Accept", "*/*")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .build()

            android.util.Log.d("NewTokiDebug", "Sending /api/webtoon-images request...")
            val imagesResponse = cleanClient.newCall(imagesRequest).execute()
            android.util.Log.d("NewTokiDebug", "webtoon-images response code: ${imagesResponse.code}")
            if (!imagesResponse.isSuccessful) {
                val errBody = imagesResponse.body?.string() ?: ""
                android.util.Log.e("NewTokiDebug", "webtoon-images failed. body: $errBody")
                val titleMatch = Regex("<title>(.*?)</title>").find(errBody)
                val shortBody = if (titleMatch != null) {
                    "HTML Title: ${titleMatch.groupValues[1]}"
                } else {
                    errBody.take(150)
                }
                throw Exception("API 403 Blocked - $shortBody")
            }

            val respBodyStr = imagesResponse.body!!.string()
            android.util.Log.d("NewTokiDebug", "webtoon-images success. body: $respBodyStr")
            val jsonResponse = JSONObject(respBodyStr)
            if (!jsonResponse.optBoolean("ok", false)) {
                throw Exception("API webtoon-images error: " + jsonResponse.optString("error", "Unknown error"))
            }

            val jsonImages = jsonResponse.getJSONArray("images")
            val pages = mutableListOf<Page>()
            for (i in 0 until jsonImages.length()) {
                val imgObj = jsonImages.getJSONObject(i)
                val pageIndex = imgObj.getInt("page") - 1
                val shuffledSrc = imgObj.getString("shuffledSrc")
                val shuffleSeed = imgObj.optString("shuffleSeed", "")
                pages.add(Page(pageIndex, "", "$shuffledSrc#seed=$shuffleSeed"))
            }
            android.util.Log.d("NewTokiDebug", "Parsed pages size: ${pages.size}")
            return pages
        } catch (e: Exception) {
            android.util.Log.e("NewTokiDebug", "Exception in pageListParse: ${e.message}", e)
            throw e
        }
    }

    private fun regexExtract(html: String, pattern: String): String? {
        val matcher = java.util.regex.Pattern.compile(pattern).matcher(html)
        return if (matcher.find()) matcher.group(1) else null
    }

    // ---------------- Unscrambling / Image Processing ----------------
    private fun descrambleImage(imageBytes: ByteArray, seedStr: String): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return imageBytes
        val width = bitmap.width
        val height = bitmap.height

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        val paint = Paint()

        // 1. Parse seed to a ULong and compute permutation array
        val seedVal = seedStr.toLongOrNull() ?: return imageBytes
        val u = getPermutation(seedVal.toULong())

        // 2. Draw tiles (5x5 grid)
        val grid = 5
        val blockWidth = width / grid
        val blockHeight = height / grid

        for (e in 0 until 25) {
            val srcPos = getTileRect(width, height, grid, u[e])
            val destPos = getTileRect(width, height, grid, e)
            if (srcPos != null && destPos != null) {
                canvas.drawBitmap(bitmap, srcPos, destPos, paint)
            }
        }

        // 3. Draw remainder strips (25 = right, 26 = bottom)
        for (e in 25..26) {
            val pos = getTileRect(width, height, grid, e)
            if (pos != null) {
                canvas.drawBitmap(bitmap, pos, pos, paint)
            }
        }

        bitmap.recycle()

        // 4. Compress to output byte array
        val outputStream = ByteArrayOutputStream()
        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 92, outputStream)
        resultBitmap.recycle()
        
        return outputStream.toByteArray()
    }

    private fun getTileRect(width: Int, height: Int, grid: Int, n: Int): Rect? {
        val r = grid * grid
        if (n < r) {
            val blockWidth = width / grid
            val blockHeight = height / grid
            val left = (n % grid) * blockWidth
            val top = (n / grid) * blockHeight
            return Rect(left, top, left + blockWidth, top + blockHeight)
        }
        if (n == r) { // 25: right remainder
            val remainder = width % grid
            if (remainder == 0) return null
            return Rect(width - remainder, 0, width, height)
        }
        // 26: bottom remainder
        val remainder = height % grid
        if (remainder == 0) return null
        return Rect(0, height - remainder, width - (width % grid), height)
    }

    private fun getPermutation(seed: ULong): IntArray {
        var t = seed
        val u = IntArray(25) { it }
        
        // Custom 64-bit Xorshift PRNG matching JavaScript logic
        val n = { e: Int ->
            t = t xor (t shr 12)
            t = t xor (t shl 25)
            t = t xor (t shr 27)
            val shifted = t shr 32
            (shifted % e.toULong()).toInt()
        }
        
        for (e in 0 until 25) {
            val target = n(25)
            val temp = u[e]
            u[e] = u[target]
            u[target] = temp
        }
        
        return u
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = baseUrlPrefKey
            title = "도메인 설정 (Base URL)"
            summary = "현재 주소: %s"
            setDefaultValue(defaultBaseUrl)
            dialogTitle = "Base URL"
            
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = newValue as String
                    res.toHttpUrl()
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }
        screen.addPreference(baseUrlPref)
    }
}
