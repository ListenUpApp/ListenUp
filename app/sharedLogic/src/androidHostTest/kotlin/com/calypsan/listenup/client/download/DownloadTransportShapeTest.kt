package com.calypsan.listenup.client.download

import android.content.Context
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What each transport hands the platform. The job and the worker agree on the network rule (never `VALIDATED`, so a
 * LAN-only server qualifies; unmetered only under Wi-Fi only), and the job is the user-initiated, persisted kind with
 * the bytes left as its estimate. The transport follows the release: a job from Android 14, WorkManager below.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadTransportShapeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun request(wifiOnly: Boolean) =
        BookTransferRequest(bookId = "b1", title = "Project Hail Mary", wifiOnly = wifiOnly, remainingBytes = 428_000_000)

    @Test
    @Config(sdk = [34])
    fun `the Android 14 job is user-initiated, persisted, estimated and on the download network`() {
        val job = downloadJobInfo(context, request(wifiOnly = true))

        job.isUserInitiated shouldBe true
        job.isPersisted shouldBe true
        job.isRequireStorageNotLow shouldBe true
        job.id shouldBe downloadJobIdFor("b1")
        job.service.className shouldBe BookTransferJobService::class.java.name
        job.estimatedNetworkDownloadBytes shouldBe 428_000_000
        job.extras.getString(JOB_EXTRA_BOOK_ID) shouldBe "b1"
        job.extras.getString(JOB_EXTRA_TITLE) shouldBe "Project Hail Mary"
        val network = job.requiredNetwork.shouldNotBeNull()
        network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) shouldBe true
        network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) shouldBe true
        network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) shouldBe false
    }

    @Test
    @Config(sdk = [36])
    fun `off Wi-Fi only the job takes any network, still without validation`() {
        val network = downloadJobInfo(context, request(wifiOnly = false)).requiredNetwork.shouldNotBeNull()

        network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) shouldBe false
        network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) shouldBe false
    }

    @Test
    fun `a book's job id is stable, so the job scheduled before a restart is the one cancelled after it`() {
        downloadJobIdFor("book-42") shouldBe downloadJobIdFor("book-42")
    }

    @Test
    @Config(sdk = [33])
    fun `the worker request is one unique run per book on the same network rule, with linear backoff`() {
        val wifiOnly = buildBookTransferRequest(request(wifiOnly = true)).workSpec
        val anyNetwork = buildBookTransferRequest(request(wifiOnly = false)).workSpec

        // The network rule travels as the request itself (WorkManager then requires no network *type* of its own).
        val wifiRule = wifiOnly.constraints.requiredNetworkRequest.shouldNotBeNull()
        val anyRule = anyNetwork.constraints.requiredNetworkRequest.shouldNotBeNull()
        wifiRule.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) shouldBe true
        anyRule.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) shouldBe false
        anyRule.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) shouldBe true
        wifiRule.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) shouldBe false
        anyRule.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) shouldBe false
        wifiOnly.backoffPolicy shouldBe BackoffPolicy.LINEAR
        wifiOnly.input.getString(BookTransferWorker.KEY_BOOK_ID) shouldBe "b1"
        buildBookTransferRequest(request(wifiOnly = true)).tags shouldContain BOOK_TRANSFER_TAG
        bookWorkName("b1") shouldBe "download_book_b1"
        legacyBookTag("b1") shouldBe "download_b1"
    }

    @Test
    @Config(sdk = [33])
    fun `Android 13 downloads on WorkManager`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        bookTransferSchedulerFor(context).transport shouldBe DownloadTransport.WORKMANAGER_FGS
    }

    @Test
    @Config(sdk = [34])
    fun `Android 14 downloads as a user-initiated job`() {
        bookTransferSchedulerFor(context).transport shouldBe DownloadTransport.UIDT
    }
}
