package app.template.patches.gboard.voice

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val NO_NET_EXT = "Lapp/template/extension/gboard/OnlineFeatures;"
private const val NET_GUARD = "Lapp/template/extension/gboard/NetGuard;"
private const val GIF_MODULE = "GifAccessPointProviderModule"

// Gboard modules that only work online. Found by the name each module logs with.
private val ONLINE_MODULES = listOf(
    "GifAccessPointProviderModule",        // GIF button
    "StickerAccessPointProviderModule",    // Sticker button
    "StickerExtension",                    // Sticker keyboard
    "CustomStickerKeyboardProviderModule", // Custom stickers
    "TranslateAccessPointProviderModule",  // Translate button
    "TranslateUiExtension",                // Translate UI
    "AutoTranslateModule",                 // Auto translate
    "EmojiKitchenBrowseExtension",         // Emoji Kitchen browser
    "JarvisAccessPointProviderModule",     // Writing tools / proofread button
    "JarvisExtension",                     // Writing tools
    "JarvisPromptExtension",               // Writing tools prompts
    "LlmGrpcModule",                       // Server LLM
)

// Settings entries for those features (matched against android:key).
private val ONLINE_SETTINGS = mapOf(
    "res/xml/settings.xml" to listOf(
        "settings_header_translate", "settings_header_sharing", "settings_header_rate_us",
        "settings_header_help_and_feedback",
    ),
    "res/xml/setting_expression.xml" to listOf(
        "pref_key_enable_conv2query", "enable_sticker_predictions_while_typing",
        "setting_emoji_kitchen_category_key_expression_settings",
    ),
    // Privacy: only the on-device options stay (Personalize for you, Delete learned words).
    "res/xml/setting_privacy.xml" to listOf(
        "enable_user_metrics", "user_enable_federated_training", "setting_privacy_voice_category_key",
    ),
)

