package com.panomc.plugins.sociallogin.frontend

import com.panomc.platform.frontend.FallbackPageEnvironment
import com.panomc.platform.frontend.FallbackPageRegistry
import com.panomc.platform.frontend.FrontendTargetsRefusal
import com.panomc.platform.frontend.FrontendUrlInputs
import com.panomc.platform.frontend.FrontendUrlMap
import com.panomc.platform.frontend.ThemeRouteMap
import com.panomc.platform.frontend.pages.FallbackPageRenderer
import com.panomc.platform.route.FallbackPageRoute
import com.panomc.platform.ui.FrontendMode
import com.panomc.plugins.sociallogin.SocialLoginPlugin
import com.panomc.plugins.sociallogin.config.SocialLoginConfig
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.util.concurrent.TimeUnit

/**
 * The social sign-in on the open front-end (doc 05 sections 10.1 to 10.3): the plugin's `frontend-targets.json` is
 * accepted by the host's URL map, the callback follows the target, the built-in callback page renders from the
 * plugin's own template with the host's route, and the redirect URL of each enabled provider is listed for the
 * compatibility page.
 */
class SocialFrontendTest {
    private class FakeInputs : FrontendUrlInputs {
        var mode = FrontendMode.NONE
        var frontend: Map<String, Any?> = emptyMap()

        override fun mode() = mode
        override fun siteUrl() = "https://site.example"
        override fun websiteUrl() = "https://pano.example"
        override fun overrides(): Map<String, String> = emptyMap()
        override fun frontendUrls() = frontend
        override fun themeRoutes(): ThemeRouteMap? = null
    }

    private class FakeEnvironment : FallbackPageEnvironment {
        var locale = "en-US"

        override fun ready() = true
        override suspend fun blockedByMaintenance(context: RoutingContext) = false
        override fun siteName() = "My Site"
        override fun locale() = locale
        override fun logoUrl() = "/api/v1/website-logo"
        override fun faviconUrl() = "/api/v1/favicon"
        override fun homeUrl() = "/"
    }

    private val logger = LoggerFactory.getLogger("test")
    private val inputs = FakeInputs()
    private val environment = FakeEnvironment()
    private val urls = FrontendUrlMap(inputs, logger)
    private val registry = FallbackPageRegistry(urls, logger)
    private val route = FallbackPageRoute(registry, urls, FallbackPageRenderer(environment, logger), environment, logger)

    private lateinit var plugin: SocialLoginPlugin
    private lateinit var vertx: Vertx
    private var port = 0

    private val targetsFile: String
        get() = javaClass.classLoader.getResourceAsStream("frontend-targets.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    /** What `PluginManager` does when it loads the plugin: the pages first, then the targets file. */
    private fun loadPlugin() {
        registry.registerPlugin(
            plugin.pluginId,
            NAMESPACE,
            listOf(SocialCallbackFallbackPage(plugin)),
            SocialLoginPlugin::class.java.classLoader
        )

        urls.registerPlugin(plugin.pluginId, NAMESPACE, targetsFile)
    }

    @BeforeEach
    fun setUp() {
        plugin = SocialLoginPlugin()

        set(plugin, "pluginId", "pano-plugin-social-login")

        val context = AnnotationConfigApplicationContext()

        context.beanFactory.registerSingleton("environment", environment)
        context.beanFactory.registerSingleton("urls", urls)
        context.refresh()

        set(plugin, "applicationContext", context)

        vertx = Vertx.vertx()

        val router = Router.router(vertx)

        route.paths.forEach { path ->
            router.route(path.routeType.vertxHttpMethod, path.url).order(route.order).handler(route.getHandler())
        }

        port = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)
    }

