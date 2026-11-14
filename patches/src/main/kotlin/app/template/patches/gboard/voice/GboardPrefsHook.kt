package app.template.patches.gboard.voice

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import com.android.tools.smali.dexlib2.AccessFlags

private const val PREFS_EXT = "Lapp/template/extension/gboard/GboardPrefs;"

/**
 * Points GboardPrefs.raw() at the SharedPreferences behind Gboard's own preferences wrapper (the
 * store its settings screens save to via a PreferenceDataStore). The wrapper is found by shape:
 * a static get(Context) returning itself, an instance method returning SharedPreferences, and a
 * SharedPreferences change listener field.
 */
internal fun hookGboardPrefs() {
    val hits = mutableListOf<Pair<String, Pair<String, String>>>() // class -> (getInstance, getPrefs)
    classDefForEach { cd ->
        if (cd.fields.none { it.type == "Landroid/content/SharedPreferences\$OnSharedPreferenceChangeListener;" }) return@classDefForEach
        val get = cd.methods.firstOrNull { m ->
            AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == cd.type &&
                m.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;")
        } ?: return@classDefForEach
        val prefs = cd.methods.firstOrNull { m ->
            !AccessFlags.STATIC.isSet(m.accessFlags) && m.parameterTypes.isEmpty() &&
                m.returnType == "Landroid/content/SharedPreferences;"
        } ?: return@classDefForEach
        hits += cd.type to (get.name to prefs.name)
    }
    if (hits.size != 1) throw PatchException("Gboard preferences wrapper: found ${hits.size} candidates ${hits.map { it.first }}")
    val (type, names) = hits[0]
    val ext = classDefByOrNull(PREFS_EXT) ?: throw PatchException("GboardPrefs extension missing")
    val raw = mutableClassDefBy(ext).methods.first { it.name == "raw" }
    raw.addInstructions(
        0,
        """
            invoke-static {p0}, $type->${names.first}(Landroid/content/Context;)$type
            move-result-object v0
            invoke-virtual {v0}, $type->${names.second}()Landroid/content/SharedPreferences;
            move-result-object v0
            return-object v0
        """,
    )
}
