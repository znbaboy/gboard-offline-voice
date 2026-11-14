/*
 * Approach (which flags turn on Gboard's consolidated "smart dictation" stack, and the
 * com.google.android.tts package query) follows Gboard-patches by jasonwu1994 (GPLv3):
 * https://github.com/jasonwu1994/Gboard-patches
 * Rewritten for Gboard 18.6.x, with the speech provider pinned to on-device recognition.
 */
package app.template.patches.gboard.voice

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.packageMetadata
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val FLAGS_EXT = "Lapp/template/extension/gboard/AdvancedVoiceFlags;"

// Smart dictation (advanced voice typing) flag definitions.
internal object SmartDictationFlagsFingerprint : Fingerprint(
    name = "<clinit>",
    strings = listOf("handle_fallback_inside_sd_stack", "enable_s3_as_fallback_in_smart_dictation"),
)

// Assistant voice typing (NGA) flag definitions.
internal object NgaFlagsFingerprint : Fingerprint(
    name = "<clinit>",
    strings = listOf("enable_nga", "nga_mic_tap_handshake_timeout_ms"),
)

/**
 * Flag name -> replacement method in [FLAGS_EXT]; `required` flags must be found and read.
 * `nga` = defined with the Assistant voice typing flags instead of the smart dictation flags.
 */
private data class FlagOverride(
    val name: String,
    val replacement: String,
    val required: Boolean,
    val nga: Boolean = false,
)

private val FLAG_OVERRIDES = listOf(
    // Loads Gboard's Assistant voice typing module (rainbow mic, handleLaunchVoice). Without it the
    // mic always opens the legacy voice input.
    FlagOverride("enable_nga", "enabled", true, nga = true),
    // Mic button routes voice input through the smart dictation stack.
    FlagOverride("handle_fallback_inside_sd_stack", "enabled", true),
    // Advanced features: automatic punctuation/capitalization/emoji, voice commands. These run
    // formatter processors that load model files (punctuation_model.tflite, spoken_emojis_patterns
    // .binarypb, ...) which Gboard normally downloads via MDD. On a fused APK that download doesn't
    // run, so the files are fetched by the "Download advanced voice typing files" button in the patch
    // settings and served to the formatters through the Lsdx.c (getFileUri) hook below.
    FlagOverride("enable_advanced_features_in_consolidated_sd_stack", "enabled", true),
    // Install/load the dictation native split.
    FlagOverride("enable_dictation_feature_split_install", "enabled", false),
    // (enable_soda_longform_experiment is left at Gboard's default: with it forced on, Speech Services
    // reported its downloaded en-US offline model as "language not supported".)
    // Keep it on-device: speech provider = Speech Services by Google, never Google's server (S3).
    FlagOverride("asr_provider", "onDeviceAsrProvider", true),
    FlagOverride("enable_s3_as_fallback_in_smart_dictation", "disabled", true),
)

private const val SPEECH_SERVICES_PACKAGE = "com.google.android.tts"
private const val SETUP_ACTIVITY = "app.template.extension.gboard.VoiceSetupActivity"
private const val DIAG_EXT = "Lapp/template/extension/gboard/VoiceDiagnostics;"
private object SpeechServicesAnchor

