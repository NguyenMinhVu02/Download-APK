import com.aurora.gplayapi.PlayClient
import com.aurora.gplayapi.PlayException
import com.aurora.gplayapi.device.DeviceRegistry
import com.aurora.gplayapi.engine.OkHttpEngine
import com.aurora.gplayapi.model.App
import com.aurora.gplayapi.model.AppFile
import com.aurora.gplayapi.model.Restriction
import com.aurora.gplayapi.session.DefaultPlaySessions
import com.aurora.gplayapi.web.WebPlayClient
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import java.io.IOException
import java.net.Authenticator
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request

val OUT = File(System.getenv("OUT_DIR") ?: "out").absoluteFile.apply { mkdirs() }

/**
 * Một "vùng" = một đường ra Internet. Google Play xác định quốc gia theo IP, nên muốn tải app
 * bị chặn ở VN thì phải đi qua proxy ở nước khác. Mỗi vùng có OkHttp + phiên ẩn danh riêng.
 */
class Region(val id: String, val label: String, val proxyUrl: String?) {
    val http: OkHttpClient = buildHttp(proxyUrl)
    val sessions = DefaultPlaySessions(OkHttpEngine(http), device = DeviceRegistry.default, locale = Locale.US)
    val client = PlayClient(OkHttpEngine(http), sessions)
    // Client web (play.google.com) — dùng cho tìm kiếm theo tên, lane ẩn danh không tìm được.
    val web = WebPlayClient(OkHttpEngine(http), Locale.forLanguageTag("vi-VN"))

    // Thêm vài thiết bị giả lập khác mật độ màn hình / ABI: Play chỉ trả split khớp thiết bị hỏi,
    // nên hỏi nhiều thiết bị rồi gộp split lại thì gói cài được trên hầu hết điện thoại.
    val extraSessions = EXTRA_DEVICES.mapNotNull { id ->
        DeviceRegistry.byId(id)?.let { DefaultPlaySessions(OkHttpEngine(http), device = it, locale = Locale.US) }
    }
    val extraClients = extraSessions.map { PlayClient(OkHttpEngine(http), it) }

    fun invalidate() {
        sessions.invalidateAnonymous()
        extraSessions.forEach { it.invalidateAnonymous() }
    }
}

/** sm_a13_5g: xhdpi + armeabi-v7a · rm_7: xhdpi + arm64 · sm_s25u: xxxhdpi + arm64. */
val EXTRA_DEVICES = (System.getenv("EXTRA_DEVICES") ?: "sm_a13_5g,rm_7,sm_s25u")
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }

// Thông tin đăng nhập cho proxy SOCKS (Java chỉ hỗ trợ qua Authenticator toàn cục).
val socksAuth = ConcurrentHashMap<String, PasswordAuthentication>()

fun buildHttp(proxyUrl: String?): OkHttpClient {
    val b = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
    if (proxyUrl == null) return b.build()
    val u = URI(proxyUrl)
    val host = u.host ?: error("Proxy sai định dạng: $proxyUrl")
    val user = u.userInfo?.substringBefore(':')
    val pass = u.userInfo?.substringAfter(':', "")
    when (u.scheme?.lowercase()) {
        "http", "https" -> {
            b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(host, if (u.port > 0) u.port else 8080)))
            if (user != null) {
                b.proxyAuthenticator { _, res ->
                    res.request.newBuilder().header("Proxy-Authorization", Credentials.basic(user, pass ?: "")).build()
                }
            }
        }
        "socks", "socks5", "socks5h" -> {
            val port = if (u.port > 0) u.port else 1080
            b.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port)))
            if (user != null) socksAuth["$host:$port"] = PasswordAuthentication(user, (pass ?: "").toCharArray())
        }
        else -> error("Proxy chỉ hỗ trợ http://, socks5:// — gặp: $proxyUrl")
    }
    return b.build()
}

/**
 * Đọc proxies.txt (mỗi dòng: `mã  Tên hiển thị  proxy-url`, # là chú thích), hoặc biến môi trường
 * PROXIES dạng `us=socks5://h:p,jp=http://u:p@h:p`.
 */