@Suppress("unused")
val removeNetworkAccessPatch = patch(
    name = "Remove network access",
    description = "Experimental. Filters the connections Gboard makes and hides features that need the " +
        "internet (stickers, Emoji Kitchen, Translate, writing tools, share/rate/help, usage sharing). " +
        "Not a guaranteed block: for that, turn off Gboard's network access in Android settings. " +
        "Adds a GIF search switch.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    extendWith("extensions/extension.mpe")

    execute {
        // --- 1) Every connection Gboard makes goes through NetGuard. With the GIF search switch off it
        //        blocks everything; on, only Tenor GIF search/categories and GIF images.
        installNetGuard()
        hookGboardPrefs()

        // --- 2) Never load the online-only modules: give each one a condition that is never met.
        val moduleClasses = mutableMapOf<String, String>() // name -> class
        classDefForEach { cd ->
            cd.methods.forEach { m ->
                m.implementation?.instructions?.forEach { i ->
                    val s = ((i as? ReferenceInstruction)?.reference as? StringReference)?.string ?: return@forEach
                    val n = s.removeSuffix(".onCreate")
                    if (n in ONLINE_MODULES) moduleClasses[n] = cd.type
                }
            }
        }
        val missing = ONLINE_MODULES - moduleClasses.keys
        if (missing.isNotEmpty()) throw PatchException("Online modules not found: $missing")

        // ModuleManager$ModuleInfo.moduleDef -> module definition class; its constructor takes the builder.
        val moduleInfo = classDefByOrNull("Lcom/google/android/libraries/inputmethod/module/ModuleManager\$ModuleInfo;")
            ?: throw PatchException("ModuleManager\$ModuleInfo not found")
        val defType = moduleInfo.fields.first { it.name == "moduleDef" }.type
        val defClass = classDefByOrNull(defType) ?: throw PatchException("module definition class not found")
        val ctor = defClass.methods.first { it.name == "<init>" && it.parameterTypes.size == 1 }
        val builderType = ctor.parameterTypes[0].toString()
        val builder = classDefByOrNull(builderType) ?: throw PatchException("module builder not found")
        val classField = builder.fields.filter { it.type == "Ljava/lang/Class;" }.map { it.name }.sorted()
        if (classField.size != 2) throw PatchException("module builder layout changed")
        // Conditions object: the builder field whose class has e(String, BiPredicate).
        val condField = builder.fields.firstOrNull { f ->
            classDefByOrNull(f.type)?.methods?.any { m ->
                m.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "Ljava/util/function/BiPredicate;")
            } == true
        } ?: throw PatchException("module conditions field not found")
        val condType = condField.type
        val condAdd = classDefByOrNull(condType)!!.methods.first { m ->
            m.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "Ljava/util/function/BiPredicate;")
        }.name
        val impl = classField[1] // the implementation class (both are the same for these modules)
        if ((ctor.implementation?.registerCount ?: 0) - 2 < 3) throw PatchException("module definition: no free registers")

        val gifClass = moduleClasses.getValue(GIF_MODULE)
        val checks = (moduleClasses - GIF_MODULE).values.toSet().joinToString("\n") { t ->
            "const-class v1, $t\nif-eq v0, v1, :mp_block"
        }
        mutableClassDefBy(defClass).methods.first { it.name == "<init>" && it.parameterTypes.size == 1 }.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $NO_NET_EXT->forceOffOnce()V
                    iget-object v0, p1, $builderType->$impl:Ljava/lang/Class;
                    const-class v1, $gifClass
                    if-eq v0, v1, :mp_gif
                    $checks
                    goto :mp_keep
                    :mp_gif
                    sget-object v2, $NO_NET_EXT->GIF:Ljava/util/function/BiPredicate;
                    const-string v1, "mp_gif_search"
                    goto :mp_cond
                    :mp_block
                    sget-object v2, $NO_NET_EXT->NEVER:Ljava/util/function/BiPredicate;
                    const-string v1, "mp_online_feature"
                    :mp_cond
                    iget-object v0, p1, $builderType->${condField.name}:$condType
                    if-nez v0, :mp_have
                    new-instance v0, $condType
                    invoke-direct {v0}, $condType-><init>()V
                    iput-object v0, p1, $builderType->${condField.name}:$condType
                    :mp_have
                    invoke-virtual {v0, v1, v2}, $condType->$condAdd(Ljava/lang/String;Ljava/util/function/BiPredicate;)V
                """,
                app.morphe.patcher.util.smali.ExternalLabel("mp_keep", getInstruction(0)),
            )
        }

        // --- 3) The GIF search switch, at the top of Emojis, stickers & GIFs.
        resourceXml("res/xml/setting_expression.xml").use { xml ->
            xml.elements("PreferenceScreen").first().insertChild(0, "SwitchPreferenceCompat") {
                setAttribute("android:key", "mp_gif_search")
                setAttribute("android:defaultValue", "false")
                setAttribute("android:title", "GIF search")
                setAttribute(
                    "android:summary",
                    "Off: Gboard's connections are filtered (experimental, not guaranteed).\n" +
                        "On: GIF search is allowed.\n\n" +
                        "Each GIF search sends to Google (Tenor):\n" +
                        "\u2022 your search words\n" +
                        "\u2022 your language and country\n" +
                        "\u2022 Gboard's app key and ID\n" +
                        "\u2022 your IP address\n\n" +
                        "GIF categories and images also load from Tenor. For a guaranteed block, turn off Gboard's network access in Android settings.",
                )
            }
        }

        // --- 4) Settings entries for those features. Keys are string references (obfuscated names),
        //        so resolve them through values/strings.xml.
        val strings = mutableMapOf<String, String>()
        resourceXml("res/values/strings.xml").use { xml ->
            xml.elements("string").forEach { e ->
                val n = e.attribute("name") ?: return@forEach
                strings[n] = e.text
            }
        }
        fun keyOf(raw: String) = if (raw.startsWith("@string/")) strings[raw.removePrefix("@string/")] ?: raw else raw
        ONLINE_SETTINGS.forEach { (path, keys) ->
            resourceXml(path).use { xml ->
                val hits = xml.elements().filter { e ->
                    e.attribute("android:key")?.let { keyOf(it) in keys } == true
                }.toList()
                if (hits.size != keys.size) {
                    throw PatchException("Online settings in $path: found ${hits.size} of ${keys.size}")
                }
                hits.forEach { it.remove() }
            }
        }
    }
}

/**
 * Routes every outgoing connection in Gboard through NetGuard: URL strings given to Cronet /
 * HttpEngine (requests and bidirectional streams), URLs opened with openConnection/openStream, and
 * raw socket connects.
 */
private fun installNetGuard() {
    var sites = 0
    classDefForEach { cd ->
        if (cd.type.startsWith("Lapp/template/extension/")) return@classDefForEach
        val hit = cd.methods.any { m -> m.implementation?.instructions?.any { guardKind(it) != null } == true }
        if (!hit) return@classDefForEach
        mutableClassDefBy(cd).methods.forEach { m ->
            val insns = m.implementation?.instructions?.toList() ?: return@forEach
            val edits = insns.withIndex().mapNotNull { (i, insn) -> guardKind(insn)?.let { i to it } }
            edits.sortedByDescending { it.first }.forEach { (i, kind) ->
                val call = insns[i] as FiveRegisterInstruction
                val (reg, smali) = when (kind) {
                    "str" -> call.registerD to "url(Ljava/lang/String;)Ljava/lang/String;"
                    "urlRecv" -> call.registerC to "url(Ljava/net/URL;)Ljava/net/URL;"
                    "urlArg" -> call.registerD to "url(Ljava/net/URL;)Ljava/net/URL;"
                    "host" -> call.registerD to "host(Ljava/lang/String;)Ljava/lang/String;"
                    "addr" -> call.registerD to "addr(Ljava/net/InetAddress;)Ljava/net/InetAddress;"
                    else -> call.registerD to "address(Ljava/net/SocketAddress;)Ljava/net/SocketAddress;"
                }
                if (reg > 15) throw PatchException("NetGuard: register v$reg too high in ${cd.type}->${m.name}")
                m.addInstructions(i, "invoke-static {v$reg}, $NET_GUARD->$smali\nmove-result-object v$reg")
                sites++
            }
        }
    }
    if (sites < 10) throw PatchException("NetGuard: only $sites network call sites found")
}

private fun guardKind(insn: com.android.tools.smali.dexlib2.iface.instruction.Instruction): String? {
    val op = insn.opcode
    if (op != Opcode.INVOKE_VIRTUAL && op != Opcode.INVOKE_SUPER && op != Opcode.INVOKE_INTERFACE) return null
    val ref = (insn as ReferenceInstruction).reference as? MethodReference ?: return null
    val params = ref.parameterTypes.map(CharSequence::toString)
    return when {
        (ref.name == "newUrlRequestBuilder" || ref.name == "newBidirectionalStreamBuilder") &&
            params.firstOrNull() == "Ljava/lang/String;" -> "str"
        (ref.name == "openConnection" || ref.name == "openStream") && ref.definingClass == "Ljava/net/URL;" -> "urlRecv"
        ref.name == "openConnection" && params.firstOrNull() == "Ljava/net/URL;" -> "urlArg"
        ref.name == "connect" && params.firstOrNull() == "Ljava/net/SocketAddress;" &&
            (ref.definingClass == "Ljava/net/Socket;" || ref.definingClass == "Ljavax/net/ssl/SSLSocket;") -> "sock"
        ref.name == "createSocket" && params.firstOrNull() == "Ljava/lang/String;" -> "host"
        ref.name == "createSocket" && params.firstOrNull() == "Ljava/net/InetAddress;" -> "addr"
        else -> null
    }
}