    private fun set(target: Any, field: String, value: Any) {
        val declared = generateSequence<Class<*>>(target.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .first { it.name == field }

        declared.isAccessible = true
        declared.set(target, value)
    }

    private fun <T> Future<T>.blockingGet(): T = toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

    private class Answer(val status: Int, val headers: Map<String, String>, val body: String)

    private fun get(path: String): Answer =
        vertx.createHttpClient(HttpClientOptions().setKeepAlive(false)).request(HttpMethod.GET, port, "127.0.0.1", path)
            .compose { it.send() }
            .compose { response ->
                response.body().map { Answer(response.statusCode(), response.headers().entries().associate { e -> e.key.lowercase() to e.value }, it.toString()) }
            }
            .blockingGet()

    // --- the targets file --------------------------------------------------------

    @Test
    fun `the targets file declares callback with its fallback page, link and complete without`() {
        loadPlugin()

        val callback = urls.targets().first { it.id == "social-login.callback" }
        val link = urls.targets().first { it.id == "social-login.link" }
        val complete = urls.targets().first { it.id == "social-login.complete" }

        assertEquals("pano-plugin-social-login", callback.owner)
        assertEquals("/social-login/callback", callback.defaultPath)
        assertTrue(callback.fallback)
        assertTrue(callback.createsSession)

        assertEquals("/social-login/link", link.defaultPath)
        assertFalse(link.fallback)
        assertEquals("/social-login/complete", complete.defaultPath)
        assertFalse(complete.fallback)

        assertTrue(registry.has("social-login.callback"))
        assertFalse(registry.has("social-login.link"))
    }

    @Test
    fun `a plugin without its fallback page fails the load, naming the target`() {
        val refusal = assertThrows<FrontendTargetsRefusal> { urls.registerPlugin("pano-plugin-social-login", NAMESPACE, targetsFile) }

        assertTrue(refusal.message!!.contains("social-login.callback"))
    }

    @Test
    fun `unloading the plugin drops the page`() {
        loadPlugin()

        registry.unregisterPlugin(plugin.pluginId)
        urls.unregisterPlugin(plugin.pluginId)

        assertEquals(404, get("/_pano/social-login.callback?provider=google&code=c&state=s").status)
    }

    // --- the callback link -------------------------------------------------------------

    @Test
    fun `the callback redirect carries provider, code and state to the built-in page, to a theme path or to a front-end`() {
        loadPlugin()

        val params = linkedMapOf("provider" to "google", "code" to "4/abc", "state" to "s1")

        assertEquals("https://pano.example/_pano/social-login.callback?provider=google&code=4%2Fabc&state=s1", urls.url("social-login.callback", params))

        inputs.mode = FrontendMode.THEME

        assertEquals("https://site.example/social-login/callback?provider=google&code=4%2Fabc&state=s1", urls.url("social-login.callback", params))

        inputs.mode = FrontendMode.CUSTOM_APP
        inputs.frontend = mapOf("social-login.callback" to "/oauth/done")

        assertEquals("https://site.example/oauth/done?provider=google&code=4%2Fabc&state=s1", urls.url("social-login.callback", params))
    }

    @Test
    fun `the link and complete targets resolve in a theme and are omitted when nothing serves them`() {
        loadPlugin()

        inputs.mode = FrontendMode.THEME

        assertEquals("https://site.example/social-login/link?token=t&provider=github", urls.url("social-login.link", linkedMapOf("token" to "t", "provider" to "github")))
        assertEquals("https://site.example/social-login/complete?token=t", urls.url("social-login.complete", mapOf("token" to "t")))

        inputs.mode = FrontendMode.NONE

        assertEquals(null, urls.url("social-login.link", mapOf("token" to "t")))
    }

    // --- the page ------------------------------------------------------------------------

    @Test
    fun `the callback page posts provider, code and state once on load`() {
        loadPlugin()

        val answer = get("/_pano/social-login.callback?provider=discord&code=abc&state=bG9naW4%3D")

        assertEquals(200, answer.status)
        assertTrue(answer.body.contains("""data-pano-endpoint="plugin:/process""""))
        assertTrue(answer.body.contains("data-pano-auto"))
        assertTrue(answer.body.contains("""name="provider" value="discord""""))
        assertTrue(answer.body.contains("""name="code" value="abc""""))
        // Handlebars writes "=" as a character reference; the browser reads it back as "=".
        assertTrue(answer.body.contains("""name="state" value="bG9naW4&#x3D;""""))
        assertTrue(answer.body.contains("Signing you in"))
    }

    @Test
    fun `a refused consent or a missing code shows a message instead of posting`() {
        loadPlugin()

        listOf("/_pano/social-login.callback", "/_pano/social-login.callback?provider=google&error=access_denied", "/_pano/social-login.callback?provider=google&code=c").forEach {
            val answer = get(it)

            assertEquals(200, answer.status, it)
            assertFalse(answer.body.contains("data-pano-auto"), it)
            assertFalse(answer.body.contains("data-pano-endpoint"), it)
            assertTrue(answer.body.contains("This sign-in link is not valid"), it)
        }
    }

    @Test
    fun `the page escapes the query and speaks the site language`() {
        loadPlugin()

        environment.locale = "tr"

        val answer = get("/_pano/social-login.callback?provider=%22%3E%3Cscript%3E&code=c&state=s")

        assertEquals(200, answer.status)
        assertFalse(answer.body.contains("\"><script>"))
        assertTrue(answer.body.contains("Giriş yapılıyor"))
    }

    @Test
    fun `a front-end that claims the callback gets the visitor with the query`() {
        loadPlugin()

        inputs.frontend = mapOf("social-login.callback" to "/oauth/done")

        val answer = get("/_pano/social-login.callback?provider=google&code=c1&state=s1")

        assertEquals(302, answer.status)
        assertEquals("https://site.example/oauth/done?provider=google&code=c1&state=s1", answer.headers["location"])
    }

    // --- the redirect URLs ------------------------------------------------------------------

    @Test
    fun `each enabled provider lists its redirect URL under the redirect base`() {
        val listed = SocialExternalUrls.urlsOf(
            SocialLoginConfig(redirectBaseUrl = "https://pano.example/", googleEnabled = true, githubEnabled = true),
            "pano-plugin-social-login"
        ) { error("the website URL is not needed") }

        assertEquals(listOf("Google redirect URL", "GitHub redirect URL"), listed.map { it.label })
        assertEquals(
            listOf(
                "https://pano.example/api/plugins/pano-plugin-social-login/providers/google/callback",
                "https://pano.example/api/plugins/pano-plugin-social-login/providers/github/callback"
            ),
            listed.map { it.url }
        )
    }

    @Test
    fun `without a redirect base the website URL is used, and nothing is listed while every provider is off`() {
        val listed = SocialExternalUrls.urlsOf(SocialLoginConfig(twitterEnabled = true), "pano-plugin-social-login") { "https://pano.example" }

        assertEquals("https://pano.example/api/plugins/pano-plugin-social-login/providers/twitter/callback", listed.single().url)
        assertEquals("X redirect URL", listed.single().label)
        assertTrue(SocialExternalUrls.urlsOf(SocialLoginConfig(), "pano-plugin-social-login") { "https://pano.example" }.isEmpty())
    }

    private companion object {
        const val NAMESPACE = "social-login"
    }
}