fun loadRegions(): List<Region> {
    val list = mutableListOf(Region("direct", "Máy này (IP hiện tại)", null))
    val file = File(System.getenv("PROXIES_FILE") ?: "proxies.txt")
    if (file.isFile) {
        file.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val parts = line.split(Regex("\\s+"))
            require(parts.size >= 2) { "Dòng sai trong ${file.name}: $line" }
            val id = parts.first()
            val url = parts.last()
            val label = parts.drop(1).dropLast(1).joinToString(" ").ifEmpty { id.uppercase() }
            list += Region(id, label, url)
        }
    }
    System.getenv("PROXIES")?.split(',')?.map { it.trim() }?.filter { it.contains('=') }?.forEach {
        list += Region(it.substringBefore('='), it.substringBefore('=').uppercase(), it.substringAfter('='))
    }
    if (socksAuth.isNotEmpty()) {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication() = socksAuth["$requestingHost:$requestingPort"]
        })
    }
    return list
}

val regions = loadRegions()
val regionById = regions.associateBy { it.id }

// Không cho 2 request cùng tải một package một lúc.
val locks = ConcurrentHashMap<String, Mutex>()

@Serializable data class FetchReq(val input: String, val region: String = "auto", val versionCode: Long? = null)

@Serializable data class RegionInfo(val id: String, val label: String)

@Serializable data class IpInfo(val region: String, val ip: String, val country: String)

@Serializable data class AppInfo(
    val packageName: String,
    val name: String,
    val developer: String,
    val icon: String,
    val versionName: String,
    val versionCode: Long,
    val free: Boolean,
    val restriction: String,
    val region: String = "",
    val regionLabel: String = "",
    val rating: Float = 0f,
    val installs: String = "",
    val size: Long = 0,
    val updated: String = "",
    val category: String = "",
    val summary: String = "",
)

/** Tiến trình tải của một package: stage = resolve | download | pack | done | error. */
@Serializable data class Progress(val stage: String, val done: Long = 0, val total: Long = 0, val file: String = "")

@Serializable data class FileEntry(val name: String, val size: Long, val modified: Long, val app: AppInfo? = null)

val progress = ConcurrentHashMap<String, Progress>()
val META = File(OUT, ".meta").apply { mkdirs() }
val json = Json { ignoreUnknownKeys = true }

fun saveMeta(file: String, app: AppInfo) = File(META, "$file.json").writeText(json.encodeToString(AppInfo.serializer(), app))

fun readMeta(file: String): AppInfo? =
    runCatching { json.decodeFromString(AppInfo.serializer(), File(META, "$file.json").readText()) }.getOrNull()

fun listFiles(): List<FileEntry> =
    (OUT.listFiles() ?: emptyArray())
        .filter { it.isFile && !it.name.startsWith(".") && (it.name.endsWith(".apk") || it.name.endsWith(".apks")) }
        .sortedByDescending { it.lastModified() }
        .map { FileEntry(it.name, it.length(), it.lastModified(), readMeta(it.name)) }

/** Các địa chỉ IPv4 trong mạng LAN (để điện thoại quét QR mở link). */
fun lanAddresses(): List<String> =
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.name.startsWith("utun") }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .map { it.hostAddress }

@Serializable data class DownloadResult(
    val app: AppInfo,
    val file: String,
    val size: Long,
    val parts: List<String>,
    val skippedObb: Int,
    val cached: Boolean,
)

@Serializable data class ErrorBody(val message: String)

class UserError(msg: String) : Exception(msg)

fun parsePackage(text: String): String {
    val t = text.trim()
    Regex("[?&]id=([\\w.]+)").find(t)?.let { return it.groupValues[1] }
    if (Regex("^[a-zA-Z][\\w]*(\\.[a-zA-Z_][\\w]*)+$").matches(t)) return t
    throw UserError("Hãy dán link Google Play hoặc package name (vd: org.mozilla.firefox)")
}

fun App.info(r: Region? = null) = AppInfo(
    packageName = packageName,
    name = displayName,
    developer = developerName,
    icon = iconArtwork.url,
    versionName = versionName,
    versionCode = versionCode,
    free = isFree,
    restriction = restriction.name,
    region = r?.id ?: "",
    regionLabel = r?.label ?: "",
    rating = rating.average,
    installs = downloadString,
    size = size,
    updated = updatedOn,
    category = categoryName,
    summary = shortDescription,
)

fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

