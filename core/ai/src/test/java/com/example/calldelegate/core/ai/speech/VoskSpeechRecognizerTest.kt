package com.example.calldelegate.core.ai.speech

import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.domain.api.SpeechRecognitionContext
import com.example.calldelegate.domain.api.SpeechRecognitionMode
import com.example.calldelegate.domain.model.ActiveModel
import com.example.calldelegate.domain.model.CapturedAudio
import com.example.calldelegate.domain.model.ModelType
import com.example.calldelegate.domain.model.SceneType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.Executors

class VoskSpeechRecognizerTest {
    @Test fun parsesLinuxThreadSchedulingFiles() {
        val status = """
            Name: vosk-inference
            State: R (running)
            Cpus_allowed_list: 0-3
        """.trimIndent()
        val cgroupV1 = "2:cpuset:/background"
        val cgroupV2 = "0::/foreground"

        assertThat(parseCpusAllowedList(status)).isEqualTo("0-3")
        assertThat(parseCpusetGroup(cgroupV1)).isEqualTo("/background")
        assertThat(parseCpusetGroup(cgroupV2)).isEqualTo("/foreground")
    }

    @Test
    fun parseRecognition_readsEscapedJsonText() {
        val recognition = parseVoskRecognition("{\"text\":\"您好 \\\"快递\\\"\"}")

        assertThat(recognition.text).isEqualTo("您好 \"快递\"")
        assertThat(recognition.alternatives).isEmpty()
        assertThat(recognition.words).isEmpty()
    }

    @Test
    fun parseRecognition_readsWordConfidenceAndTiming() {
        val recognition = parseVoskRecognition(
            """
            {"text":"我 到 了",
             "result":[{"word":"我","conf":1.0,"start":0.1,"end":0.3},
                       {"word":"到","conf":0.42,"start":0.3,"end":0.5},
                       {"word":"了","conf":0.9,"start":0.5,"end":0.7}]}
            """.trimIndent(),
        )

        assertThat(recognition.text).isEqualTo("我 到 了")
        assertThat(recognition.words.map(VoskWord::word)).containsExactly("我", "到", "了").inOrder()
        assertThat(recognition.words[1].confidence).isWithin(1e-6f).of(0.42f)
        assertThat(recognition.words[1].startSeconds).isWithin(1e-9).of(0.3)
        assertThat(recognition.words[1].endSeconds).isWithin(1e-9).of(0.5)
    }

    /**
     * Asking for alternatives moves the transcript out of the top level. A reader that only looks
     * at "text" keeps working and silently returns nothing, so this shape is pinned explicitly.
     */
    @Test
    fun parseRecognition_readsNBestListWhereTextIsNoLongerTopLevel() {
        val recognition = parseVoskRecognition(
            """
            {"alternatives":[{"text":"剩余 挤压 多少","confidence":305.4},
                             {"text":"剩余 解押 多少","confidence":301.2},
                             {"text":"剩余 几押 多少"}]}
            """.trimIndent(),
        )

        assertThat(recognition.text).isEqualTo("剩余 挤压 多少")
        assertThat(recognition.alternatives.map(VoskHypothesis::text))
            .containsExactly("剩余 挤压 多少", "剩余 解押 多少", "剩余 几押 多少")
            .inOrder()
        assertThat(recognition.alternatives[0].score).isWithin(1e-3f).of(305.4f)
        assertThat(recognition.alternatives[2].score).isNull()
    }

    @Test
    fun parseRecognition_readsWordsBelongingToTheBestAlternative() {
        val recognition = parseVoskRecognition(
            """
            {"alternatives":[{"text":"我 到 了","confidence":10.0,
                              "result":[{"word":"我","conf":1.0,"start":0.0,"end":0.2},
                                        {"word":"到","conf":0.5,"start":0.2,"end":0.4},
                                        {"word":"了","conf":1.0,"start":0.4,"end":0.6}]},
                             {"text":"我 倒 了","confidence":9.0}]}
            """.trimIndent(),
        )

        assertThat(recognition.text).isEqualTo("我 到 了")
        assertThat(recognition.words.map(VoskWord::word)).containsExactly("我", "到", "了").inOrder()
    }

    @Test
    fun parseRecognition_survivesMalformedJson() {
        assertThat(parseVoskRecognition("not json").text).isEmpty()
        assertThat(parseVoskRecognition("{\"text\":").text).isEmpty()
    }

