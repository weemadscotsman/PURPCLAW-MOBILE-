package com.example.core.runtime

import org.json.JSONObject

/**
 * IntentResolver — the single tool-routing authority.
 *
 * Replaces legacy client-side keyword matching. Routes operator intent to a
 * real registered tool name via the CapabilityRegistry, never guessed names.
 * CHAT mode still routes, but ExecutionPolicy gates mutating tools with an
 * actionable denial instead of silently skipping execution.
 */
object IntentResolver {

  data class RoutedIntent(
    val tool: String,
    val args: String,
    val origin: String = "ANDROID_NATIVE"
  )

  fun route(prompt: String, mode: ExecutionPolicy.Mode): RoutedIntent? {
    val p = prompt.lowercase()

    return when {
      // Council podcast: one sentence convenes the 8-seat live episode.
      // Placed before the generic open/launch catch-all so "launch a podcast"
      // never routes to android.app.open. Local-only mutation (episode JSON),
      // same class as flashlight/battery — allowed in CHAT and WORK.
      // Council podcast intent split (TASK #11, 2026-08-29):
      // Only CONVENE verbs (convene/launch/run/start/host) + "podcast" route
      // to android.podcast.convene — i.e. STARTING a new live episode.
      // Saved-episode verbs (download/play/listen to ... podcast) must NOT
      // convene a new episode. There is no native saved-playback tool today,
      // so those fall through to null (chat) and the operator is told in
      // AGENT_NOTES_TASK11_PODCAST_LIVELOOP.md. This stops "download the
      // podcast" from silently spinning up a fresh council.
      isSavedPodcastRequest(p) -> null
      p.contains("podcast") || p.contains("convene") ->
        RoutedIntent("android.podcast.convene", extractTopic(prompt))
      p.contains("flashlight") || p.contains("torch") ->
        RoutedIntent(
          "android.flashlight",
          when {
            p.contains("off") -> "off"
            p.contains("on") -> "on"
            else -> "toggle"
          }
        )
      // Live capability discovery. This must query PackageManager through the
      // native tool, never answer from a static registry or model memory.
      (p.contains("list") || p.contains("show") || p.contains("what")) &&
        (p.contains("installed app") || p.contains("apps on") || p.contains("phone apps")) ->
        RoutedIntent("android.app.list", "")
      // "Open Chrome" means launch the installed app. Browser.open is for a
      // URL/search constrained to Chrome ("open YouTube in Chrome").
      Regex("^(please\\s+)?(open|launch|start)\\s+(google\\s+)?chrome[.!?]?$", RegexOption.IGNORE_CASE)
        .matches(prompt.trim()) -> RoutedIntent("android.app.open", JSONObject().put("app", "Chrome").toString())
      // CREATE/BUILD is a WorkSession goal, never a browser search. A later
      // verified artifact may emit OPEN_URL, but the raw goal stays in Work.
      isCreationRequest(p) -> null
      // Browser constraints outrank Android URL associations. In particular,
      // "YouTube in Chrome" must stay in Chrome rather than being captured by
      // the YouTube app. The target/browser pair is carried as structured args.
      isBrowserAction(p) -> {
        val browser = extractBrowserConstraint(prompt)
        val target = extractBrowserTarget(prompt)
        RoutedIntent(
          "android.browser.open",
          JSONObject()
            .put("mode", if (looksLikeSearch(p, target)) "SEARCH" else "OPEN_URL")
            .put("reason", "operator_browser_intent")
            .put("goalId", JSONObject.NULL)
            .put("target", target)
            .put("browser", browser ?: "")
            .toString()
        )
      }
      // URL-shaped request → in-app embedded browser (Dual View), not external app
      (p.contains("open ") || p.contains("go to ") || p.contains("visit ")) &&
        Regex("https?://|\\b[a-z0-9-]+\\.(com|net|org|io|dev|ai|co)\\b").containsMatchIn(p) -> {
        val rawUrl = Regex("[a-zA-Z0-9.-]+\\.[a-z]{2,}[^\\s]*").find(p)?.value ?: ""
        RoutedIntent("android.browser.embed", rawUrl)
      }
      (p.startsWith("embed ") || p.contains("dual view")) ->
        RoutedIntent("android.browser.embed", extractUrl(prompt))
      (p.contains("open ") && (p.contains("http") || p.contains(".com") || p.contains(".org") ||
        p.contains(".io") || p.contains("website"))) && !p.contains("app") ->
        RoutedIntent("android.browser.embed", extractUrl(prompt))
      (p.contains("open ") || p.contains("launch ") || p.contains("start ")) &&
        !p.contains("settings") ->
        RoutedIntent(
          "android.app.open",
          JSONObject().put(
            "app",
            p.substringAfter("open ").substringAfter("launch ").substringAfter("start ")
              .trim().removeSuffix(".").take(40)
          ).toString()
        )
      p.contains("wifi") || p.contains("wi-fi") ->
        RoutedIntent("android.settings.panel", JSONObject().put("panel", "wifi").toString())
      p.contains("bluetooth") ->
        RoutedIntent("android.settings.panel", JSONObject().put("panel", "bluetooth").toString())
      p.contains("volume") ->
        RoutedIntent("android.settings.panel", JSONObject().put("panel", "volume").toString())
      p.contains("vibrate") || p.contains("buzz") ->
        RoutedIntent("android.vibrate", "")
      p.contains("camera") || p.contains("photo") || p.contains("picture") ->
        RoutedIntent("android.camera.capture", "")
      p.contains("battery") ->
        RoutedIntent("android.battery.status", "")
      p.contains("storage") || p.contains("disk") || p.contains("free space") ->
        RoutedIntent("android.storage.status", "")
      p.contains("network") || p.contains("internet connection") ->
        RoutedIntent("android.network.status", "")
      p.contains("device info") || p.contains("phone info") || p.contains("what phone") ->
        RoutedIntent("android.device.info", "")
      p.contains("clipboard") ->
        RoutedIntent("android.clipboard.read", "")
      p.contains("notify me") || p.contains("send a notification") ->
        RoutedIntent("android.notification.send", prompt)
      (p.startsWith("say ") || p.contains("speak ")) && !p.contains("tts") ->
        RoutedIntent("android.tts.speak", prompt.removePrefix("say "))
      p.contains("browser") && (p.contains("open") || p.contains("embed")) ->
        RoutedIntent("android.browser.embed", "")
      else -> null  // pure reasoning turn — no device action detected
    }
  }