/** Tải một file Play về [dest], kiểm tra SHA-256 (hoặc SHA-1) nếu Play có gửi. */
fun downloadTo(http: OkHttpClient, f: AppFile, dest: File, onBytes: (Int) -> Unit = {}) {
    val algo = if (f.sha256.isNotEmpty()) "SHA-256" else "SHA-1"
    val expected = f.sha256.ifEmpty { f.sha1 }
    val md = MessageDigest.getInstance(algo)
    http.newCall(Request.Builder().url(f.url).build()).execute().use { res ->
        if (!res.isSuccessful) throw IOException("Tải ${f.name} lỗi HTTP ${res.code}")
        res.body.byteStream().use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                    onBytes(n)
                }
            }
        }
    }
    // Play gửi hash dạng hex hoặc base64 tuỳ trường; so cả hai.
    val digest = md.digest()
    val ok = expected.isEmpty() ||
        expected.equals(hex(digest), ignoreCase = true) ||
        expected.trimEnd('=') == java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest) ||
        expected.trimEnd('=') == java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
    if (!ok) {
        dest.delete()
        throw IOException("Sai checksum ${f.name}")
    }
}

/** IP và quốc gia mà Google nhìn thấy khi đi qua vùng [r] (dùng trang trace của Cloudflare). */
fun publicIp(r: Region): IpInfo {
    val body = r.http.newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build())
        .execute().use { it.body.string() }
    val kv = body.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    return IpInfo(r.id, kv["ip"] ?: "?", kv["loc"] ?: "?")
}

fun isGeoBlock(e: Throwable) = e is PlayException.AppNotPurchased || e is PlayException.AppNotSupported

/**
 * Lấy thông tin app ở vùng [regionId]. Với "auto": thử lần lượt từng vùng (máy này trước),
 * dừng ở vùng đầu tiên mà app không bị chặn.
 */
suspend fun fetchApp(pkg: String, regionId: String): Pair<Region, App> {
    val order = if (regionId == "auto") regions
        else listOf(regionById[regionId] ?: throw UserError("Không có vùng '$regionId'"))
    var last: Throwable? = null
    val blockedIn = mutableListOf<String>()
    for (r in order) {
        try {
            val app = r.client.apps.details(pkg)
            if (app.restriction == Restriction.UNAVAILABLE_COUNTRY) { blockedIn += r.label; continue }
            return r to app
        } catch (e: Throwable) {
            if (order.size == 1 || e is PlayException.NotFound || e is PlayException.AppRemoved) throw e
            System.err.println("[${r.id}] $pkg: ${e::class.simpleName} ${e.message}")
            last = e
        }
    }
    if (blockedIn.isNotEmpty()) {
        val hint = if (regions.size == 1) " — chưa cấu hình proxy nước ngoài nào (xem proxies.txt)"
            else " — thử thêm proxy ở quốc gia app phát hành"
        throw UserError("App bị chặn ở: ${blockedIn.joinToString(", ")}$hint")
    }
    throw last ?: UserError("Không lấy được thông tin app")
}

/**
 * Gộp split của các thiết bị giả lập khác vào danh sách file. Chỉ gộp khi base.apk của thiết bị
 * đó trùng hệt (cùng SHA) với base chính, để không trộn hai bản build khác nhau.
 */
suspend fun withAllSplits(r: Region, app: App, files: List<AppFile>): List<AppFile> {
    val main = files.filter { it.type == AppFile.Type.BASE || it.type == AppFile.Type.SPLIT }
    val base = main.firstOrNull { it.type == AppFile.Type.BASE } ?: return main
    if (main.none { it.type == AppFile.Type.SPLIT }) return main // app 1 file, không cần
    val extra = mutableListOf<AppFile>()
    r.extraClients.forEachIndexed { i, c ->
        try {
            progress[app.packageName] = Progress("resolve-extra", i.toLong() + 1, r.extraClients.size.toLong())
            val ef = c.delivery.resolve(app.packageName, app.versionCode, app.offerType)
            val eb = ef.firstOrNull { it.type == AppFile.Type.BASE }
            if (eb != null && eb.sha1 == base.sha1 && eb.sha256 == base.sha256) {
                extra += ef.filter { it.type == AppFile.Type.SPLIT }
            } else {
                System.err.println("[${EXTRA_DEVICES.getOrNull(i)}] ${app.packageName}: base khác bản chính, bỏ qua")
            }
        } catch (e: Exception) {
            System.err.println("[${EXTRA_DEVICES.getOrNull(i)}] ${app.packageName}: ${e::class.simpleName} ${e.message}")
        }
    }
    return (main + extra).distinctBy { it.name.ifEmpty { it.url } }
}