    @Test
    fun recognize_returnsRealTranscriptFromPcm() = runTest {
        val factory = FakeVoskEngineFactory("您好我是快递员")
        val recognizer = VoskSpeechRecognizer(ActiveModelSource { activeModel() }, factory)

        assertThat(recognizer.initialize()).isInstanceOf(AppResult.Success::class.java)
        val result = recognizer.recognize(audio())

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        val value = (result as AppResult.Success).value
        assertThat(value.text).isEqualTo("您好我是快递员")
        assertThat(value.isMock).isFalse()
        assertThat(value.confidence).isNull()
        assertThat(factory.acceptedSamples).isEqualTo(4)
    }

    @Test
    fun recognizeReportsOnlyAcceptAndFinalTextComputeTime() = runTest {
        var nowNanos = 1_000_000L
        val recognizer = VoskSpeechRecognizer(
            modelSource = ActiveModelSource { activeModel() },
            engineFactory = FakeVoskEngineFactory("recognized"),
            elapsedRealtimeNanos = {
                val current = nowNanos
                nowNanos += 2_000_000L
                current
            },
        )

        val result = recognizer.recognize(audio())
        val metrics = checkNotNull(recognizer.latestRecognitionMetrics.value)

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        assertThat(metrics.computeDurationMillis).isEqualTo(2L)
        assertThat(metrics.inputSamples).isEqualTo(4)
        assertThat(metrics.inputSampleRateHz).isEqualTo(16_000)
        assertThat(metrics.recognizedTextRaw).isEqualTo("recognized")
        assertThat(metrics.errorCode).isNull()
    }

    @Test
    fun recognize_removesSpacesAroundMandarinTokens() = runTest {
        val recognizer = VoskSpeechRecognizer(
            ActiveModelSource { activeModel() },
            FakeVoskEngineFactory("快递 到 了，请 放在 驿站"),
        )

        val result = recognizer.recognize(audio()) as AppResult.Success

        assertThat(result.value.text).isEqualTo("快递到了，请放在驿站")
    }

    @Test
    fun recognize_preservesWordBoundariesForNonMandarinLanguage() = runTest {
        val recognizer = VoskSpeechRecognizer(
            ActiveModelSource { activeModel() },
            FakeVoskEngineFactory("service   台  support"),
        )

        val result = recognizer.recognize(
            audio(),
            SpeechRecognitionContext(languageTag = "en-US"),
        ) as AppResult.Success

        assertThat(result.value.text).isEqualTo("service 台 support")
    }

    @Test
    fun recognizeCorrectsChessPlayerHomophoneOnlyInDeliveryHotwordContext() = runTest {
        val deliveryRecognizer = VoskSpeechRecognizer(
            ActiveModelSource { activeModel() },
            FakeVoskEngineFactory("外卖 棋手"),
            SceneHotwordProvider(SceneHotwordConfigSource { validHotwordConfiguration }),
        )
        val deliveryResult = deliveryRecognizer.recognize(
            audio(),
            SpeechRecognitionContext(
                mode = SpeechRecognitionMode.SCENE_VOCABULARY,
                sceneHints = setOf(SceneType.DELIVERY),
            ),
        ) as AppResult.Success

        val rideHailingRecognizer = VoskSpeechRecognizer(
            ActiveModelSource { activeModel() },
            FakeVoskEngineFactory("外卖 棋手"),
            SceneHotwordProvider(SceneHotwordConfigSource { validHotwordConfiguration }),
        )
        val rideHailingResult = rideHailingRecognizer.recognize(
            audio(),
            SpeechRecognitionContext(
                mode = SpeechRecognitionMode.SCENE_VOCABULARY,
                sceneHints = setOf(SceneType.RIDE_HAILING),
            ),
        ) as AppResult.Success

        assertThat(deliveryResult.value.text).isEqualTo("外卖骑手")
        assertThat(deliveryRecognizer.latestRecognitionMetrics.value?.recognizedTextRaw).isEqualTo("外卖 棋手")
        assertThat(rideHailingResult.value.text).isEqualTo("外卖棋手")
    }

