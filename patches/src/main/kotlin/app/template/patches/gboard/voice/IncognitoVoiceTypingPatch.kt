/*
 * Idea from adobo by jkennethcarino (GPLv3): https://github.com/jkennethcarino/adobo
 * Rewritten for Gboard 18.6.x, where adobo's fingerprint no longer matches.
 */
package app.template.patches.gboard.voice

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val INCOGNITO_DICTATION_LOG =
    "Dictation is not enabled in incognito mode input session. workprofile=%s [SD]"

// Speech dictation eligibility check: logs the incognito refusal right after reading the
// session's incognito flag.
internal object DictationEligibilityFingerprint : Fingerprint(
    strings = listOf(INCOGNITO_DICTATION_LOG),
)

private const val EXT = "Lapp/template/extension/gboard/IncognitoVoice;"
private const val CONTEXT = "Landroid/content/Context;"
private const val EDITOR_INFO = "Landroid/view/inputmethod/EditorInfo;"

private fun Method.params() = parameterTypes.map(CharSequence::toString)

private fun Method.paramRegister(index: Int): Int {
    // Static method: parameters occupy the last registers. All params here are single-width.
    return (implementation?.registerCount ?: 0) - parameterTypes.size + index
}

@Suppress("unused")
val incognitoVoiceTypingPatch = patch(
    name = "Allow voice typing in incognito",
    description = "Shows the mic key and allows voice typing in incognito mode, with an on/off switch " +
        "in Voice typing settings. Based on adobo (jkennethcarino), rewritten for Gboard 18.6.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    extendWith("extensions/extension.mpe")

    execute {
        hookGboardPrefs()
        // 1) Mic key availability: Gboard's static check
        //    isVoiceUnavailable(Context, EditorInfo, boolean incognito): Z
        //    returns true when the incognito argument is true. It is the only static
        //    (Context, EditorInfo, Z)Z method that calls two static (EditorInfo)Z field-type checks
        //    (password field, TYPE_NULL) and branches on its boolean parameter.
        val candidates = mutableListOf<Pair<ClassDef, Method>>()
        classDefForEach { cd ->
            cd.methods.forEach { m ->
                if (!AccessFlags.STATIC.isSet(m.accessFlags) || m.returnType != "Z" ||
                    m.params() != listOf(CONTEXT, EDITOR_INFO, "Z")
                ) return@forEach
                val insns = m.implementation?.instructions?.toList() ?: return@forEach
                if (insns.any { it.opcode == Opcode.CONST_STRING }) return@forEach
                val fieldTypeChecks = insns.count { i ->
                    i.opcode == Opcode.INVOKE_STATIC &&
                        ((i as ReferenceInstruction).reference as MethodReference).let {
                            it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(EDITOR_INFO)
                        }
                }
                val incognitoReg = m.paramRegister(2)
                val branchesOnParam = insns.any { i ->
                    (i.opcode == Opcode.IF_EQZ || i.opcode == Opcode.IF_NEZ) &&
                        (i as OneRegisterInstruction).registerA == incognitoReg
                }
                if (fieldTypeChecks >= 2 && branchesOnParam) candidates += cd to m
            }
        }
        if (candidates.size != 1) {
            throw PatchException("Mic availability check not found uniquely (${candidates.size} matches)")
        }
        val (checkClass, check) = candidates.single()
        mutableClassDefBy(checkClass).methods.first {
            it.name == check.name && it.params() == check.params() && it.returnType == check.returnType
        }.apply {
            val reg = paramRegister(2)
            if (reg > 15) throw PatchException("Unexpected mic availability check registers")
            // Treat the session as non-incognito for this check while the switch is on.
            addInstructions(0, "invoke-static {v$reg}, $EXT->filter(Z)Z\nmove-result v$reg")
        }

        // 2) Speech dictation path: the session's incognito flag (read right before the
        //    "Dictation is not enabled in incognito mode" log) is always stored as false.
        val eligibility = DictationEligibilityFingerprint.originalMethod.implementation!!.instructions.toList()
        val logIndex = eligibility.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == INCOGNITO_DICTATION_LOG
        }
        // The branch that jumps to the incognito log tests a boolean read from the session object.
        val incognitoField = eligibility.take(logIndex).mapNotNull { i ->
            ((i as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
                i.opcode == Opcode.IGET_BOOLEAN
            }
        }.let { reads ->
            // In the method, reads go: ..., incognito (if-nez -> incognito log), unsupported IME, morse.
            // Pick the first boolean read on the session class that is directly followed by if-nez.
            eligibility.indices.firstNotNullOfOrNull { idx ->
                if (idx >= logIndex - 1) return@firstNotNullOfOrNull null
                val i = eligibility[idx]
                val next = eligibility[idx + 1]
                if (i.opcode == Opcode.IGET_BOOLEAN && next.opcode == Opcode.IF_NEZ &&
                    (i as TwoRegisterInstruction).registerA == (next as OneRegisterInstruction).registerA
                ) ((i as ReferenceInstruction).reference as FieldReference).takeIf { it in reads } else null
            }
        } ?: throw PatchException("Dictation incognito flag not found")

        var writes = 0
        classDefForEach { cd ->
            if (cd.methods.none { m ->
                    m.implementation?.instructions?.any { i ->
                        i.opcode == Opcode.IPUT_BOOLEAN &&
                            ((i as ReferenceInstruction).reference as FieldReference).let {
                                it.definingClass == incognitoField.definingClass && it.name == incognitoField.name
                            }
                    } == true
                }
            ) return@classDefForEach
            mutableClassDefBy(cd).methods.forEach { m ->
                val insns = m.implementation?.instructions?.toList() ?: return@forEach
                insns.indices.filter { idx ->
                    insns[idx].opcode == Opcode.IPUT_BOOLEAN &&
                        ((insns[idx] as ReferenceInstruction).reference as FieldReference).let {
                            it.definingClass == incognitoField.definingClass && it.name == incognitoField.name
                        }
                }.sortedDescending().forEach { idx ->
                    val reg = (insns[idx] as TwoRegisterInstruction).registerA
                    if (reg > 15) throw PatchException("Unexpected incognito flag register")
                    m.addInstructions(idx, "invoke-static {v$reg}, $EXT->filter(Z)Z\nmove-result v$reg")
                    writes++
                }
            }
        }
        if (writes == 0) throw PatchException("Dictation incognito flag write not found")

        // 3) The switch, in Voice typing settings.
        var added = 0
        listOf("res/xml/setting_voice.xml", "res/xml/setting_traditional_voice.xml").forEach { path ->
            resourceXml(path).use { xml ->
                val screen = xml.elements("PreferenceScreen").first()
                val useVoiceKey = screen.children.firstOrNull { it.tag.endsWith("ColoredBackgroundSwitchPreference") }
                    ?.attribute("android:key")
                screen.appendChild("SwitchPreferenceCompat") {
                    if (useVoiceKey != null) setAttribute("android:dependency", useVoiceKey)
                    setAttribute("android:key", "mp_voice_incognito")
                    setAttribute("android:defaultValue", "true")
                    setAttribute("android:title", "Voice typing in incognito")
                    setAttribute("android:summary", "Allow the mic in incognito / private fields")
                }
                added++
            }
        }
        if (added == 0) throw PatchException("Voice settings screens not found")
    }
}