  private fun isBrowserAction(p: String): Boolean {
    val action = p.contains("open") || p.contains("search") || p.contains("look up") ||
      p.contains("browse") || p.contains("go to") || p.contains("visit")
    val constrained = p.contains("browser") || p.contains("chrome") || p.contains("firefox") ||
      p.contains("edge") || p.contains("brave") || p.contains("opera") || p.contains("samsung internet")
    val webSearch = p.contains("search the web") || p.contains("search web") ||
      p.contains("look up online") || p.contains("google ")
    return action && (constrained || webSearch)
  }

  private fun isCreationRequest(p: String): Boolean {
    val createVerb = Regex("\\b(build|create|make|write|code|implement|develop|generate|design)\\b").containsMatchIn(p)
    val artifact = Regex(
      "\\b(game|app|application|website|webpage|web\\s+page|site|page|html|css|javascript|tool|project|prototype|artifact)\\b"
    ).containsMatchIn(p)
    return createVerb && artifact
  }

  private fun looksLikeSearch(prompt: String, target: String): Boolean =
    prompt.contains("search") || prompt.contains("look up") || prompt.contains("google ") ||
      !(target.startsWith("http://") || target.startsWith("https://") ||
        Regex("^[a-z0-9-]+\\.(com|org|io|net|dev|ai|co)").containsMatchIn(target.lowercase()))

  private fun extractBrowserConstraint(prompt: String): String? {
    val p = prompt.lowercase()
    return listOf("Samsung Internet", "Chrome", "Firefox", "Brave", "Edge", "Opera")
      .firstOrNull { p.contains(it.lowercase()) }
  }

  private fun extractBrowserTarget(prompt: String): String {
    Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(prompt)?.let {
      return it.value.trimEnd('.', ',', ')')
    }
    Regex("\\b[a-z0-9-]+\\.(com|org|io|net|dev|ai|co)(/\\S*)?", RegexOption.IGNORE_CASE)
      .find(prompt)?.let { return it.value.trimEnd('.', ',', ')') }

    val lower = prompt.lowercase()
    val knownSite = mapOf(
      "youtube" to "https://www.youtube.com",
      "google" to "https://www.google.com",
      "github" to "https://github.com"
    ).entries.firstOrNull { lower.contains(it.key) }?.value
    if (knownSite != null && !lower.contains("search")) return knownSite

    return prompt
      .replace(Regex("(?i)^(please\\s+)?(open|launch|start|search|browse|visit|go to|look up)\\s+"), "")
      .replace(Regex("(?i)\\s+(in|using|with)\\s+(the\\s+)?(chrome|firefox|edge|brave|opera|samsung internet|browser).*$"), "")
      .replace(Regex("(?i)^(the\\s+)?web\\s+(for\\s+)?"), "")
      .trim().ifBlank { "https://www.google.com" }
  }

  /**
   * TASK #11 — true when the operator wants to consume a SAVED episode
   * (download/play/listen to/replay) rather than convene a new one. The verb
   * set is deliberately narrow: "show me the podcast" is ambiguous and could
   * mean either, so it is left to convene (which renders the Studio list as a
   * side effect). Only unambiguous playback verbs are intercepted here.
   */
  private fun isSavedPodcastRequest(p: String): Boolean {
    if (!p.contains("podcast")) return false
    val savedVerbs = listOf("download", "play", "listen to", "replay", "hear the")
    return savedVerbs.any { p.contains(it) }
  }

  /** Strip command scaffolding so the remainder becomes the podcast topic. */
  private fun extractTopic(prompt: String): String {
    val stripped = prompt.lowercase()
      .replace(Regex("^(please\\s+)?(convene|launch|run|start)\\b"), "")
      .replace(Regex("\\b(an?\\s+)?(eight-member\\s+|8-member\\s+)?council\\b"), "")
      .replace(Regex("\\band\\s+(launch|run|start)\\b"), "")
      .replace(Regex("\\ban?\\s+(live\\s+)?podcast\\b"), "")
      .replace(Regex("\\babout\\b"), "")
      .replace(Regex("\\bon the topic of\\b"), "")
      .replace(Regex("\\bdiscussing\\b"), "")
      .replace(Regex("^[^a-z0-9]+|[^a-z0-9]+$"), "")
      .trim()
    return if (stripped.length >= 3) stripped.take(80) else "PurpClaw itself"
  }

  /** Best-effort URL extraction; falls back to a search-style URL. */
  private fun extractUrl(prompt: String): String {
    val urlRegex = Regex("https?://\\S+")
    urlRegex.find(prompt)?.let { return it.value }
    val bare = Regex("\\b[a-z0-9-]+\\.(com|org|io|net|dev|ai|co)\\b*").find(prompt.lowercase())
    return bare?.value?.let { "https://$it" } ?: "https://www.google.com/search?q=" +
      java.net.URLEncoder.encode(prompt.trim(), "UTF-8")
  }
}