    @Test
    fun sceneRecognitionUsesOneGrammarRecognizerAndRecordsBothAttempts() = runTest {
        val factory = FakeVoskEngineFactory("外卖骑手")
        val hotwords = SceneHotwordProvider(SceneHotwordConfigSource { validHotwordConfiguration })
        val recognizer = VoskSpeechRecognizer(ActiveModelSource { activeModel() }, factory, hotwords)

        recognizer.recognize(audio())
        recognizer.recognize(
            audio(),
            SpeechRecognitionContext(
                mode = SpeechRecognitionMode.SCENE_VOCABULARY,
                sceneHints = setOf(SceneType.DELIVERY),
                isSecondaryPass = true,
            ),
        )

        assertThat(factory.grammarPhrases).containsExactly("外卖 骑手", "[unk]").inOrder()
        assertThat(recognizer.latestRecognitionAttempts.value).hasSize(2)
        assertThat(recognizer.latestRecognitionAttempts.value[0].recognitionMode)
            .isEqualTo(SpeechRecognitionMode.GENERAL)
        assertThat(recognizer.latestRecognitionAttempts.value[1].recognitionMode)
            .isEqualTo(SpeechRecognitionMode.SCENE_VOCABULARY)
    }

    @Test
    fun generalRecognitionKeepsFreeformGrammarWithConfiguredHotwords() = runTest {
        val factory = FakeVoskEngineFactory("房源挂牌价")
        val hotwords = SceneHotwordProvider(SceneHotwordConfigSource { validHotwordConfiguration })
        val recognizer = VoskSpeechRecognizer(ActiveModelSource { activeModel() }, factory, hotwords)

        recognizer.recognize(audio())

        assertThat(factory.grammarPhrases).isNull()
    }

    @Test
    fun recognizeReturnsEveryAlternativeNormalizedTheSameWayAsTheTranscript() = runTest {
        val factory = FakeVoskEngineFactory(
            text = "剩余 挤压 多少",
            alternatives = listOf(
                VoskHypothesis("剩余 挤压 多少", 305.4f),
                VoskHypothesis("剩余 解押 [unk] 多少", 301.2f),
            ),
        )
        val recognizer = VoskSpeechRecognizer(
            modelSource = ActiveModelSource { activeModel() },
            engineFactory = factory,
            recognizerOptions = VoskRecognizerOptions(maxAlternatives = 3),
        )

        val result = recognizer.recognize(audio()) as AppResult.Success

        // Spacing collapsed and [unk] dropped on the alternative exactly as on the transcript: the
        // point of keeping alternatives is that a later stage can compare them as text.
        assertThat(result.value.text).isEqualTo("剩余挤压多少")
        assertThat(result.value.alternatives.map { it.text })
            .containsExactly("剩余挤压多少", "剩余解押多少")
            .inOrder()
        assertThat(result.value.alternatives[0].score).isWithin(1e-3f).of(305.4f)
        assertThat(factory.requestedOptions)
            .isEqualTo(VoskRecognizerOptions(maxAlternatives = 3))
        assertThat(recognizer.latestRecognitionMetrics.value?.alternativeCount).isEqualTo(2)
    }

    @Test
    fun maxAlternativesExperimentCanSelectThePlainRecognizerPath() = runTest {
        val factory = FakeVoskEngineFactory(text = "好的")
        val recognizer = VoskSpeechRecognizer(
            modelSource = ActiveModelSource { activeModel() },
            engineFactory = factory,
            recognizerOptions = VoskRecognizerOptions(maxAlternatives = 5),
        )

        recognizer.setMaxAlternativesOverride(0)
        recognizer.recognize(audio())

        assertThat(factory.requestedOptions).isNull()
    }

    @Test
    fun recognizeFoldsAlternativesThatNormalizeToTheSameText() = runTest {
        val recognizer = VoskSpeechRecognizer(
            modelSource = ActiveModelSource { activeModel() },
            engineFactory = FakeVoskEngineFactory(
                text = "我 到 了",
                alternatives = listOf(
                    VoskHypothesis("我 到 了", 10f),
                    VoskHypothesis("我 到 [unk] 了", 9f),
                    VoskHypothesis("我 倒 了", 8f),
                ),
            ),
            recognizerOptions = VoskRecognizerOptions(maxAlternatives = 3),
        )

        val result = recognizer.recognize(audio()) as AppResult.Success

        assertThat(result.value.alternatives.map { it.text }).containsExactly("我到了", "我倒了").inOrder()
    }

    @Test
    fun recognizeReportsWordConfidenceAsTheResultConfidence() = runTest {
        val recognizer = VoskSpeechRecognizer(
            modelSource = ActiveModelSource { activeModel() },
            engineFactory = FakeVoskEngineFactory(
                text = "我 到 了",
                words = listOf(
                    VoskWord("我", 1.0f, 0.0, 0.2),
                    VoskWord("到", 0.4f, 0.2, 0.4),
                    VoskWord("了", 1.0f, 0.4, 0.6),
                ),
            ),
            recognizerOptions = VoskRecognizerOptions(words = true),
        )

        val result = recognizer.recognize(audio()) as AppResult.Success
        val metrics = checkNotNull(recognizer.latestRecognitionMetrics.value)

        assertThat(result.value.confidence).isWithin(1e-4f).of(0.8f)
        assertThat(result.value.words.map { it.word }).containsExactly("我", "到", "了").inOrder()
        assertThat(metrics.meanWordConfidence).isWithin(1e-4f).of(0.8f)
        // The minimum is what says "one word is doubtful", which a mean of 0.8 hides.
        assertThat(metrics.minimumWordConfidence).isWithin(1e-4f).of(0.4f)
    }

