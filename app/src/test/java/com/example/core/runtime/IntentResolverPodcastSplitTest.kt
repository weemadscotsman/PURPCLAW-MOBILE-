package com.example.core.runtime

import com.example.core.model.InteractionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TASK #11 (2026-08-29) — IntentResolver podcast split.
 *
 * Before the fix, ANY utterance containing "podcast" routed to
 * android.podcast.convene — including "download the podcast" / "play the
 * podcast", which silently spun up a fresh council instead of playing the
 * saved episode. These tests pin the split:
 *   - saved-episode verbs (download/play/listen to/replay/hear the) -> null (chat)
 *   - convene verbs (convene/launch/run/start/host) + podcast -> convene
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IntentResolverPodcastSplitTest {

  private val mode = ExecutionPolicy.Mode.CHAT

  @Test
  fun downloadThePodcast_doesNotConvene() {
    val r = IntentResolver.route("download the podcast", mode)
    assertNull("download the podcast must not start a new episode", r)
  }

  @Test
  fun playThePodcast_doesNotConvene() {
    val r = IntentResolver.route("play the latest podcast", mode)
    assertNull("play the podcast must not start a new episode", r)
  }

  @Test
  fun listenToThePodcast_doesNotConvene() {
    val r = IntentResolver.route("listen to the podcast", mode)
    assertNull("listen to the podcast must not start a new episode", r)
  }

  @Test
  fun replayThePodcast_doesNotConvene() {
    val r = IntentResolver.route("replay the podcast from yesterday", mode)
    assertNull("replay must not start a new episode", r)
  }

  @Test
  fun conveneCouncilPodcast_doesConvene() {
    val r = IntentResolver.route("convene a council podcast about routing parity", mode)
    assertNotNull("convene verb must route", r)
    assertEquals("android.podcast.convene", r!!.tool)
  }

  @Test
  fun launchPodcast_doesConvene() {
    val r = IntentResolver.route("launch a podcast on the state of the stack", mode)
    assertNotNull(r)
    assertEquals("android.podcast.convene", r!!.tool)
  }

  @Test
  fun startPodcast_doesConvene() {
    val r = IntentResolver.route("start a live podcast about Ted's excuses", mode)
    assertNotNull(r)
    assertEquals("android.podcast.convene", r!!.tool)
  }

  @Test
  fun hostPodcast_doesConvene() {
    val r = IntentResolver.route("host a podcast on memory spine truth", mode)
    assertNotNull(r)
    assertEquals("android.podcast.convene", r!!.tool)
  }

  @Test
  fun bareConvene_doesConvene() {
    val r = IntentResolver.route("convene the council", mode)
    assertNotNull(r)
    assertEquals("android.podcast.convene", r!!.tool)
  }
}