suspend fun download(pkg: String, regionId: String, wantVc: Long? = null): DownloadResult = locks.computeIfAbsent(pkg) { Mutex() }.withLock {
    progress[pkg] = Progress("resolve")
    try {
        downloadLocked(pkg, regionId, wantVc).also { progress[pkg] = Progress("done", it.size, it.size, it.file) }
    } catch (e: Throwable) {
        progress[pkg] = Progress("error")
        throw e
    }
}

suspend fun downloadLocked(pkg: String, regionId: String, wantVc: Long? = null): DownloadResult {
    val (r, latest) = fetchApp(pkg, regionId)
    // Bản cũ: Play chỉ trả file nếu nó còn giữ versionCode đó cho thiết bị này.
    val app = if (wantVc != null && wantVc != latest.versionCode) latest.copy(versionCode = wantVc, versionName = "(versionCode $wantVc)") else latest
    if (!app.isFree) throw UserError("Chỉ hỗ trợ app miễn phí")

    val base = "${app.packageName}-${app.versionCode}"
    val existing = listOf(File(OUT, "$base.apk"), File(OUT, "$base.apks")).firstOrNull { it.exists() }
    if (existing != null) {
        return DownloadResult(app.info(r), existing.name, existing.length(), emptyList(), 0, cached = true)
    }

    val files = try {
        r.client.delivery.resolve(
            packageName = app.packageName,
            versionCode = app.versionCode,
            offerType = app.offerType,
        )
    } catch (e: PlayException) {
        if (wantVc == null || e is PlayException.RateLimited || e is PlayException.Unauthorized) throw e
        throw UserError("Google Play không còn trả bản versionCode $wantVc cho app này (hoặc bản đó không dành cho máy giả lập)")
    }
    val obb = files.count { it.type != AppFile.Type.BASE && it.type != AppFile.Type.SPLIT }
    val apks = withAllSplits(r, app, files)

    val total = apks.sumOf { it.size }
    var done = 0L
    progress[pkg] = Progress("download", 0, total)

    return withContext(Dispatchers.IO) {
        val work = File(OUT, ".tmp-$base").apply { deleteRecursively(); mkdirs() }
        try {
            val parts = apks.map { f ->
                val name = f.name.ifEmpty { if (f.type == AppFile.Type.BASE) "base.apk" else "split-${f.id}.apk" }
                    .let { if (it.endsWith(".apk")) it else "$it.apk" }
                File(work, name).also {
                    downloadTo(r.http, f, it) { n ->
                        done += n
                        progress[pkg] = Progress("download", done, total, name)
                    }
                }
            }
            progress[pkg] = Progress("pack", total, total)
            // Chỉ 1 file base → trả .apk cài thẳng. Có split → đóng .apks (zip), giữ chữ ký gốc, không ký lại.
            val out = if (parts.size == 1) {
                File(OUT, "$base.apk").also { parts[0].copyTo(it, overwrite = true) }
            } else {
                val tmp = File(work, "bundle.zip")
                ZipOutputStream(tmp.outputStream()).use { zip ->
                    for (p in parts) {
                        zip.putNextEntry(ZipEntry(p.name))
                        p.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                File(OUT, "$base.apks").also { tmp.renameTo(it) }
            }
            saveMeta(out.name, app.info(r))
            DownloadResult(app.info(r), out.name, out.length(), parts.map { it.name }, obb, cached = false)
        } finally {
            work.deleteRecursively()
        }
    }
}

fun errorMessage(e: Throwable): Pair<HttpStatusCode, String> = when (e) {
    is UserError -> HttpStatusCode.BadRequest to (e.message ?: "Lỗi")
    is PlayException.NotFound -> HttpStatusCode.NotFound to "Không tìm thấy app trên Google Play"
    is PlayException.AppRemoved -> HttpStatusCode.NotFound to "App đã bị gỡ khỏi Google Play"
    is PlayException.AppNotSupported -> HttpStatusCode.UnprocessableEntity to "App không hỗ trợ thiết bị giả lập (Pixel 9a) hoặc bị chặn ở vùng này"
    is PlayException.AppNotPurchased -> HttpStatusCode.UnprocessableEntity to "App trả phí hoặc không được phát hành ở vùng server"
    is PlayException.RequiresAccount -> HttpStatusCode.UnprocessableEntity to "App này cần tài khoản Google, lane ẩn danh không tải được"
    is PlayException.NoFilesAvailable -> HttpStatusCode.UnprocessableEntity to "Play không trả file nào để tải"
    is PlayException.RateLimited -> HttpStatusCode.TooManyRequests to "Google đang giới hạn tốc độ, thử lại sau ít phút"
    is PlayException.Unauthorized -> {
        regions.forEach { it.invalidate() }
        HttpStatusCode.BadGateway to "Phiên ẩn danh bị từ chối, đã tạo lại — hãy thử lại"
    }
    is PlayException -> HttpStatusCode.BadGateway to "Google Play báo lỗi: ${e.message}"
    is IOException -> HttpStatusCode.BadGateway to "Lỗi mạng: ${e.message}"
    else -> HttpStatusCode.InternalServerError to "Lỗi: ${e.message ?: e::class.simpleName}"
}

fun main() {
    val host = System.getenv("HOST") ?: "127.0.0.1"
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    println("Aurora Web: http://$host:$port  (file lưu ở $OUT)")
    for (r in regions) {
        val via = r.proxyUrl?.replace(Regex("//[^@/]*@"), "//***@")?.let { "  qua $it" } ?: ""
        println("  vùng ${r.id}: ${r.label}$via")
    }

    embeddedServer(Netty, port = port, host = host) {
        install(ContentNegotiation) { json() }
        install(StatusPages) {
            exception<Throwable> { call, e ->
                if (e !is UserError) e.printStackTrace()
                val (code, msg) = errorMessage(e)
                call.respond(code, ErrorBody(msg))
            }
        }
        routing {
            get("/") {
                val html = object {}.javaClass.getResource("/index.html")?.readText() ?: "index.html missing"
                call.respondText(html, ContentType.Text.Html)
            }

            get("/api/regions") {
                call.respond(regions.map { RegionInfo(it.id, it.label) })
            }

            get("/api/ip") {
                val r = regionById[call.request.queryParameters["region"] ?: "direct"] ?: regions.first()
                call.respond(withContext(Dispatchers.IO) { publicIp(r) })
            }

            // Sau khi bật/đổi VPN: bỏ phiên ẩn danh cũ (đã gắn với IP cũ), lần gọi sau tạo phiên mới.
            post("/api/reset") {
                regions.forEach { it.invalidate() }
                call.respond(ErrorBody("Đã làm mới phiên"))
            }

            get("/api/search") {
                val q = call.request.queryParameters["q"]?.trim().orEmpty()
                if (q.length < 2) throw UserError("Nhập ít nhất 2 ký tự để tìm")
                val r = regionById[call.request.queryParameters["region"] ?: "direct"] ?: regions.first()
                val apps = r.web.search.results(q).clusters.flatMap { it.apps }
                    .distinctBy { it.packageName }.take(30)
                call.respond(apps.map { it.info() })
            }

            get("/api/progress") {
                val pkg = call.request.queryParameters["pkg"].orEmpty()
                call.respond(progress[pkg] ?: Progress("idle"))
            }

            get("/api/files") {
                call.respond(listFiles())
            }

            delete("/api/files/{name}") {
                val name = call.parameters["name"].orEmpty()
                val f = File(OUT, name)
                if (name.startsWith(".") || f.parentFile != OUT || !f.isFile) throw UserError("Không có file")
                f.delete()
                File(META, "$name.json").delete()
                call.respond(ErrorBody("Đã xoá $name"))
            }

            get("/api/lan") {
                call.respond(lanAddresses().map { "http://$it:$port" })
            }

            get("/api/details") {
                val pkg = parsePackage(call.request.queryParameters["input"] ?: "")
                val regionId = call.request.queryParameters["region"] ?: "auto"
                val (r, app) = fetchApp(pkg, regionId)
                call.respond(app.info(r))
            }

            post("/api/download") {
                val req = call.receive<FetchReq>()
                call.respond(download(parsePackage(req.input), req.region, req.versionCode))
            }

            get("/files/{name}") {
                val name = call.parameters["name"] ?: ""
                val f = File(OUT, name)
                if (name.startsWith(".") || f.parentFile != OUT || !f.isFile) {
                    call.respond(HttpStatusCode.NotFound, ErrorBody("Không có file"))
                    return@get
                }
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$name\"")
                call.respondFile(f)
            }
        }
    }.start(wait = true)
}