    @Test
    fun recognizeReportsNoConfidenceWhenWordsWereNotRequested() = runTest {
        val recognizer = VoskSpeechRecognizer(ActiveModelSource { activeModel() }, FakeVoskEngineFactory("我到了"))

        val result = recognizer.recognize(audio()) as AppResult.Success

        assertThat(result.value.confidence).isNull()
        assertThat(recognizer.latestRecognitionMetrics.value?.meanWordConfidence).isNull()
        assertThat(recognizer.latestRecognitionMetrics.value?.alternativeCount).isNull()
    }

    @Test
    fun unknownTokensAreExcludedFromWordConfidence() = runTest {
        val recognizer = VoskSpeechRecognizer(
            modelSource = ActiveModelSource { activeModel() },
            engineFactory = FakeVoskEngineFactory(
                text = "我 [unk] 了",
                words = listOf(
                    VoskWord("我", 1.0f, 0.0, 0.2),
                    VoskWord("[unk]", 0.0f, 0.2, 0.4),
                    VoskWord("了", 1.0f, 0.4, 0.6),
                ),
            ),
            recognizerOptions = VoskRecognizerOptions(words = true),
        )

        val result = recognizer.recognize(audio()) as AppResult.Success

        // [unk] already has its own count as a quality signal. Letting it also drag the confidence
        // to 0.67 would report the same fact twice, in a number that reads as acoustic doubt.
        assertThat(result.value.confidence).isWithin(1e-4f).of(1.0f)
        assertThat(result.value.words.map { it.word }).containsExactly("我", "了").inOrder()
        assertThat(recognizer.latestRecognitionMetrics.value?.unknownTokenCount).isEqualTo(1)
    }

    @Test
    fun sceneVocabularyPassDoesNotAskForAlternatives() = runTest {
        val factory = FakeVoskEngineFactory("外卖 骑手")
        val recognizer = VoskSpeechRecognizer(
            modelSource = ActiveModelSource { activeModel() },
            engineFactory = factory,
            hotwords = SceneHotwordProvider(SceneHotwordConfigSource { validHotwordConfiguration }),
            recognizerOptions = VoskRecognizerOptions(maxAlternatives = 3),
        )

        recognizer.recognize(
            audio(),
            SpeechRecognitionContext(
                mode = SpeechRecognitionMode.SCENE_VOCABULARY,
                sceneHints = setOf(SceneType.DELIVERY),
            ),
        )

        // A phrase-list grammar can only produce phrases from that list, so ranking its N-best
        // would rank the list against itself.
        assertThat(factory.requestedOptions?.maxAlternatives ?: 0).isEqualTo(0)
    }

