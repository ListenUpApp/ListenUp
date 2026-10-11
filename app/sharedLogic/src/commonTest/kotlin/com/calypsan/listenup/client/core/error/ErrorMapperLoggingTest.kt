package com.calypsan.listenup.client.core.error

import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.client.core.logging.RecordedLogEvent
import com.calypsan.listenup.client.core.logging.withRecordingLoggerFactory
import io.github.oshai.kotlinlogging.Level
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/**
 * What [ErrorMapper] leaves in the log.
 *
 * An exception nobody anticipated is the one a bug report needs most, and the mapper is the last
 * place that still holds the throwable — the [UnexpectedClientError] it returns carries only a sentence.
 * So the catch-all logs it at ERROR, stack trace and all. Every typed mapping is an outcome the app
 * already understands (no network, a timeout, a 4xx), and logging those at ERROR would bury the
 * real faults under expected weather.
 */
class ErrorMapperLoggingTest :
    FunSpec({
        fun List<RecordedLogEvent>.atWarnOrAbove() = filter { it.level == Level.WARN || it.level == Level.ERROR }

        test("an unanticipated exception is logged at ERROR with its throwable") {
            val surprise = IllegalStateException("mapper bug")

            val logged =
                withRecordingLoggerFactory { recorder ->
                    ErrorMapper.map(surprise).shouldBeInstanceOf<UnexpectedClientError>()
                    recorder.events.atWarnOrAbove()
                }

            logged shouldHaveSize 1
            logged.single().level shouldBe Level.ERROR
            logged.single().cause shouldBeSameInstanceAs surprise
        }

        test("an expected transport failure is not logged at WARN or ERROR") {
            val logged =
                withRecordingLoggerFactory { recorder ->
                    ErrorMapper.map(IOException("connection reset"))
                    recorder.events.atWarnOrAbove()
                }

            logged.shouldBeEmpty()
        }

        test("cancellation reaching the catch-all is not logged as a fault") {
            val logged =
                withRecordingLoggerFactory { recorder ->
                    ErrorMapper.map(CancellationException("scope left"))
                    recorder.events.atWarnOrAbove()
                }

            logged.shouldBeEmpty()
        }
    })
