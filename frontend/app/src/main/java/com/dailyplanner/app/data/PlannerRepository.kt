package com.dailyplanner.app.data

import android.content.Context
import com.dailyplanner.app.data.model.Plan
import com.dailyplanner.app.data.model.PlanInput
import com.dailyplanner.app.data.model.Reminder
import com.dailyplanner.app.data.model.ReminderInput
import com.dailyplanner.app.data.model.Template
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Standalone data layer:
 *  - templates + images ship inside the APK (assets/bundle, copied from backend/ at build time)
 *  - plans and reminders are stored on the phone
 *  - if a backend URL is configured, plans/reminders are also copied there in the background
 */
class PlannerRepository(
    private val context: Context,
    private val api: PlannerApi?,
    private val json: Json,
) {
    private val cacheDir = File(context.filesDir, "cache").apply { mkdirs() }
    private val syncScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    /** Backend copy is best effort and never blocks the UI (phone may be offline / no backend). */
    private fun sync(block: suspend PlannerApi.() -> Unit) { val a = api ?: return; syncScope.launch { runCatching { a.block() } } }

    // ---------------------------------------------------------------- templates (bundled)

    @Serializable private data class Source(val id: String)

    private val bundled: List<Template> by lazy {
        val dir = "bundle/templates"
        val files = context.assets.list(dir).orEmpty().filter { it.endsWith(".json") && it != "figma-sources.json" }
        val order = runCatching {
            json.decodeFromString(ListSerializer(Source.serializer()), context.assets.open("$dir/figma-sources.json").bufferedReader().readText()).map { it.id }
        }.getOrDefault(emptyList())
        files.map { f -> json.decodeFromString(Template.serializer(), context.assets.open("$dir/$f").bufferedReader().readText()) }
            .sortedBy { t -> order.indexOf(t.id).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    suspend fun templates(): List<Template> = withContext(Dispatchers.IO) { bundled }

    suspend fun template(id: String): Template = templates().firstOrNull { it.id == id } ?: error("Template $id is not in this app version")

    // ---------------------------------------------------------------- plans (on the phone)

    private val plansFile = File(cacheDir, "plans_local.json")
    private val planSer = ListSerializer(Plan.serializer())
    private val planLock = Any()

    private fun localPlans(): List<Plan> = synchronized(planLock) {
        if (plansFile.exists()) runCatching { json.decodeFromString(planSer, plansFile.readText()) }.getOrDefault(emptyList()) else emptyList()
    }

    private fun writePlans(list: List<Plan>) = synchronized(planLock) {
        val tmp = File(cacheDir, "plans_local.json.tmp")
        tmp.writeText(json.encodeToString(planSer, list))
        tmp.renameTo(plansFile)
    }

    suspend fun plans(): List<Plan> = withContext(Dispatchers.IO) { localPlans().sortedByDescending { it.updatedAt } }

    suspend fun plan(id: String): Plan = withContext(Dispatchers.IO) { localPlans().firstOrNull { it.id == id } ?: error("Plan not found") }

    suspend fun createPlan(input: PlanInput): Plan = savePlan(java.util.UUID.randomUUID().toString(), input)

    suspend fun updatePlan(id: String, input: PlanInput): Plan = savePlan(id, input)

    private suspend fun savePlan(id: String, input: PlanInput): Plan = withContext(Dispatchers.IO) {
        val now = java.time.OffsetDateTime.now().toString()
        val existing = localPlans().firstOrNull { it.id == id }
        val p = Plan(id, input.templateId, input.title, input.values, input.checks, existing?.createdAt ?: now, now)
        writePlans(localPlans().filterNot { it.id == id } + p)
        sync { upsertPlan(id, input) }
        p
    }

    suspend fun deletePlan(id: String) = withContext(Dispatchers.IO) {
        writePlans(localPlans().filterNot { it.id == id })
        sync { deletePlan(id) }
    }

    // ---------------------------------------------------------------- reminders
    // Offline-first: the phone owns its reminders (so alarms work with no backend), and
    // copies them to the backend whenever it is reachable.

    private val remindersFile = File(cacheDir, "reminders_local.json")
    private val reminderSer = ListSerializer(Reminder.serializer())
    private val lock = Any()

    fun localReminders(): List<Reminder> = synchronized(lock) {
        if (remindersFile.exists()) runCatching { json.decodeFromString(reminderSer, remindersFile.readText()) }.getOrDefault(emptyList())
        else emptyList()
    }

    private fun writeLocal(list: List<Reminder>) = synchronized(lock) {
        remindersFile.writeText(json.encodeToString(reminderSer, list.sortedBy { it.remindAt }))
    }

    /** The phone's list, returned immediately. Backend sync (if configured) runs in the background. */
    suspend fun reminders(): List<Reminder> = withContext(Dispatchers.IO) {
        val local = localReminders()
        sync {
            val remote = reminders()
            val current = localReminders()
            val imported = remote.filter { r -> current.none { it.id == r.id } && !deleted().contains(r.id) }
            if (imported.isNotEmpty()) writeLocal(current + imported)
            current.filter { l -> remote.none { it.id == l.id } }.forEach { putReminder(it.id, it.toInput()) }
        }
        local
    }

    /** Old name kept for the boot receiver. */
    fun cachedReminders(): List<Reminder> = localReminders()

    suspend fun saveReminder(input: ReminderInput, id: String = java.util.UUID.randomUUID().toString()): Reminder =
        withContext(Dispatchers.IO) {
            val now = java.time.OffsetDateTime.now().toString()
            val existing = localReminders().firstOrNull { it.id == id }
            val r = Reminder(id, input.title, input.note, input.remindAt, input.enabled, input.planId, input.elementId, existing?.createdAt ?: now, now)
            writeLocal(localReminders().filterNot { it.id == id } + r)
            sync { putReminder(id, input) }
            r
        }

    private val deletedFile = File(cacheDir, "reminders_deleted.txt")
    private fun deleted(): Set<String> = if (deletedFile.exists()) deletedFile.readLines().toSet() else emptySet()

    suspend fun deleteReminder(id: String) = withContext(Dispatchers.IO) {
        writeLocal(localReminders().filterNot { it.id == id })
        deletedFile.appendText("$id\n")
        sync { deleteReminder(id) }
    }
}