    @Test
    fun askingForBothAnNBestListAndWordConfidenceIsRejected() {
        // Measured, not assumed: the decoder populates one result path or the other, so a request
        // for both is answered with less than was asked for. See VoskRecognizerOptions.
        val error = runCatching { VoskRecognizerOptions(maxAlternatives = 5, words = true) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(VoskRecognizerOptions(maxAlternatives = 5).words).isFalse()
        assertThat(VoskRecognizerOptions(words = true).maxAlternatives).isEqualTo(0)
    }

    @Test
    fun streamingRecognitionAcceptsFramesInOrderAndReportsComputeMetrics() = runTest {
        var nowNanos = 1_000_000L
        var metricNanos = 1_000_000L
        val factory = FakeVoskEngineFactory("streamed")
        val recognizer = VoskSpeechRecognizer(
            modelSource = ActiveModelSource { activeModel() },
            engineFactory = factory,
            elapsedRealtimeNanos = {
                val current = nowNanos
                nowNanos += 2_000_000L
                current
            },
            metricsClockNanos = {
                val current = metricNanos
                metricNanos += 1_000_000L
                current
            },
        )

        val opened = recognizer.openStreamingRecognition(16_000, SpeechRecognitionContext())
        val session = (opened as AppResult.Success).value
        assertThat(session.accept(shortArrayOf(1, 2))).isInstanceOf(AppResult.Success::class.java)
        assertThat(session.accept(shortArrayOf(3, 4))).isInstanceOf(AppResult.Success::class.java)
        val result = session.finish(speechDetected = true)

        assertThat(result).isInstanceOf(AppResult.Success::class.java)
        assertThat((result as AppResult.Success).value.text).isEqualTo("streamed")
        assertThat(factory.acceptedFrames).containsExactly(
            listOf(1.toShort(), 2.toShort()),
            listOf(3.toShort(), 4.toShort()),
        ).inOrder()
        val metrics = checkNotNull(recognizer.latestRecognitionMetrics.value)
        assertThat(metrics.inputSamples).isEqualTo(4)

        // The two fake clocks are counters that advance on every read, and the enqueue side reads
        // them on the caller thread while the worker reads them on the inference dispatcher. How
        // many reads land between an enqueue and its dequeue is therefore decided by the scheduler,
        // which made the exact millisecond values that used to be asserted here vary run to run --
        // observed 2, 3 and 6 for the same span, failing on a different line each time. What the
        // metrics must guarantee is that every span is measured and that the parts stay consistent
        // with the whole; the absolute numbers are an artifact of the fixture, not behaviour.
        assertThat(metrics.computeDurationMillis).isAtLeast(1L)
        assertThat(metrics.recognizerCreateDurationMillis).isAtLeast(1L)
        assertThat(metrics.voskAcceptComputeDurationMillis).isAtLeast(1L)
        assertThat(metrics.voskFinalResultDurationMillis).isAtLeast(1L)
        assertThat(metrics.voskQueueMaxDepth).isAtLeast(1)
        assertThat(metrics.voskDrainDurationMillis).isAtLeast(0L)
        assertThat(metrics.voskQueueWaitDurationMillis).isAtLeast(0L)
        assertThat(metrics.voskQueueWaitMaxMillis).isAtMost(metrics.voskQueueWaitDurationMillis)
        assertThat(factory.recognizerCloseCount).isEqualTo(1)
    }

    @Test
    fun streamingRecognitionKeepsCompletedSegmentsAndUsesOneRecognizerForSnapshots() = runTest {
        val factory = FakeVoskEngineFactory(
            text = "后 半 句",
            completedSegmentAtAccept = 2,
            completedSegmentText = "前 半 句",
            partialText = "后 半",
        )
        val recognizer = VoskSpeechRecognizer(
            modelSource = ActiveModelSource { activeModel() },
            engineFactory = factory,
        )

        val opened = recognizer.openStreamingRecognition(16_000, SpeechRecognitionContext())
        val session = (opened as AppResult.Success).value
        val idBeforeRollback = session.recognizerId
        session.accept(shortArrayOf(1, 2))
        session.accept(shortArrayOf(3, 4))

        val snapshot = session.snapshot() as AppResult.Success
        val result = session.finish(speechDetected = true) as AppResult.Success

        assertThat(snapshot.value.recognizerId).isEqualTo(idBeforeRollback)
        assertThat(snapshot.value.partialTextRaw).isEqualTo("前 半 句 后 半")
        assertThat(result.value.text).isEqualTo("前半句后半句")
        assertThat(recognizer.latestRecognitionMetrics.value?.recognizedTextRaw)
            .isEqualTo("前 半 句 后 半 句")
        assertThat(factory.newRecognizerCount).isEqualTo(1)
    }

    @Test
    fun streamingRecognitionCombinesTheAlternativesOfEverySegment() = runTest {
        val factory = FakeVoskEngineFactory(
            text = "后 半 句",
            completedSegmentAtAccept = 1,
            completedSegmentText = "前 半 句",
            completedSegmentAlternatives = listOf(
                VoskHypothesis("前 半 句", 10f),
                VoskHypothesis("前 伴 句", 8f),
            ),
            alternatives = listOf(
                VoskHypothesis("后 半 句", 20f),
                VoskHypothesis("后 伴 句", 15f),
            ),
        )
        val recognizer = VoskSpeechRecognizer(
            modelSource = ActiveModelSource { activeModel() },
            engineFactory = factory,
            recognizerOptions = VoskRecognizerOptions(maxAlternatives = 2),
        )

        val session = (
            recognizer.openStreamingRecognition(16_000, SpeechRecognitionContext()) as AppResult.Success
            ).value
        session.accept(shortArrayOf(1, 2))
        val result = session.finish(speechDetected = true) as AppResult.Success

        assertThat(result.value.text).isEqualTo("前半句后半句")
        assertThat(result.value.alternatives.map { it.text })
            .containsExactly("前半句后半句", "前伴句后伴句")
            .inOrder()
        assertThat(result.value.alternatives[1].score).isWithin(1e-3f).of(23f)
    }

    @Test
    fun mergingSegmentsRepeatsAConfidentSegmentRatherThanTruncatingTheList() {
        val merged = mergeSegments(
            listOf(
                VoskRecognition("确定 的", listOf(VoskHypothesis("确定 的", 5f))),
                VoskRecognition(
                    "含糊 的",
                    listOf(VoskHypothesis("含糊 的", 4f), VoskHypothesis("含湖 的", 3f)),
                ),
            ),
        )

        // The first segment offers one hypothesis. Stopping at depth 1 would throw away the second
        // segment's alternative, which is the one worth reranking.
        assertThat(merged.alternatives.map(VoskHypothesis::text))
            .containsExactly("确定 的 含糊 的", "确定 的 含湖 的")
            .inOrder()
        assertThat(merged.alternatives[1].score).isWithin(1e-3f).of(8f)
    }

    @Test
    fun mergingSegmentsReportsNoScoreWhenASegmentDidNotScoreItsPick() {
        val merged = mergeSegments(
            listOf(
                VoskRecognition("甲", listOf(VoskHypothesis("甲", null))),
                VoskRecognition("乙", listOf(VoskHypothesis("乙", 4f))),
            ),
        )

        // A sum missing a term is not comparable with one that has it, so it is not reported.
        assertThat(merged.alternatives.single().score).isNull()
    }

    /**
     * A cancel arriving while finish() is still draining must not free the recognizer under it.
     *
     * Both teardowns used to share one `closed` flag, which is set on entry, so the second one
     * concluded there was nothing to wait for and closed the recognizer while the worker was still
     * inside accept(). On the device that read freed memory: SIGSEGV at 0x10 in Kaldi's
     * ProcessNonemitting, on the vosk-inference thread, 2026-08-08.
     */
    @Test
    fun cancellingDuringFinishWaitsInsteadOfClosingTheRecognizerUnderIt() = runBlocking {
        val releaseAccept = CompletableDeferred<Unit>()
        val acceptEntered = CompletableDeferred<Unit>()
        val factory = FakeVoskEngineFactory(
            text = "streamed",
            onAccept = {
                acceptEntered.complete(Unit)
                // Block inside the native call the way a slow decode does, so finish() is still
                // draining when the cancel lands.
                runBlocking { releaseAccept.await() }
            },
        )
        val recognizer = VoskSpeechRecognizer(ActiveModelSource { activeModel() }, factory)
        val session = (
            recognizer.openStreamingRecognition(16_000, SpeechRecognitionContext()) as AppResult.Success
            ).value
        session.accept(shortArrayOf(1, 2))
        acceptEntered.await()

        // UNDISPATCHED so each runs to its first suspension before the assertion below. Plain
        // launches would still be queued, and the assertion would hold even on the broken version.
        // finish() suspends at worker.join(); cancel() must suspend waiting for it. The broken
        // version did not suspend there -- it went straight on to close the recognizer.
        val finishing = launch(start = CoroutineStart.UNDISPATCHED) { session.finish(speechDetected = true) }
        val cancelling = launch(start = CoroutineStart.UNDISPATCHED) { session.cancel() }

        assertThat(factory.recognizerCloseCount).isEqualTo(0)
        releaseAccept.complete(Unit)
        finishing.join()
        cancelling.join()

        // Closed exactly once, and only after the worker had left the native call.
        assertThat(factory.recognizerCloseCount).isEqualTo(1)
        assertThat(factory.acceptsAfterClose).isEqualTo(0)
    }

    @Test
    fun streamingSnapshotReturnsWorkerFailureInsteadOfWaitingForever() = runTest {
        val recognizer = VoskSpeechRecognizer(
            modelSource = ActiveModelSource { activeModel() },
            engineFactory = FakeVoskEngineFactory(text = "unused", failAtAccept = 1),
        )
        val session = (
            recognizer.openStreamingRecognition(16_000, SpeechRecognitionContext()) as AppResult.Success
            ).value
        session.accept(shortArrayOf(1, 2))

        val snapshot = withTimeout(5_000L) { session.snapshot() }

        assertThat(snapshot).isInstanceOf(AppResult.Failure::class.java)
        assertThat((snapshot as AppResult.Failure).error.code).isEqualTo("ASR_RECOGNIZE")
        session.cancel()
    }

    @Test
    fun cancellingAnAbandonedStreamingTurnStillReleasesTheRecognizerForTheNextTurn() = runBlocking {
        val factory = FakeVoskEngineFactory("streamed")
        val recognizer = VoskSpeechRecognizer(ActiveModelSource { activeModel() }, factory)

        val opened = recognizer.openStreamingRecognition(16_000, SpeechRecognitionContext())
        val session = (opened as AppResult.Success).value
        session.accept(shortArrayOf(1, 2))

        // Reproduce the device turn-timeout path: the turn coroutine is cancelled, so the caller's
        // finally block runs cancel() from inside an already-cancelled coroutine. Before the fix the
        // first suspension point threw and the session stayed attached, so every later turn failed
        // with ASR_BUSY for the remainder of the process.
        // UNDISPATCHED so the body reaches awaitCancellation() before the cancel below. A plain
        // launch would still be waiting for its first dispatch on the runBlocking event loop, get
        // cancelled before starting, and never run the finally block this test exists to exercise.
        val abandonedTurn = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                session.cancel()
            }
        }
        abandonedTurn.cancelAndJoin()