@Suppress("unused")
val advancedVoiceTypingPatch = patch(
    name = "Advanced voice typing",
    description = "Turns on Gboard's advanced voice typing (automatic punctuation, voice commands) using " +
        "on-device speech recognition from Speech Services by Google. Google's server recognizer is " +
        "never used as provider or fallback. Needs Speech Services by Google with its offline language " +
        "installed. Approach based on Gboard-patches (jasonwu1994).",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    extendWith("extensions/extension.mpe")

    execute {
        // --- Resolve each flag's static field from the flag definitions.
        val sdClinit = SmartDictationFlagsFingerprint.originalMethod.implementation!!.instructions.toList()
        val ngaClinit = NgaFlagsFingerprint.originalMethod.implementation!!.instructions.toList()
        fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
        val fields = FLAG_OVERRIDES.mapNotNull { flag ->
            val clinit = if (flag.nga) ngaClinit else sdClinit
            val nameIndex = clinit.indexOfFirst { it.string() == flag.name }
            if (nameIndex < 0) {
                if (flag.required) throw PatchException("Flag ${flag.name} not found")
                return@mapNotNull null
            }
            // const-string name, default, invoke factory, move-result, sput-object field
            val field = (nameIndex + 1 until minOf(nameIndex + 8, clinit.size)).firstNotNullOfOrNull { i ->
                ((clinit[i] as? ReferenceInstruction)?.reference as? FieldReference)
                    ?.takeIf { clinit[i].opcode == Opcode.SPUT_OBJECT }
            } ?: throw PatchException("Field for flag ${flag.name} not found")
            flag to field
        }

        fun FieldReference.matches(other: FieldReference) =
            definingClass == other.definingClass && name == other.name && type == other.type

        // --- Replace every read of those flags' values.
        val hooks = mutableMapOf<String, Int>()
        val readers = mutableListOf<ClassDef>()
        classDefForEach { cd ->
            if (cd.methods.any { m ->
                    m.implementation?.instructions?.any { i ->
                        i.opcode == Opcode.SGET_OBJECT && fields.any { (_, f) ->
                            ((i as ReferenceInstruction).reference as FieldReference).matches(f)
                        }
                    } == true
                }
            ) readers += cd
        }
        readers.forEach { cd ->
            mutableClassDefBy(cd).methods.forEach { m ->
                val insns = m.implementation?.instructions?.toList() ?: return@forEach
                // index to insert after -> smali to insert
                val sites = mutableListOf<Pair<Int, String>>()
                insns.forEachIndexed { s, insn ->
                    if (insn.opcode != Opcode.SGET_OBJECT) return@forEachIndexed
                    val ref = (insn as ReferenceInstruction).reference as FieldReference
                    val (flag, field) = fields.firstOrNull { (_, f) -> ref.matches(f) } ?: return@forEachIndexed
                    val flagReg = (insn as OneRegisterInstruction).registerA
                    // flag.g() returns the current value: invoke-interface {vFlag}, ...->g()Ljava/lang/Object;
                    val call = (s + 1 until minOf(s + 7, insns.size)).firstOrNull { j ->
                        val c = insns[j]
                        (c.opcode == Opcode.INVOKE_INTERFACE || c.opcode == Opcode.INVOKE_VIRTUAL) &&
                            ((c as ReferenceInstruction).reference as MethodReference).let {
                                it.name == "g" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/Object;"
                            } && (c as FiveRegisterInstruction).registerC == flagReg
                    }
                    if (call != null && call + 1 < insns.size && insns[call + 1].opcode == Opcode.MOVE_RESULT_OBJECT) {
                        val reg = (insns[call + 1] as OneRegisterInstruction).registerA
                        sites += (call + 1) to """
                            invoke-static {}, $FLAGS_EXT->${flag.replacement}()Ljava/lang/Object;
                            move-result-object v$reg
                        """
                        hooks[flag.name] = (hooks[flag.name] ?: 0) + 1
                    } else if (call == null && flag.replacement == "enabled") {
                        // The flag object is handed on and read later: hand on an always-on proxy instead.
                        sites += s to """
                            invoke-static/range {v$flagReg .. v$flagReg}, $FLAGS_EXT->forceEnabled(Ljava/lang/Object;)Ljava/lang/Object;
                            move-result-object v$flagReg
                            check-cast v$flagReg, ${field.type}
                        """
                        hooks[flag.name] = (hooks[flag.name] ?: 0) + 1
                    }
                }
                sites.sortedByDescending { it.first }.forEach { (after, smali) ->
                    m.addInstructions(after + 1, smali)
                }
            }
        }
        FLAG_OVERRIDES.filter { it.required }.forEach { flag ->
            if ((hooks[flag.name] ?: 0) == 0) throw PatchException("No reads of flag ${flag.name} found")
        }

        // --- Diagnostics: keep Gboard's own voice typing log lines for the Offline voice model screen.
        mutableClassDefBy(DIAG_EXT).methods.first { it.name == "patched" }.apply {
            addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        }
        // Gboard's logger (Flogger): every t(String)/u(String, int)/... call ends in one private
        // method (String format, Object[] args) of the same class. Hook that method.
        var logHooks = 0
        val logClasses = mutableListOf<Pair<ClassDef, String>>() // class, central method name
        classDefForEach { cd ->
            val t = cd.methods.firstOrNull { m ->
                m.name == "t" && m.returnType == "V" && m.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;")
            } ?: return@classDefForEach
            val central = t.implementation?.instructions?.firstNotNullOfOrNull { i ->
                ((i as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                    i.opcode == Opcode.INVOKE_DIRECT && it.definingClass == cd.type && it.returnType == "V" &&
                        it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "[Ljava/lang/Object;")
                }
            } ?: return@classDefForEach
            logClasses += cd to central.name
        }
        logClasses.forEach { (cd, name) ->
            mutableClassDefBy(cd).methods.filter { m ->
                m.name == name && m.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "[Ljava/lang/Object;")
            }.forEach { m ->
                m.addInstructions(0, "invoke-static/range {p1 .. p2}, $DIAG_EXT->log(Ljava/lang/String;[Ljava/lang/Object;)V")
                logHooks++
            }
        }
        if (logHooks == 0) throw PatchException("Gboard logger not found")

        // ================= Never use Google's server (S3) or the system voice IME =================
        // Methods by the log strings they contain.
        fun methodsWithString(vararg strings: String): List<Pair<ClassDef, com.android.tools.smali.dexlib2.iface.Method>> {
            val found = mutableListOf<Pair<ClassDef, com.android.tools.smali.dexlib2.iface.Method>>()
            classDefForEach { cd ->
                cd.methods.forEach { m ->
                    val ss = m.implementation?.instructions?.mapNotNull { it.string() }?.toSet() ?: return@forEach
                    if (strings.all { it in ss }) found += cd to m
                }
            }
            return found
        }
        fun mutable(cd: ClassDef, m: com.android.tools.smali.dexlib2.iface.Method) =
            mutableClassDefBy(cd).methods.first {
                it.name == m.name && it.parameterTypes.map(CharSequence::toString) ==
                    m.parameterTypes.map(CharSequence::toString) && it.returnType == m.returnType
            }

        // 1) Fallback provider list: drop the "S3 is available" and "System Voice IME is available" adds.
        val listBuilders = methodsWithString("S3 is available. [SD]", "System Voice IME is available. [SD]")
        if (listBuilders.size != 1) throw PatchException("Fallback provider list builder not found")
        var s3Field: FieldReference? = null
        mutable(listBuilders[0].first, listBuilders[0].second).apply {
            val insns = implementation!!.instructions.toList()
            listOf("S3 is available. [SD]", "System Voice IME is available. [SD]").map { log ->
                val logIndex = insns.indexOfFirst { it.string() == log }
                val add = (logIndex until minOf(logIndex + 8, insns.size)).firstOrNull { j ->
                    insns[j].opcode == Opcode.INVOKE_INTERFACE &&
                        ((insns[j] as ReferenceInstruction).reference as MethodReference).let {
                            it.definingClass == "Ljava/util/List;" && it.name == "add"
                        }
                } ?: throw PatchException("Provider add after '$log' not found")
                if (insns[add + 1].opcode == Opcode.MOVE_RESULT) throw PatchException("Unexpected provider add shape")
                if (log.startsWith("S3")) {
                    s3Field = (logIndex until add).firstNotNullOfOrNull { j ->
                        ((insns[j] as? ReferenceInstruction)?.reference as? FieldReference)
                            ?.takeIf { insns[j].opcode == Opcode.SGET_OBJECT }
                    }
                }
                add
            }.sortedDescending().forEach { add -> replaceInstruction(add, "nop") }
        }
        val s3 = s3Field ?: throw PatchException("S3 provider constant not found")

        // 2) Provider selection: when Speech Services can't serve the language, choose no provider
        //    (Gboard then uses its regular offline voice input) instead of S3.
        val selectors = methodsWithString("SBG path failed. Reason: %s. Trying S3 path. [SD]")
        if (selectors.isEmpty()) throw PatchException("ASR provider selector not found")
        selectors.forEach { (cd, m) ->
            mutable(cd, m).apply {
                val insns = implementation!!.instructions.toList()
                val logIndex = insns.indexOfFirst { it.string() == "SBG path failed. Reason: %s. Trying S3 path. [SD]" }
                val s3Read = (logIndex until insns.size).firstOrNull { j ->
                    insns[j].opcode == Opcode.SGET_OBJECT &&
                        ((insns[j] as ReferenceInstruction).reference as FieldReference).let {
                            it.definingClass == s3.definingClass && it.name == s3.name
                        }
                } ?: throw PatchException("S3 selection not found")
                // The other branch reads the "none" provider constant of the same enum.
                val none = (s3Read + 1 until minOf(s3Read + 6, insns.size)).firstNotNullOfOrNull { j ->
                    ((insns[j] as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
                        insns[j].opcode == Opcode.SGET_OBJECT && it.definingClass == s3.definingClass && it.name != s3.name
                    }
                } ?: throw PatchException("'No provider' constant not found")
                val reg = (insns[s3Read] as OneRegisterInstruction).registerA
                replaceInstruction(s3Read, "sget-object v$reg, ${none.definingClass}->${none.name}:${none.type}")
            }
        }

        // 3) Last line of defence: the S3 (server) recognizer client never connects or sends audio.
        // The S3 client class is the one whose toString-like method builds "S3AsrClient, id = ".
        val s3ClientTypes = methodsWithString("S3AsrClient, id = ").map { it.first.type }.toSet()
        if (s3ClientTypes.size != 1) throw PatchException("S3 client class not found (${s3ClientTypes.size})")
        var s3Guards = 0
        listOf("#start [SD]", "#processAudio bytes=%d [SD]").forEach { log ->
            val targets = methodsWithString(log).filter { (cd, m) -> cd.type in s3ClientTypes && m.returnType == "V" }
            if (targets.isEmpty()) throw PatchException("S3 client method for '$log' not found")
            targets.forEach { (cd, m) ->
                mutable(cd, m).addInstructions(0, "return-void")
                s3Guards++
            }
        }

        // 4) Speech Services offers English offline as en-US: use it for every English variant
        //    (otherwise en-CA, en-GB, ... are "not supported" and Gboard looked for another provider).
        var localeHooks = 0
        classDefForEach { cd ->
            val isSbgConfig = cd.methods.any { m ->
                m.name == "toString" && m.implementation?.instructions?.any {
                    it.string()?.startsWith("SbgAsrConfig: {initialLocale: ") == true
                } == true
            }
            if (!isSbgConfig) return@classDefForEach
            mutableClassDefBy(cd).methods.filter { it.name == "<init>" }.forEach { ctor ->
                val params = ctor.parameterTypes.map(CharSequence::toString)
                val idx = params.indexOf("Ljava/util/Locale;")
                if (idx < 0) return@forEach
                // p0 = this; parameters before the Locale are all single-width objects here.
                if (params.take(idx).any { it == "J" || it == "D" }) throw PatchException("Unexpected SbG config ctor")
                val p = "p${idx + 1}"
                ctor.addInstructions(
                    0,
                    """
                        invoke-static {$p}, $FLAGS_EXT->sbgLocale(Ljava/util/Locale;)Ljava/util/Locale;
                        move-result-object $p
                    """,
                )
                localeHooks++
            }
        }
        if (localeHooks == 0) throw PatchException("SbG config constructor not found")

        // 5) Gboard asks Speech Services for an offline model that has SPOKEN_PUNCTUATION +
        //    OFFENSIVE_WORD_MASKING (the Pixel-pack features); the installed pack lacks them, so Speech
        //    Services answers "language not supported" (eligibility 50). Instead of dropping all
        //    features (which left punctuation to Gboard's text-only formatter), request the recognizer's
        //    own audio-based models: UNSPOKEN_PUNCTUATION (automatic punctuation from prosody/pauses) and
        //    CAPITALIZATION. If the pack supports them, punctuation/caps come from the native model.
        var featureEnum: ClassDef? = null
        classDefForEach { cd ->
            if (featureEnum != null) return@classDefForEach
            val clinit = cd.methods.firstOrNull { it.name == "<clinit>" } ?: return@classDefForEach
            val ss = clinit.implementation?.instructions?.mapNotNull { it.string() }?.toSet() ?: return@classDefForEach
            if ("FEATURE_SPOKEN_PUNCTUATION" in ss && "FEATURE_OFFENSIVE_WORD_MASKING" in ss && "FEATURE_LANG_ID" in ss) {
                featureEnum = cd
            }
        }
        val enumDef = featureEnum ?: throw PatchException("Speech feature enum not found")
        fun enumField(constName: String): FieldReference {
            val insns = enumDef.methods.first { it.name == "<clinit>" }.implementation!!.instructions.toList()
            val s = insns.indexOfFirst { it.string() == constName }
            val init = (s until insns.size).first { j ->
                insns[j].opcode == Opcode.INVOKE_DIRECT &&
                    ((insns[j] as ReferenceInstruction).reference as MethodReference).name == "<init>"
            }
            val objReg = (insns[init] as FiveRegisterInstruction).registerC
            val put = (init until insns.size).first { j ->
                insns[j].opcode == Opcode.SPUT_OBJECT && (insns[j] as OneRegisterInstruction).registerA == objReg
            }
            return (insns[put] as ReferenceInstruction).reference as FieldReference
        }
        val pixelOnly = listOf(enumField("FEATURE_SPOKEN_PUNCTUATION"), enumField("FEATURE_OFFENSIVE_WORD_MASKING"))
        val nativePunct = enumField("FEATURE_UNSPOKEN_PUNCTUATION")
        val nativeCaps = enumField("FEATURE_CAPITALIZATION")
        // 5a) Set the shared feature set to the recognizer's audio-based models. This set is used by
        //     the recognition request (so the model punctuates/capitalizes) AND by the availability
        //     check (which would reject them -> eligibility 50); 5b fixes the availability side.
        var swsClass: String? = null
        var featureSets = 0
        classDefForEach { cd ->
            val clinit = cd.methods.firstOrNull { it.name == "<clinit>" } ?: return@classDefForEach
            val insns = clinit.implementation?.instructions?.toList() ?: return@classDefForEach
            if (insns.size != 6) return@classDefForEach
            val sgetIdx = insns.withIndex().filter { it.value.opcode == Opcode.SGET_OBJECT }
            val reads = sgetIdx.map { (it.value as ReferenceInstruction).reference as FieldReference }
            if (reads.size != 2 || !pixelOnly.all { p -> reads.any { it.matches(p) } }) return@classDefForEach
            val m = mutable(cd, clinit)
            listOf(nativePunct, nativeCaps).forEachIndexed { i, field ->
                val idx = sgetIdx[i].index
                val reg = (insns[idx] as OneRegisterInstruction).registerA
                m.replaceInstruction(idx, "sget-object v$reg, ${field.definingClass}->${field.name}:${field.type}")
            }
            swsClass = (insns[insns.indexOfFirst { it.opcode == Opcode.SPUT_OBJECT }] as ReferenceInstruction)
                .let { (it.reference as FieldReference).definingClass }
            featureSets++
        }
        if (featureSets != 1) throw PatchException("Required speech feature set not found ($featureSets)")
        val swsField = swsClass!!

        // 5b) The SbG model-availability request (the builder that reads the feature set AND references
        //     the Luyg availability-request proto) must NOT require these features, or Speech Services
        //     answers "language not supported" (eligibility 50). Overwrite the loaded set with an empty
        //     ImmutableSet so the availability request asks for no required features. The recognition
        //     builders (which reference Luyd/Luzd, not Luyg) keep the real feature set.
        var availPatched = 0
        classDefForEach { cd ->
            cd.methods.forEach { m ->
                val insns = m.implementation?.instructions?.toList() ?: return@forEach
                val swsRead = insns.indexOfFirst {
                    it.opcode == Opcode.SGET_OBJECT &&
                        ((it as ReferenceInstruction).reference as? FieldReference)
                            ?.let { f -> f.definingClass == swsField && f.name == "a" } == true
                }
                if (swsRead < 0) return@forEach
                val refsAvailReq = insns.any {
                    ((it as? ReferenceInstruction)?.reference as? FieldReference)?.definingClass == "Luyg;"
                }
                if (!refsAvailReq) return@forEach
                val reg = (insns[swsRead] as OneRegisterInstruction).registerA
                val setType = ((insns[swsRead] as ReferenceInstruction).reference as FieldReference).type
                // ImmutableSet.copyOf(Collection) -- the set type's only STATIC (Collection) -> set factory.
                val copyOf = classDefByOrNull(setType)?.methods?.filter { cm ->
                    AccessFlags.STATIC.isSet(cm.accessFlags) && cm.returnType == setType &&
                        cm.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/Collection;")
                }?.singleOrNull() ?: throw PatchException("Set factory not found for $setType")
                mutable(cd, m).addInstructions(
                    swsRead + 1,
                    """
                        invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                        move-result-object v$reg
                        invoke-static {v$reg}, $setType->${copyOf.name}(Ljava/util/Collection;)$setType
                        move-result-object v$reg
                    """,
                )
                availPatched++
            }
        }
        if (availPatched != 1) throw PatchException("SbG availability request builder not found ($availPatched)")

        // 6) Native dictation split. The eligibility check calls NativeLibHelper to load Gboard's own
        //    libdictation_jni.so (the in-process SODA recognizer). That library ships only in the
        //    dictation feature split, which a Morphe-fused base APK doesn't carry, so the check fails
        //    with NON_ELIGIBLE_NATIVE_SPLIT_UNAVAILABLE (code 51). With the speech provider pinned to
        //    Speech Services (SBG), recognition runs in that app and Gboard never calls its own SODA
        //    library, so the readiness query can safely report it as loaded.
        val nativeGateLog = "Dictation is not enabled because native split is not loaded. [SD]"
        val nativeGate = methodsWithString(nativeGateLog)
        if (nativeGate.size != 1) throw PatchException("Native split gate not found (${nativeGate.size})")
        val ngInsns = nativeGate[0].second.implementation!!.instructions.toList()
        val ngStr = ngInsns.indexOfFirst { it.string() == nativeGateLog }
        // The readiness query is the last no-arg static ()Z invoked before the "not loaded" log.
        val nativeCheck = (ngStr - 1 downTo 0).firstNotNullOfOrNull { i ->
            val ins = ngInsns[i]
            if (ins.opcode == Opcode.INVOKE_STATIC) {
                val r = (ins as ReferenceInstruction).reference as MethodReference
                if (r.returnType == "Z" && r.parameterTypes.isEmpty()) r else null
            } else null
        } ?: throw PatchException("Native split readiness check not found")
        val nativeCheckClass = classDefByOrNull(nativeCheck.definingClass)
            ?: throw PatchException("Native split readiness class missing")
        val nativeCheckMethod = nativeCheckClass.methods.firstOrNull {
            it.name == nativeCheck.name && it.returnType == "Z" && it.parameterTypes.isEmpty() &&
                AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: throw PatchException("Native split readiness method missing")
        // Gboard's own loader is skipped by this, so load libdictation_jni (formatter + emoji NLU
        // natives, also used with SBG) ourselves.
        mutable(nativeCheckClass, nativeCheckMethod).addInstructions(
            0,
            """
                invoke-static {}, Lapp/template/extension/gboard/DictationJni;->ensure()Z
                const/4 v0, 0x1
                return v0
            """,
        )

        // ================= Formatter files bundled in the APK (no download) =================
        val dataExt = "Lapp/template/extension/gboard/SmartDictationData;"
        // a) Availability check: a file missing from Gboard's download manager counts as available
        //    when a manually downloaded copy exists (then the check moves on to the next file).
        val availability = methodsWithString("File is not available, fileId: %s [SD]")
        if (availability.size != 1) throw PatchException("Data file availability check not found")
        mutable(availability[0].first, availability[0].second).apply {
            val insns = implementation!!.instructions.toList()
            val logStr = insns.indexOfFirst { it.string() == "File is not available, fileId: %s [SD]" }
            // Loop over the required files: head = first Iterator.hasNext() after the list is fetched.
            val listCall = insns.indexOfFirst { i ->
                i.opcode == Opcode.INVOKE_STATIC && ((i as ReferenceInstruction).reference as MethodReference).let {
                    it.returnType == "Ljava/util/List;" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/Locale;")
                }
            }
            if (listCall < 0 || listCall > logStr) throw PatchException("Required data file list not found")
            val loopHead = (listCall until logStr).firstOrNull { j ->
                insns[j].opcode == Opcode.INVOKE_INTERFACE &&
                    ((insns[j] as ReferenceInstruction).reference as MethodReference).name == "hasNext"
            } ?: throw PatchException("Data file loop not found")
            // Loop body start: next() -> move-result -> check-cast -> iget-object fileId (String).
            val fileIdRead = (loopHead until logStr).firstOrNull { j ->
                insns[j].opcode == Opcode.IGET_OBJECT &&
                    ((insns[j] as ReferenceInstruction).reference as FieldReference).type == "Ljava/lang/String;"
            } ?: throw PatchException("Data file id read not found")
            val fileIdReg = (insns[fileIdRead] as TwoRegisterInstruction).registerA
            // Scratch register: the one the very next call's move-result overwrites (so it's dead here).
            val nextMoveResult = (fileIdRead + 1 until minOf(fileIdRead + 4, logStr)).firstOrNull { j ->
                insns[j].opcode == Opcode.MOVE_RESULT
            } ?: throw PatchException("Unexpected data file loop shape")
            val scratch = (insns[nextMoveResult] as OneRegisterInstruction).registerA
            if (scratch == fileIdReg || (fileIdRead + 1 until nextMoveResult).any { j ->
                    // the scratch register must not be read before that move-result
                    (insns[j] as? FiveRegisterInstruction)?.let { f ->
                        listOf(f.registerC, f.registerD, f.registerE, f.registerF, f.registerG).take(f.registerCount).contains(scratch)
                    } == true
                }
            ) throw PatchException("No safe scratch register in data file loop")
            // Every register is exactly as on a normal loop iteration here, so jumping to the
            // loop head ("next file") is safe.
            addInstructionsWithLabels(
                fileIdRead + 1,
                """
                    invoke-static/range {v$fileIdReg .. v$fileIdReg}, $dataExt->manualFileAvailable(Ljava/lang/String;)Z
                    move-result v$scratch
                    if-nez v$scratch, :mpnextfile
                """,
                app.morphe.patcher.util.smali.ExternalLabel("mpnextfile", getInstruction(loopHead)),
            )
        }

        // b) File lookup: return the manually downloaded copy when there is one.
        val lookups = methodsWithString("Model doesn't exist for ", "getFileUri(...)")
        if (lookups.isEmpty()) throw PatchException("Data file lookup not found")
        lookups.forEach { (cd, m) ->
            val params = m.parameterTypes.map(CharSequence::toString)
            if (params.size != 3 || params[0] != "Ljava/util/Locale;" || params[1] != "Ljava/lang/String;" ||
                AccessFlags.STATIC.isSet(m.accessFlags)
            ) throw PatchException("Unexpected data file lookup signature")
            mutable(cd, m).apply {
                if ((implementation!!.registerCount - 4) < 1) throw PatchException("No free register in data file lookup")
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static/range {p1 .. p2}, $dataExt->manualFileUri(Ljava/util/Locale;Ljava/lang/String;)Landroid/net/Uri;
                        move-result-object v0
                        if-eqz v0, :mporiginal
                        return-object v0
                    """,
                    app.morphe.patcher.util.smali.ExternalLabel("mporiginal", getInstruction(0)),
                )
            }
        }

        // c) Ship the files as assets (assets/mp_sd/en/...) when the build has them (they're Google's,
        //    so a public build may leave them out); the extension copies them to files/ on first use.
        //    Without them, the setup screen downloads them from Google once.
        val bundled = listOf(
            "punctuation_model.tflite", "capitalization_model.tflite", "capitalization_vocab.txt",
            "spoken_punctuation_model.tflite", "spoken_punctuation_config.binarypb",
            "spoken_emojis_patterns.binarypb", "corrections_query_alternatives.binarypb",
        ).count { name ->
            val input = SpeechServicesAnchor::class.java.classLoader.getResourceAsStream("gboard/sd/en/$name")
                ?: return@count false
            val out = resourceFile("assets/mp_sd/en/$name")
            out.parentFile.mkdirs()
            input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            true
        }
        if (bundled != 0 && bundled != 7) throw PatchException("Only $bundled of 7 formatter files bundled")

        // --- Keep the final result when the mic is tapped to stop. Gboard stops the dictation (and
        //     tears down the oration/ASR session) immediately, so Speech Services' final result for the
        //     current utterance arrives afterwards and is ignored; the last partial is what stays on
        //     screen. Hold a keyboard stop until the final text was received (or a timeout).
        val finalExt = "Lapp/template/extension/gboard/FinalWait;"
        // DictationLogger.onTextReceived(Duration, boolean isFinal, String text, ...): every recognized
        // text passes through here, partial or final.
        val finalLog = "Final text received: same as last partial: %b [SD]"
        val textSites = methodsWithString(finalLog)
        if (textSites.size != 1) throw PatchException("onTextReceived not found (${textSites.size})")
        textSites.forEach { (cd, m) ->
            val pt = m.parameterTypes.map(CharSequence::toString)
            if (pt.size != 4 || pt[1] != "Z" || pt[2] != "Ljava/lang/String;") throw PatchException("onTextReceived signature changed: $pt")
            mutable(cd, m).addInstructions(0, "invoke-static {p2, p3}, $finalExt->onText(ZLjava/lang/String;)V")
        }
        val stopLog = "Stopping %s with status %s [SD]"
        val stopSites = methodsWithString(stopLog)
        if (stopSites.size != 1) throw PatchException("Dictation.stop not found (${stopSites.size})")
        val (stopCd, stopM) = stopSites[0]
        val stopInsns = stopM.implementation!!.instructions.toList()
        // Shape: monitor-enter; ...compareAndSet(stopping)...; if-nez -> real stop; else
        // "already stopping" block that returns the existing stop future. Reuse that block.
        val firstIf = stopInsns.indexOfFirst { it.opcode == Opcode.IF_NEZ }
        if (stopInsns[0].opcode != Opcode.MONITOR_ENTER || firstIf < 0 ||
            stopInsns[firstIf + 1].opcode != Opcode.IGET_OBJECT || stopM.parameterTypes.size != 1 ||
            AccessFlags.STATIC.isSet(stopM.accessFlags)
        ) throw PatchException("Dictation.stop has an unexpected shape")
        mutable(stopCd, stopM).apply {
            val alreadyStopping = getInstruction(firstIf + 1)
            addInstructionsWithLabels(
                1,
                """
                    const-string v0, "${stopM.name}"
                    invoke-static {p0, p1, v0}, $finalExt->shouldDefer(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Z
                    move-result v0
                    if-nez v0, :mp_hold_stop
                """,
                app.morphe.patcher.util.smali.ExternalLabel("mp_hold_stop", alreadyStopping),
            )
        }

        // --- "Advanced voice typing" screen (setup, English download with progress, test field),
        //     opened from Gboard's Voice typing settings.
        resourceXml("AndroidManifest.xml").use { manifest ->
            manifest.elements("application").first().appendChild("activity") {
                setAttribute("android:name", SETUP_ACTIVITY)
                setAttribute("android:exported", "false")
                setAttribute("android:label", "Advanced voice typing")
                setAttribute("android:theme", "@android:style/Theme.DeviceDefault")
                setAttribute("android:configChanges", "orientation|screenSize|screenLayout|smallestScreenSize|uiMode")
            }
        }
        val packageName = packageMetadata.packageName
        var added = 0
        listOf("res/xml/setting_voice.xml", "res/xml/setting_traditional_voice.xml").forEach { path ->
            resourceXml(path).use { xml ->
                // Right under "Use voice typing" (and greyed out with it), like Gboard's own entries.
                val screen = xml.elements("PreferenceScreen").first()
                val useVoice = screen.children.indexOfFirst { it.tag.endsWith("ColoredBackgroundSwitchPreference") }
                val useVoiceKey = if (useVoice >= 0) screen.children[useVoice].attribute("android:key") else null
                screen.insertChild(useVoice + 1, "Preference") {
                    if (useVoiceKey != null) setAttribute("android:dependency", useVoiceKey)
                    setAttribute("android:key", "mp_avt_setup")
                    setAttribute("android:persistent", "false")
                    setAttribute("android:title", "Advanced voice typing")
                    setAttribute("android:summary", "Setup, English download and test")
                    appendChild("intent") {
                        setAttribute("android:targetPackage", packageName)
                        setAttribute("android:targetClass", SETUP_ACTIVITY)
                    }
                }
                added++
            }
        }
        if (added == 0) throw PatchException("Voice settings screens not found")

        // --- "Keep other audio playing": route Gboard's audio focus calls through AudioFocusGuard.
        hookGboardPrefs()
        var focusSites = 0
        classDefForEach { cd ->
            if (cd.type.startsWith("Lapp/template/extension/")) return@classDefForEach
            val hit = cd.methods.any { m -> m.implementation?.instructions?.any { focusCall(it) != null } == true }
            if (!hit) return@classDefForEach
            mutableClassDefBy(cd).methods.forEach { m ->
                val insns = m.implementation?.instructions?.toList() ?: return@forEach
                insns.withIndex().filter { focusCall(it.value) != null }.reversed().forEach { (i, insn) ->
                    val call = insn as FiveRegisterInstruction
                    val target = if (focusCall(insn) == "request") "request" else "abandon"
                    m.replaceInstruction(
                        i,
                        "invoke-static {v${call.registerC}, v${call.registerD}}, " +
                            "Lapp/template/extension/gboard/AudioFocusGuard;->$target(Landroid/media/AudioManager;Landroid/media/AudioFocusRequest;)I",
                    )
                    focusSites++
                }
            }
        }
        if (focusSites < 2) throw PatchException("Audio focus calls not found ($focusSites)")
        var focusPrefs = 0
        listOf("res/xml/setting_voice.xml", "res/xml/setting_traditional_voice.xml").forEach { path ->
            resourceXml(path).use { xml ->
                val screen = xml.elements("PreferenceScreen").first()
                val useVoiceKey = screen.children.firstOrNull { it.tag.endsWith("ColoredBackgroundSwitchPreference") }
                    ?.attribute("android:key")
                screen.appendChild("SwitchPreferenceCompat") {
                    if (useVoiceKey != null) setAttribute("android:dependency", useVoiceKey)
                    setAttribute("android:key", "mp_voice_no_audio_focus")
                    setAttribute("android:defaultValue", "false")
                    setAttribute("android:title", "Keep other audio playing")
                    setAttribute(
                        "android:summary",
                        "Voice typing won't pause or lower music and videos. The mic may pick them up.",
                    )
                }
                focusPrefs++
            }
        }
        if (focusPrefs == 0) throw PatchException("Voice settings screens not found")

        // --- Project link at the top of Settings > About.
        resourceXml("res/xml/setting_about.xml").use { xml ->
            xml.elements("PreferenceScreen").first().insertChild(0, "Preference") {
                setAttribute("android:key", "mp_about_project")
                setAttribute("android:persistent", "false")
                setAttribute("android:title", "Gboard Offline Voice")
                setAttribute("android:summary", "Patched build \u00b7 github.com/znbaboy/gboard-offline-voice")
                appendChild("intent") {
                    setAttribute("android:action", "android.intent.action.VIEW")
                    setAttribute("android:data", "https://github.com/znbaboy/gboard-offline-voice")
                }
            }
        }

        // --- Let Gboard see Speech Services by Google (Android 11+ package visibility).
        resourceXml("AndroidManifest.xml").use { manifest ->
            val root = manifest.elements("manifest").first()
            val queries = manifest.elements("queries").firstOrNull() ?: root.appendChild("queries") {}
            listOf(SPEECH_SERVICES_PACKAGE).forEach { pkg ->
                val present = manifest.elements("package").any { it.attribute("android:name") == pkg }
                if (!present) {
                    queries.appendChild("package") {
                        setAttribute("android:name", pkg)
                    }
                }
            }
        }
    }
}

/** Direct AudioManager.requestAudioFocus / abandonAudioFocusRequest(AudioFocusRequest) calls. */
private fun focusCall(insn: com.android.tools.smali.dexlib2.iface.instruction.Instruction): String? {
    if (insn.opcode != Opcode.INVOKE_VIRTUAL) return null
    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (ref.definingClass != "Landroid/media/AudioManager;" || ref.returnType != "I") return null
    if (ref.parameterTypes.map(CharSequence::toString) != listOf("Landroid/media/AudioFocusRequest;")) return null
    return when (ref.name) {
        "requestAudioFocus" -> "request"
        "abandonAudioFocusRequest" -> "abandon"
        else -> null
    }
}
