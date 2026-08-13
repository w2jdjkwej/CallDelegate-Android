package com.example.calldelegate.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.calldelegate.BuildConfig
import com.example.calldelegate.core.ai.DefaultAiModuleRegistry
import com.example.calldelegate.core.ai.DefaultCallSessionController
import com.example.calldelegate.core.ai.DefaultHumanTakeoverController
import com.example.calldelegate.core.ai.EnergyVoiceActivityDetector
import com.example.calldelegate.core.ai.RuleSummaryGenerator
import com.example.calldelegate.core.ai.adaptation.AdaptiveSpeechRuntime
import com.example.calldelegate.core.ai.adaptation.AndroidDeviceProfileManager
import com.example.calldelegate.core.ai.mock.MockSpeechRecognizer
import com.example.calldelegate.core.ai.mock.MockSpeechSynthesizer
import com.example.calldelegate.core.ai.model.AndroidModelManager
import com.example.calldelegate.core.ai.model.ModelPackageValidator
import com.example.calldelegate.core.ai.speech.ActiveModelSource
import com.example.calldelegate.core.ai.speech.NBestRecognitionReranker
import com.example.calldelegate.core.ai.speech.NativeVoskEngineFactory
import com.example.calldelegate.core.ai.speech.NativeSherpaTtsEngineFactory
import com.example.calldelegate.core.ai.speech.UtteranceCompleteness
import com.example.calldelegate.core.ai.speech.SherpaSpeechSynthesizer
import com.example.calldelegate.core.ai.speech.SlotFilledReplyPrefetch
import com.example.calldelegate.core.ai.speech.SlotReplyPrefetchResult
import com.example.calldelegate.core.ai.speech.SceneHotwordConfigSource
import com.example.calldelegate.core.ai.speech.SceneHotwordProvider
import com.example.calldelegate.core.ai.speech.FileSynthesizedSpeechStore
import com.example.calldelegate.core.ai.speech.SynthesizedSpeechStore
import com.example.calldelegate.core.ai.speech.SwitchingSpeechRecognizer
import com.example.calldelegate.core.ai.speech.SwitchingSpeechSynthesizer
import com.example.calldelegate.core.ai.speech.VoskEndpointerDelays
import com.example.calldelegate.core.ai.speech.VoskRecognizerOptions
import com.example.calldelegate.core.ai.speech.VoskSpeechRecognizer
import com.example.calldelegate.core.common.AppResult
import com.example.calldelegate.core.ai.rules.AssetRuleProvider
import com.example.calldelegate.core.ai.rules.AndroidRuleLogger
import com.example.calldelegate.core.ai.rules.JsonDialogueEngine
import com.example.calldelegate.core.ai.rules.RegexEntityExtractor
import com.example.calldelegate.core.ai.rules.RuleBasedIntentClassifier
import com.example.calldelegate.core.ai.rules.RuleProvider
import com.example.calldelegate.core.ai.coordination.AutomatedCallSessionBridge
import com.example.calldelegate.core.ai.coordination.TransportAwareAiAnswerRouter
import com.example.calldelegate.core.ai.coordination.ExternalCallCoordinator
import com.example.calldelegate.core.audio.AndroidAudioOutputSink
import com.example.calldelegate.core.audio.BuiltInPresetRepository
import com.example.calldelegate.core.audio.ExampleVoIPAdapter
import com.example.calldelegate.core.audio.SimulatedCallSource
import com.example.calldelegate.core.audio.DefaultAudioInputRegistry
import com.example.calldelegate.core.audio.DefaultRecordingAudioNormalizer
import com.example.calldelegate.core.audio.PresetAudioInputSource
import com.example.calldelegate.core.audio.WavSessionRecordingStore
import com.example.calldelegate.core.audio.capture.AudioRecordPcmReader
import com.example.calldelegate.core.audio.capture.CallAudioCaptureEngine
import com.example.calldelegate.core.audio.capture.DownlinkCallRecorder
import com.example.calldelegate.core.audio.capture.MicrophoneTurnAudioInputSource
import com.example.calldelegate.core.audio.capture.StreamingTurnAudioInputSource
import com.example.calldelegate.core.audio.telecom.TelecomCallAudioBridge
import com.example.calldelegate.core.audio.telecom.TelecomCallRegistry
import com.example.calldelegate.core.audio.telecom.TelecomCallSource
import com.example.calldelegate.telecom.recording.ShizukuCallUplinkAudioSink
import com.example.calldelegate.telecom.recording.SpeakerphoneCallResponseSink
import com.example.calldelegate.telecom.recording.ShizukuCaptureConnector
import com.example.calldelegate.telecom.recording.ShizukuCarrierCallRecorder
import com.example.calldelegate.domain.api.ExternalCallAdapter
import com.example.calldelegate.domain.coordination.CallTransportRouter
import com.example.calldelegate.core.common.Clock
import com.example.calldelegate.core.common.PerformanceMonitor
import com.example.calldelegate.core.common.SystemClock
import com.example.calldelegate.data.local.CallEntityMapper
import com.example.calldelegate.data.local.DataStoreSettingsRepository
import com.example.calldelegate.data.local.NoOpPrivateFileCipher
import com.example.calldelegate.data.local.RoomCallRepository
import com.example.calldelegate.data.local.db.CallDao
import com.example.calldelegate.data.local.db.CallDatabase
import com.example.calldelegate.domain.api.AiModuleRegistry
import com.example.calldelegate.domain.api.AudioInputRegistry
import com.example.calldelegate.domain.api.AudioInputSource
import com.example.calldelegate.domain.api.AudioOutputSink
import com.example.calldelegate.domain.api.CallRepository
import com.example.calldelegate.domain.api.AiAnswerRouter
import com.example.calldelegate.domain.model.InputMode
import com.example.calldelegate.domain.api.CallResponseAudioSink
import com.example.calldelegate.domain.api.CallSessionController
import com.example.calldelegate.domain.api.DialogueEngine
import com.example.calldelegate.domain.api.DeviceProfileProvider
import com.example.calldelegate.domain.api.EntityExtractor
import com.example.calldelegate.domain.api.HumanTakeoverController
import com.example.calldelegate.domain.api.IntentClassifier
import com.example.calldelegate.domain.api.ModelManager
import com.example.calldelegate.domain.api.PresetRepository
import com.example.calldelegate.domain.api.RecordingAudioNormalizer
import com.example.calldelegate.domain.api.PrivateFileCipher
import com.example.calldelegate.domain.api.SessionRecordingStore
import com.example.calldelegate.domain.api.SettingsRepository
import com.example.calldelegate.domain.api.SpeechRecognizer
import com.example.calldelegate.domain.api.SpeechRuntimeManager
import com.example.calldelegate.domain.api.SpeechSynthesizer
import com.example.calldelegate.domain.api.SummaryGenerator
import com.example.calldelegate.domain.api.VoiceActivityDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun json(): Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Provides @Singleton fun clock(): Clock = SystemClock
    @Provides @Singleton fun performanceMonitor() = PerformanceMonitor()
    @Provides @Singleton fun deviceProfiles(@ApplicationContext context: Context): DeviceProfileProvider =
        AndroidDeviceProfileManager(context)

    @Provides @Singleton fun database(@ApplicationContext context: Context) = CallDatabase.get(context)
    @Provides fun callDao(database: CallDatabase): CallDao = database.callDao()
    @Provides @Singleton fun callMapper(json: Json) = CallEntityMapper(json)
    @Provides @Singleton fun calls(
        @ApplicationContext context: Context,
        dao: CallDao,
        mapper: CallEntityMapper,
    ): CallRepository = RoomCallRepository(dao, mapper, java.io.File(context.filesDir, "recordings"))
    @Provides @Singleton fun settings(@ApplicationContext context: Context): SettingsRepository = DataStoreSettingsRepository(context)
    @Provides @Singleton fun cipher(): PrivateFileCipher = NoOpPrivateFileCipher()

    // EnergyVoiceActivityDetector holds per-turn mutable state (speech-seen flag, accumulated
    // silence, subframe buffer), so it is intentionally unscoped: the microphone and telecom turn
    // sources each get their own instance instead of sharing one across two capture paths.
    @Provides fun vad(): VoiceActivityDetector = EnergyVoiceActivityDetector()
    @Provides @Singleton fun presets(): PresetRepository = BuiltInPresetRepository()
    // Microphone turns go through the same reversible-endpoint segmenter as call audio, so a
    // mid-sentence pause becomes an endpoint candidate that rolls back when the caller resumes.
    @Provides @Singleton fun microphone(
        @ApplicationContext context: Context,
        vad: VoiceActivityDetector,
    ): MicrophoneTurnAudioInputSource = MicrophoneTurnAudioInputSource(
        hasRecordAudioPermission = {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        },
        readerFactory = { AudioRecordPcmReader() },
        vad = vad,
        captureDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mic-capture").apply { isDaemon = true }
        }.asCoroutineDispatcher(),
        frameProcessingDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mic-vad-processing").apply { isDaemon = true }
        }.asCoroutineDispatcher(),
    )
    @Provides @Singleton fun presetInput(presets: PresetRepository) = PresetAudioInputSource(presets)
    @Provides @Singleton fun audioInputs(microphone: MicrophoneTurnAudioInputSource, preset: PresetAudioInputSource): AudioInputRegistry =
        DefaultAudioInputRegistry(setOf<AudioInputSource>(microphone, preset))
    @Provides @Singleton fun audioOutput(): AudioOutputSink = AndroidAudioOutputSink()
    @Provides @Singleton fun recordingStore(@ApplicationContext context: Context): SessionRecordingStore = WavSessionRecordingStore(context)
    @Provides @Singleton fun recordingAudioNormalizer(): RecordingAudioNormalizer = DefaultRecordingAudioNormalizer()

    // Continuous downlink (remote-party) audio recorder using the 4-source fallback chain
    // (VOICE_COMMUNICATION → VOICE_CALL → VOICE_RECOGNITION → MIC) with automatic microphone
    // mute, adapted from CallProxyDemo's AudioRecordFallbackRecorder strategy.
    // Runs on a dedicated single-thread dispatcher to keep AudioRecord I/O off the main thread.
    @Provides @Singleton fun downlinkCallRecorder(
        @ApplicationContext context: Context,
        recordingStore: SessionRecordingStore,
        recordingAudioNormalizer: RecordingAudioNormalizer,
    ): DownlinkCallRecorder = DownlinkCallRecorder(
        context = context,
        recordingStore = recordingStore,
        recordingAudioNormalizer = recordingAudioNormalizer,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        captureDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "downlink-capture").apply { isDaemon = true }
        }.asCoroutineDispatcher(),
    )

    @Provides @Singleton fun telecomCallRegistry(): TelecomCallRegistry = TelecomCallRegistry()
    @Provides @Singleton fun shizukuCaptureConnector(
        @ApplicationContext context: Context,
    ): ShizukuCaptureConnector = ShizukuCaptureConnector(context)
    @Provides @Singleton fun shizukuCarrierCallRecorder(
        @ApplicationContext context: Context,
        connector: ShizukuCaptureConnector,
        audioBridge: TelecomCallAudioBridge,
    ): ShizukuCarrierCallRecorder = ShizukuCarrierCallRecorder(context, connector, audioBridge)

    // Streaming call-audio capture (Stage 3). Confined to a dedicated single background thread; the
    // app-scoped SupervisorJob keeps capture structured (no GlobalScope). WAV lands in app-private
    // storage only — never uploaded (no INTERNET permission).
    @Provides @Singleton fun callAudioCaptureEngine(@ApplicationContext context: Context): CallAudioCaptureEngine =
        CallAudioCaptureEngine(
            readerFactory = { AudioRecordPcmReader() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            captureDispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "call-audio-capture").apply { isDaemon = true }
            }.asCoroutineDispatcher(),
            wavDirectory = java.io.File(context.filesDir, "call_captures"),
        )

    // --- External-call transports + coordination (Stage 4 M2) ---
    // Each transport is a fixed @Singleton binding; the router adds the runtime "which is active"
    // dimension. SIMULATED gets AI audio via the mic; TELECOM is honestly audio-less for the AI
    // chain (carrier audio is SILENCED for non-privileged apps); VOIP is a compile-safe stub.
    @Provides @Singleton fun simulatedCallSource(microphone: MicrophoneTurnAudioInputSource): SimulatedCallSource =
        SimulatedCallSource(audioInput = microphone)
    @Provides @Singleton fun telecomCallAudioBridge(): TelecomCallAudioBridge =
        TelecomCallAudioBridge()
    @Provides @Singleton fun telecomTurnAudioInput(
        audioBridge: TelecomCallAudioBridge,
        vad: VoiceActivityDetector,
        utteranceCompleteness: UtteranceCompleteness,
    ): StreamingTurnAudioInputSource = StreamingTurnAudioInputSource(
        source = audioBridge,
        vad = vad,
        // This source carries the call downlink, not the microphone. Leaving it on the class default
        // made every real-call record claim MICROPHONE, which is what a session that had fallen back
        // to the microphone would also say.
        mode = InputMode.CALL_AUDIO,
        earlyEndpointGraceMs = StreamingTurnAudioInputSource.DEFAULT_EARLY_ENDPOINT_GRACE_MS,
        utteranceLooksComplete = { snapshot ->
            utteranceCompleteness.snapshotLooksComplete(snapshot)
        },
        frameProcessingDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "call-vad-processing").apply { isDaemon = true }
        }.asCoroutineDispatcher(),
        /*
         * Four 20 ms subframes per accept() rather than one, because a large part of what the
         * decode costs is charged per call and not per sample.
         *
         * Measured on device on 2026-08-09, one call each. Grouping cut the crossings into the
         * recognizer from 161/120/128 per turn to 48/31/12, and with them: rtf 1.16/1.13/1.67 ->
         * 0.79/0.88/1.62, queue depth 45/28/60 -> 13/6/6, and the drain the caller actually sits
         * through after the endpoint commits, 1,392/219/1,503 ms -> 624/0/402 ms. Per second of
         * audio, accept fell from 1.10 to 0.78 and from 1.06 to 0.83 on the two turns long enough
         * for the fixed cost to amortize; the 900 ms turn stayed where it was, which is the same
         * finding read from the other end.
         *
         * VAD is untouched: it always receives its own fixed 20 ms subframe, and this only groups
         * those subframes on the way to the recognizer. Endpoint timing cannot move because of it.
         *
         * Not widened further on the evidence of one call apiece. 80 ms already puts the decode
         * under real time, and the returns past it are the part not yet measured.
         */
        recognitionChunkDurationMs = 80L,
    )

    @Provides @Singleton fun utteranceCompleteness() = UtteranceCompleteness()
    @Provides @Singleton fun shizukuCallUplinkAudioSink(
        connector: ShizukuCaptureConnector,
    ): ShizukuCallUplinkAudioSink = ShizukuCallUplinkAudioSink(connector)

    /**
     * The assistant's voice on a real call, played out loud so the handset's own microphone carries
     * it up the line.
     *
     * Injection writes the reply straight into the call's uplink, which is what the far end needs.
     * The first attempt used the call-redirection API and failed -- no registered call assistant --
     * so the track is now built the way AOSP's CallRecordingTonePlayer builds one, preferring the
     * TYPE_TELEPHONY output. That route exists here (`in_call_music` feeds `telephony_tx`) and its
     * permission, MODIFY_PHONE_STATE, is held by shell, which is what the Shizuku service runs as.
     *
     * SpeakerphoneCallResponseSink stays as the acoustic fallback for devices that refuse this.
     */
    @Provides @Singleton fun callResponseAudioSink(
        uplinkSink: ShizukuCallUplinkAudioSink,
    ): CallResponseAudioSink = uplinkSink
    @Provides @Singleton fun telecomCallSource(
        registry: TelecomCallRegistry,
        audioBridge: TelecomCallAudioBridge,
        turnAudioInput: StreamingTurnAudioInputSource,
        responseSink: CallResponseAudioSink,
    ): TelecomCallSource =
        TelecomCallSource(registry, audioBridge, turnAudioInput, responseSink)
    @Provides @Singleton fun voipCallSource(): ExampleVoIPAdapter = ExampleVoIPAdapter()
    @Provides @Singleton fun callTransportRouter(
        simulated: SimulatedCallSource,
        telecom: TelecomCallSource,
        voip: ExampleVoIPAdapter,
    ): CallTransportRouter =
        CallTransportRouter(setOf<ExternalCallAdapter>(simulated, telecom, voip))
    @Provides @Singleton fun aiAnswerRouter(
        coordinator: ExternalCallCoordinator,
        controller: CallSessionController,
    ): AiAnswerRouter = TransportAwareAiAnswerRouter(coordinator, controller)
    @Provides @Singleton fun externalCallCoordinator(router: CallTransportRouter): ExternalCallCoordinator =
        ExternalCallCoordinator(
            router = router,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    // Automated no-UI multi-turn driver. Provided here; the foreground call service (M4) owns its
    // start()/stop() lifecycle.
    @Provides @Singleton fun automatedCallSessionBridge(
        coordinator: ExternalCallCoordinator,
        controller: CallSessionController,
        settings: SettingsRepository,
    ): AutomatedCallSessionBridge = AutomatedCallSessionBridge(
        coordinator = coordinator,
        controller = controller,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        // Read when the call rings rather than captured here: this is a singleton built at process
        // start, and a delay fixed then would ignore every later change to the setting.
        autoAnswerDelayMillis = { settings.current().autoAnswerDelayMillis },
    )

    @Provides @Singleton fun ruleProvider(@ApplicationContext context: Context, json: Json): RuleProvider = AssetRuleProvider(context, json)
    @Provides @Singleton fun entityExtractor(): EntityExtractor = RegexEntityExtractor()
    @Provides @Singleton fun intentClassifier(
        provider: RuleProvider,
        extractor: EntityExtractor,
    ): IntentClassifier = RuleBasedIntentClassifier(
        provider = provider,
        extractor = extractor,
        logger = AndroidRuleLogger(),
        debugTraceEnabled = BuildConfig.DEBUG,
    )
    @Provides @Singleton fun dialogue(provider: RuleProvider, classifier: IntentClassifier, extractor: EntityExtractor): DialogueEngine =
        JsonDialogueEngine(provider, classifier, extractor)
    @Provides @Singleton fun mockRecognizer() = MockSpeechRecognizer()
    @Provides @Singleton fun sceneHotwords(@ApplicationContext context: Context) = SceneHotwordProvider(
        SceneHotwordConfigSource {
            context.assets.open("scene_hotwords.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        },
    )
    @Provides @Singleton fun voskRecognizer(
        models: ModelManager,
        hotwords: SceneHotwordProvider,
    ) = VoskSpeechRecognizer(
        ActiveModelSource { models.activeModel(com.example.calldelegate.domain.model.ModelType.ASR) },
        NativeVoskEngineFactory(),
        hotwords,
        /*
         * The N-best list, not word confidence: Vosk gives one or the other (see
         * VoskRecognizerOptions), and the list is both the more useful and the cheaper request.
         *
         * More useful because it carries the repair, not just the alarm. On the 172-utterance
         * device run of 2026-08-07 a reading closer to the reference was present in the list on 86
         * turns; word confidence, measured on the same corpus, sat at a mean of 0.93 and was high
         * on turns the recognizer got badly wrong, so it would have raised an alarm without
         * offering anything to do about it.
         *
         * Five covers the confusions that matter here -- 工单/公担, 上客区/上课去 -- and costs
         * lattice work on a small model rather than a second decode.
         */
        recognizerOptions = VoskRecognizerOptions(
            /*
             * Five, and not fewer for speed: narrowing to 3 was measured on device on 2026-08-09
             * and did not decode any faster. Four turns at 3 gave rtf 1.16/1.13/1.67/0.73 against
             * 0.75/1.02/1.12/1.43 at 5 on the call before it, with deeper queues rather than
             * shallower. Lattice width is not what keeps this decode above real time, so the
             * accuracy the list buys is free of any speed argument against it.
             */
            maxAlternatives = 5,
            // Trailing silence before the decoder closes a segment. Stated rather than
            // inherited, because the endpoint path now asks this recognizer whether the
            // caller finished. 0.30 s is under the 500 ms the capture path waits, so the
            // answer can arrive in time to be of use, and over the 140 ms of intra-sentence
            // pause measured across 27 rollbacks on device.
            endpointerDelays = VoskEndpointerDelays(
                startMaxSeconds = 5.0f,
                endSeconds = 0.30f,
                maxSeconds = 20.0f,
            ),
        ),
        /*
         * Raised to urgent-audio priority because the decode has to keep up with a live phone line,
         * and at the default it did not. On the 2026-08-09 21:20 call the thread reported
         * `priority=0 cpus=0-7 cpuset=/top-app`, and the cost of one 20 ms frame swung between 12 ms
         * and 35 ms across turns of the same call while `acceptCpu` tracked wall clock almost
         * exactly -- the thread was on a CPU the whole time, so it was running slowly rather than
         * waiting. That is the shape of a nice-0 task the scheduler is free to leave on a little
         * core at a low clock. Falling behind real time is what puts audio in front of every
         * snapshot the endpoint needs, so this is the same latency as the queue split above, seen
         * from the other end.
         *
         * URGENT_AUDIO rather than AUDIO: this thread is the reason a caller waits, and it is a
         * single thread doing bounded work per frame, so it cannot starve the rest of the app.
         */
        inferenceDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                runnable.run()
            }, "vosk-inference").apply { isDaemon = true }
        }.asCoroutineDispatcher(),
    )
    @Provides @Singleton fun switchingRecognizer(
        mock: MockSpeechRecognizer,
        real: VoskSpeechRecognizer,
    ) = SwitchingSpeechRecognizer(mock, real)
    @Provides @Singleton fun recognizer(switching: SwitchingSpeechRecognizer): SpeechRecognizer = switching
    @Provides @Singleton fun mockSynthesizer() = MockSpeechSynthesizer()
    @Provides @Singleton fun sherpaFactory() = NativeSherpaTtsEngineFactory()
    /**
     * Internal storage, not cacheDir: the system may evict cacheDir silently, and the prewarm cost
     * would then be paid again at an unpredictable time, which defeats the point of prewarming.
     * All three internal locations read at the same speed, so this choice is about lifetime only.
     */
    @Provides @Singleton fun synthesizedSpeechStore(
        @ApplicationContext context: Context,
    ): SynthesizedSpeechStore = FileSynthesizedSpeechStore(
        directory = java.io.File(context.filesDir, "tts-cache"),
        rulesFingerprint = {
            // Lazily hashed on first use, off the main thread. Identifies the reply set the
            // recordings were made from, so edited rules do not leave stale audio behind.
            runCatching {
                context.assets.open("dialogue_rules.json").use { input ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
            }.getOrDefault("unknown-rules")
        },
    )
    @Provides @Singleton fun sherpaSynthesizer(
        models: ModelManager,
        factory: NativeSherpaTtsEngineFactory,
        speechStore: SynthesizedSpeechStore,
    ) = SherpaSpeechSynthesizer(
        ActiveModelSource { models.activeModel(com.example.calldelegate.domain.model.ModelType.TTS) },
        factory,
        inferenceDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tts-inference").apply { isDaemon = true }
        }.asCoroutineDispatcher(),
        persistentStore = speechStore,
    )
    @Provides @Singleton fun switchingSynthesizer(
        mock: MockSpeechSynthesizer,
        real: SherpaSpeechSynthesizer,
    ) = SwitchingSpeechSynthesizer(mock, real)
    @Provides @Singleton fun synthesizer(switching: SwitchingSpeechSynthesizer): SpeechSynthesizer = switching
    @Provides @Singleton fun speechRuntime(
        profiles: DeviceProfileProvider,
        recognizer: SwitchingSpeechRecognizer,
        synthesizer: SwitchingSpeechSynthesizer,
    ): SpeechRuntimeManager = AdaptiveSpeechRuntime(profiles, recognizer, synthesizer)
    @Provides @Singleton fun summary(): SummaryGenerator = RuleSummaryGenerator()
    @Provides @Singleton fun takeover(): HumanTakeoverController = DefaultHumanTakeoverController()
    @Provides @Singleton fun moduleRegistry(runtime: SpeechRuntimeManager): AiModuleRegistry =
        DefaultAiModuleRegistry(runtime)
    @Provides @Singleton fun modelManager(
        @ApplicationContext context: Context,
        json: Json,
        profiles: DeviceProfileProvider,
    ): ModelManager = AndroidModelManager(context, json, ModelPackageValidator(), profiles)

    @Provides @Singleton fun sessionController(
        dialogue: DialogueEngine,
        recognizer: SpeechRecognizer,
        synthesizer: SpeechSynthesizer,
        summary: SummaryGenerator,
        audioInputs: AudioInputRegistry,
        audioOutput: AudioOutputSink,
        recordingStore: SessionRecordingStore,
        recordingAudioNormalizer: RecordingAudioNormalizer,
        calls: CallRepository,
        settings: SettingsRepository,
        takeover: HumanTakeoverController,
        clock: Clock,
        performanceMonitor: PerformanceMonitor,
        speechRuntime: SpeechRuntimeManager,
        downlinkRecorder: DownlinkCallRecorder,
        classifier: IntentClassifier,
        hotwords: SceneHotwordProvider,
        ruleProvider: RuleProvider,
        sherpaSynthesizer: SherpaSpeechSynthesizer,
    ): CallSessionController = DefaultCallSessionController(
        dialogue, recognizer, synthesizer, summary, audioInputs, audioOutput, recordingStore, recordingAudioNormalizer,
        calls, settings, takeover, clock, performanceMonitor, speechRuntime,
        downlinkRecorder = downlinkRecorder,
        intentClassifier = classifier,
        sceneHotwords = hotwords,
        nBestReranker = NBestRecognitionReranker(),
        slotReplyPrefetcher = { sceneId, stateId, slots, languageTag ->
            // The rule file is read through the same provider the dialogue engine uses, so a
            // prefetched reply can never be one the engine has stopped offering.
            (ruleProvider.load() as? AppResult.Success)?.value?.let { rules ->
                val candidates =
                    SlotFilledReplyPrefetch.candidates(rules, sceneId, stateId, slots, languageTag)
                val outcome = sherpaSynthesizer.prefetch(candidates)
                SlotReplyPrefetchResult(
                    candidates = candidates.size,
                    generated = outcome.generated,
                    alreadyStored = outcome.alreadyStored,
                    failed = outcome.failed,
                )
            }
        },
    )
}