        // Localizes how far cleanup got: closeRecognizer() runs immediately before the detach step.
        assertThat(factory.recognizerCloseCount).isEqualTo(1)

        val reopened = recognizer.openStreamingRecognition(16_000, SpeechRecognitionContext())

        assertThat((reopened as? AppResult.Failure)?.error?.code).isNull()
        assertThat(reopened).isInstanceOf(AppResult.Success::class.java)
        assertThat(factory.recognizerCloseCount).isAtLeast(1)
    }

    @Test
    fun recognizeClosesEachRecognizerAfterTheTurn() = runTest {
        val factory = FakeVoskEngineFactory("text")
        val recognizer = VoskSpeechRecognizer(ActiveModelSource { activeModel() }, factory)

        recognizer.recognize(audio())
        recognizer.recognize(audio())

        assertThat(factory.newRecognizerCount).isEqualTo(2)
        assertThat(factory.recognizerCloseCount).isEqualTo(2)
        assertThat(factory.acceptedSamples).isEqualTo(8)
    }

    @Test
    fun recognize_rejectsWrongSampleRate() = runTest {
        val recognizer = VoskSpeechRecognizer(ActiveModelSource { activeModel() }, FakeVoskEngineFactory("text"))
        recognizer.initialize()

        val result = recognizer.recognize(audio(sampleRate = 8_000))

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error.code).isEqualTo("ASR_SAMPLE_RATE")
    }

    @Test
    fun recognizeRemovesUnknownGrammarTokensBeforeReturningText() = runTest {
        val recognizer = VoskSpeechRecognizer(
            ActiveModelSource { activeModel() },
            FakeVoskEngineFactory("[unk] 走 错 楼栋 [unk]"),
        )

        val result = recognizer.recognize(audio()) as AppResult.Success

        assertThat(result.value.text).isEqualTo("走错楼栋")
        assertThat(recognizer.latestRecognitionMetrics.value?.recognizedTextRaw).isEqualTo("走 错 楼栋")
        assertThat(recognizer.latestRecognitionMetrics.value?.unknownTokenCount).isEqualTo(2)
    }

    @Test
    fun initialize_failsWhenModelIsMissing() = runTest {
        val recognizer = VoskSpeechRecognizer(ActiveModelSource { null }, FakeVoskEngineFactory("text"))

        val result = recognizer.initialize()

        assertThat(result).isInstanceOf(AppResult.Failure::class.java)
        assertThat((result as AppResult.Failure).error.code).isEqualTo("ASR_MODEL_MISSING")
    }

    @Test
    fun release_isIdempotent() = runTest {
        val factory = FakeVoskEngineFactory("text")
        val recognizer = VoskSpeechRecognizer(ActiveModelSource { activeModel() }, factory)
        recognizer.initialize()

        recognizer.release()
        recognizer.release()

        assertThat(factory.modelCloseCount).isEqualTo(1)
    }

    @Test
    fun streamingRecognition_usesInjectedInferenceDispatcher() {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-vosk-inference")
        }.asCoroutineDispatcher()
        dispatcher.use {
            val factory = FakeVoskEngineFactory("text")
            val recognizer = VoskSpeechRecognizer(
                modelSource = ActiveModelSource { activeModel() },
                engineFactory = factory,
                inferenceDispatcher = dispatcher,
            )

            runBlocking {
                val session = (
                    recognizer.openStreamingRecognition(16_000, SpeechRecognitionContext()) as AppResult.Success
                    ).value
                session.accept(shortArrayOf(1, 2))
                session.finish(speechDetected = true)
            }

            assertThat(factory.newRecognizerThreadName).startsWith("test-vosk-inference")
            assertThat(factory.acceptThreadName).startsWith("test-vosk-inference")
            assertThat(factory.finalTextThreadName).startsWith("test-vosk-inference")
        }
    }

    private fun activeModel() = ActiveModel(
        type = ModelType.ASR,
        version = "1.0.0",
        displayName = "Test Vosk",
        runtime = "vosk",
        directoryPath = "test-model",
        sampleRateHz = 16_000,
        files = emptyMap(),
    )

    private fun audio(sampleRate: Int = 16_000) = CapturedAudio(
        pcm16 = shortArrayOf(1, 2, 3, 4),
        sampleRateHz = sampleRate,
        durationMillis = 1,
        recordingPath = null,
    )

    private val validHotwordConfiguration = """
        {
          "schemaVersion": 1,
          "globalPhrases": ["房源", "挂牌价"],
          "scenes": {
            "delivery": ["外卖 骑手", "[unk]"],
            "ride_hailing": ["滴滴 司机", "[unk]"],
            "customer_service": ["客服 售后", "[unk]"],
            "real_estate": ["房产 中介", "[unk]"],
            "insurance_finance": ["保险 理赔", "[unk]"],
            "spam_risk": ["贷款 优惠", "[unk]"]
          }
        }
    """.trimIndent()
}

private class FakeVoskEngineFactory(
    private val text: String,
    private val completedSegmentAtAccept: Int? = null,
    private val completedSegmentText: String = "",
    private val partialText: String = text,
    private val failAtAccept: Int? = null,
    private val alternatives: List<VoskHypothesis> = emptyList(),
    private val completedSegmentAlternatives: List<VoskHypothesis> = emptyList(),
    private val words: List<VoskWord> = emptyList(),
    private val onAccept: (() -> Unit)? = null,
) : VoskEngineFactory {
    /** Reads that arrived after close(); on a real recognizer each is a use-after-free. */
    var acceptsAfterClose = 0
    var acceptedSamples = 0
    val acceptedFrames = ArrayList<List<Short>>()
    var newRecognizerCount = 0
    var recognizerCloseCount = 0
    var modelCloseCount = 0
    var grammarPhrases: List<String>? = null
    var requestedOptions: VoskRecognizerOptions? = null
    var newRecognizerThreadName: String? = null
    var acceptThreadName: String? = null
    var finalTextThreadName: String? = null

    override fun openModel(directoryPath: String): VoskModelHandle = object : VoskModelHandle {
        override fun newRecognizer(sampleRateHz: Int): VoskRecognizerHandle {
            newRecognizerThreadName = Thread.currentThread().name
            return object : VoskRecognizerHandle {
                private var acceptCount = 0
                private var handleClosed = false

                init { newRecognizerCount += 1 }

                override fun accept(samples: ShortArray): Boolean {
                    if (handleClosed) acceptsAfterClose += 1
                    acceptThreadName = Thread.currentThread().name
                    acceptedSamples += samples.size
                    acceptedFrames += samples.toList()
                    acceptCount += 1
                    if (acceptCount == failAtAccept) error("synthetic worker failure")
                    onAccept?.invoke()
                    // A real recognizer freed here would already have been read through a dangling
                    // pointer by the time this call returns, so closing mid-call counts too.
                    if (handleClosed) acceptsAfterClose += 1
                    return acceptCount == completedSegmentAtAccept
                }

                override fun result(): VoskRecognition =
                    VoskRecognition(completedSegmentText, completedSegmentAlternatives)

                override fun partialText(): String = partialText

                override fun finalResult(): VoskRecognition {
                    finalTextThreadName = Thread.currentThread().name
                    return VoskRecognition(text, alternatives, words)
                }

                override fun close() {
                    handleClosed = true
                    recognizerCloseCount += 1
                }
            }
        }

        override fun newRecognizer(sampleRateHz: Int, phrases: List<String>): VoskRecognizerHandle {
            grammarPhrases = phrases
            return newRecognizer(sampleRateHz)
        }

        override fun newRecognizer(
            sampleRateHz: Int,
            phrases: List<String>,
            options: VoskRecognizerOptions,
        ): VoskRecognizerHandle {
            requestedOptions = options
            return if (phrases.isEmpty()) newRecognizer(sampleRateHz) else newRecognizer(sampleRateHz, phrases)
        }

        override fun close() { modelCloseCount++ }
    }
}
