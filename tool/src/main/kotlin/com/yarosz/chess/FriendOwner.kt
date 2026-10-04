package com.yarosz.chess

import android.util.Log
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import com.yarosz.chess.correspondence.Correspondence
import com.yarosz.chess.correspondence.CorrespondenceGame
import com.yarosz.chess.correspondence.CorrespondenceStore
import com.yarosz.chess.correspondence.Delivery
import com.yarosz.chess.correspondence.LiveOwner
import com.yarosz.chess.correspondence.PendingSeat
import com.yarosz.chess.correspondence.Refusal
import com.yarosz.chess.correspondence.SyncReport
import com.yarosz.chess.relay.Append
import com.yarosz.chess.relay.EntryKind
import com.yarosz.chess.relay.LiveConnector
import com.yarosz.chess.relay.OkHttpLiveConnector
import com.yarosz.chess.relay.OkHttpTransport
import com.yarosz.chess.relay.RelayClient
import com.yarosz.chess.relay.RelayConfig
import com.yarosz.chess.rules.Move
import com.yarosz.chess.rules.Side
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A short line the Game's strip shows for [NOTICE_MS] in place of its status (W4: "Not yet", a Refusal's copy). */
data class Notice(val text: String, val id: Long)

/**
 * What the Play a friend screens show: every Correspondence Game and Seat being taken, and what the
 * screens have in hand that isn't saved: the Move chosen but not confirmed (F11), the Games with a
 * request in flight ("Sending"), and the notices.
 */
data class FriendState(
    val games: List<CorrespondenceGame> = emptyList(),
    val seats: List<PendingSeat> = emptyList(),
    /** Chosen, not sent: the strip shows its SAN with Send and Undo (F11). Never saved: a restart forgets it. */
    val chosen: Map<String, Move> = emptyMap(),
    val sending: Set<String> = emptySet(),
    val notices: Map<String, Notice> = emptyMap(),
    /** The Game whose live socket is open (G3): its board needs no one-minute poll (Y12). */
    val linked: String? = null,
    /** The Game that is Live: its socket open, and the Relay reporting both Seats here (G3). */
    val live: String? = null,
) {
    fun game(gameId: String): CorrespondenceGame? = games.firstOrNull { it.gameId == gameId }

    /** The Menu's "Your move: N" (W10): the user's Move and not Stopped. */
    val yourMove: Int get() = games.count { it.yourMove }

    /** The cap of five is reached (E7, F9, V13): New game and Enter code show lightened. */
    val full: Boolean get() = games.count { it.takesASlot } + seats.size >= Correspondence.MAX_GAMES
}

/** The LightWork schedule of the background sync (W7), a seam so the owner is tested on the JVM. */
interface SyncJobs {
    /** Runs [FriendJobs.PERIODIC] every hour while [on], or cancels it. */
    fun periodic(on: Boolean)

    /** Runs [FriendJobs.SEND] once soon, with Retry backoff, to send what waits (C8). */
    fun soon()
}

/** [SyncJobs] on LightWork (WorkManager): one-off and periodic jobs that run with the Tool off screen. */
class LightSyncJobs(private val context: SealedLightContext) : SyncJobs {
    override fun periodic(on: Boolean) {
        if (on) LightWork.enqueuePeriodic(context, FriendJobs.PERIODIC, FriendJobs.INTERVAL)
        else LightWork.cancel(context, FriendJobs.PERIODIC)
    }

    override fun soon() {
        LightWork.enqueue(context, FriendJobs.SEND)
    }
}

/**
 * The one owner of Play a friend in this process: the [Correspondence] sync engine over the Relay
 * at [RelayConfig.url] and `correspondence.json` in `no_backup`, what the screens show, and the
 * LightWork schedule. Like [GameOwner], the screens' view models and the background jobs are views
 * onto it; [Correspondence]'s lock keeps a screen and a job from interleaving.
 *
 * It never touches the engine or the Book (W10): no Game Hint, no Book Move, no eval, no Takeback.
 *
 * Nothing here sends a request unless the phone holds a Correspondence Game, an open invite or a Seat
 * being taken, or the user creates or redeems a code (ADR 0004): [sync] asks [Correspondence.hasWork]
 * first, and the periodic job runs only while some Game waits on the opponent (C2, W7).
 *
 * Every operation runs on [scope] (a background dispatcher on the phone), so leaving a screen mid-send
 * doesn't stop it (C8), and the entry is saved before anything is sent anyway. Results that a screen
 * waits for come back on [ui].
 *
 * The live socket (G3, W7) is [live]'s, through one [LiveOwner]: only for the Game whose board shows
 * ([watch], [unwatch]), never for Home, Puzzles, the list or an invite. Without [live], no socket.
 */
class FriendOwner(
    private val correspondence: Correspondence,
    private val jobs: SyncJobs,
    private val scope: CoroutineScope,
    private val ui: CoroutineContext = Dispatchers.Main,
    live: LiveConnector? = null,
    clock: () -> Long = System::currentTimeMillis,
    liveTickMs: Long? = LiveOwner.TICK_MS,
) {
    private val states = MutableStateFlow(FriendState(correspondence.games, correspondence.seats))

    private val liveOwner: LiveOwner? = live?.let { LiveOwner(correspondence, it, scope, clock, tickMs = liveTickMs, changed = { publish() }) }

    val state: StateFlow<FriendState> = states

    /** Whether the periodic job is scheduled, as far as this process knows; null until it first decides. */
    private var periodicOn: Boolean? = null
    private var noticeIds = 0L

    /** The rolled-back entry each Game last showed a notice for, so a notice shows once. */
    private val shownRollbacks = HashMap<String, Append?>()

    init {
        for (game in correspondence.games) shownRollbacks[game.gameId] = game.rolledBack
        schedule(afterUserAction = true)
    }

    /** The phone's estimate of the Relay's clock, for Time Left (V14). */
    fun serverNow(): Long = correspondence.serverNow()

    // ---- Syncing (W7) ---------------------------------------------------------------------------

    /** syncAll in the background, when the Tool opens and when Play a friend or a Game comes on screen. */
    fun sync() {
        if (!correspondence.hasWork) return
        scope.launch { syncNow() }
    }

    /**
     * syncAll now (the LightWork jobs' body). Null when there was nothing to do: no request went out.
     * From the [periodicJob], a sync that leaves nothing waiting on the opponent cancels that job.
     */
    suspend fun syncNow(periodicJob: Boolean = false): SyncReport? {
        val report = if (correspondence.hasWork) correspondence.syncAll() else null
        publish(stopIdlePeriodic = periodicJob)
        return report
    }

    // ---- The live socket (G3, W7) ---------------------------------------------------------------

    /** [gameId]'s board shows: its socket opens if the Game is started and not Stopped. */
    fun watch(gameId: String) {
        liveOwner?.watch(gameId)
        publish()
    }

    /**
     * [gameId]'s board left the screen. On pause ([now]) the socket closes at once; under another
     * screen it stays [LiveOwner.GRACE_MS], so the board's own Menu doesn't drop Live (decision log U1).
     */
    fun unwatch(gameId: String, now: Boolean) {
        liveOwner?.unwatch(gameId, now)
        publish()
    }

    /**
     * onAppPause on any Play a friend screen: whatever Game is watched loses its socket now (W7, U1).
     * LightActivity pauses only the top screen, so a board under its Menu, or still in its 10 s grace
     * under the list, never hears of the pause itself.
     */
    fun pause() {
        liveOwner?.pause()
        publish()
    }

    /** The live socket's timers, for a test that moves time by hand (built with no tick). */
    internal fun tickLive() = liveOwner?.tick()

    // ---- The Move and its confirmation (F11) ----------------------------------------------------

    fun choose(gameId: String, move: Move) = states.update { it.copy(chosen = it.chosen + (gameId to move)) }

    fun undo(gameId: String) = states.update { it.copy(chosen = it.chosen - gameId) }

    /** Send, or "Send and offer draw" (W2): the chosen Move goes, and with [offerDraw] a draw offer is held until it is stored. */
    fun send(gameId: String, offerDraw: Boolean = false) {
        val move = states.value.chosen[gameId] ?: return
        run(gameId, clearChosen = true) { correspondence.play(gameId, move, offerDraw) }
    }

    /** Retry: sends the pending entry again, then reads what is new. */
    fun retry(gameId: String) = run(gameId) { correspondence.sync(gameId) }

    fun offerDraw(gameId: String) = run(gameId) { correspondence.offerDraw(gameId) }

    fun acceptDraw(gameId: String) = run(gameId) { correspondence.acceptDraw(gameId) }

    fun declineDraw(gameId: String) = run(gameId) { correspondence.declineDraw(gameId) }

    fun resign(gameId: String) = run(gameId) { correspondence.resign(gameId) }

    fun claim(gameId: String) = run(gameId) { correspondence.claimTimeout(gameId) }

    fun rematch(gameId: String) = run(gameId) { correspondence.offerRematch(gameId) }

    fun acceptRematch(gameId: String, done: (Delivery) -> Unit = {}) = run(gameId, done = done) { correspondence.acceptRematch(gameId) }

    fun declineRematch(gameId: String) = run(gameId) { correspondence.declineRematch(gameId) }

    fun cancelInvite(gameId: String) = run(gameId) { correspondence.cancelInvite(gameId) }

    fun forget(gameId: String, done: (Delivery) -> Unit = {}) = run(gameId, notify = false, done = done) { correspondence.forget(gameId) }

    fun rename(gameId: String, label: String) = run(gameId, notify = false) { correspondence.rename(gameId, label) }

    // ---- Invites (C3, W6) -----------------------------------------------------------------------

    /** Creates a Game with an Invite Code; [done] gets the Game (its code shows) or the refusal. */
    fun create(side: Side, daysPerMove: Int, done: (Delivery) -> Unit) = run(null, done = done) { correspondence.createInvite(side, daysPerMove) }

    /** Takes the other Seat with a typed code; [done] gets the Game or why not (W6's Enter code errors). */
    fun redeem(typed: String, done: (Delivery) -> Unit) = run(null, done = done) { correspondence.redeemInvite(typed) }

    // ---- Inside -------------------------------------------------------------------------------

    private fun run(
        gameId: String?,
        clearChosen: Boolean = false,
        notify: Boolean = true,
        done: (Delivery) -> Unit = {},
        action: suspend () -> Delivery,
    ) {
        if (gameId != null) {
            if (gameId in states.value.sending) return
            states.update { it.copy(sending = it.sending + gameId, chosen = if (clearChosen) it.chosen - gameId else it.chosen) }
        }
        scope.launch {
            val delivery = try {
                action()
            } finally {
                if (gameId != null) states.update { it.copy(sending = it.sending - gameId) }
            }
            if (notify && gameId != null && delivery is Delivery.Refused) refusalNotice(gameId, delivery.reason)
            publish(afterUserAction = true)
            withContext(ui) { done(delivery) }
        }
    }

    /** A refusal the strip doesn't already show by its state gets its copy for a few seconds (W10). */
    private fun refusalNotice(gameId: String, reason: Refusal) {
        when (reason) {
            // Shown by the Game's own state: stopped, "Update Chess", or what rolled the entry back (a draw
            // offer, a Result), with its own notice for a claim or an offer.
            Refusal.HALTED, Refusal.NEEDS_UPDATE, Refusal.ROLLED_BACK -> {}
            else -> notice(gameId, UiCopy.refusal(reason))
        }
    }

    private fun notice(gameId: String, text: String) {
        val notice = Notice(text, ++noticeIds)
        states.update { it.copy(notices = it.notices + (gameId to notice)) }
        scope.launch {
            delay(NOTICE_MS)
            states.update { s -> if (s.notices[gameId] == notice) s.copy(notices = s.notices - gameId) else s }
        }
    }

    /** The engine's Games into [state], a notice for each new rollback of a claim or an offer (W2, W4), and the schedule. */
    private fun publish(afterUserAction: Boolean = false, stopIdlePeriodic: Boolean = false) {
        val games = correspondence.games
        synchronized(shownRollbacks) {
            for (game in games) {
                val rolled = game.rolledBack
                if (shownRollbacks.containsKey(game.gameId) && shownRollbacks[game.gameId] == rolled) continue
                shownRollbacks[game.gameId] = rolled
                when (rolled?.entryKind) {
                    EntryKind.CLAIM -> notice(game.gameId, UiCopy.NOT_YET)
                    EntryKind.DRAW_OFFER -> notice(game.gameId, UiCopy.OFFER_NOT_SENT)
                    else -> {}
                }
            }
        }
        states.update { s ->
            s.copy(
                games = games,
                seats = correspondence.seats,
                chosen = s.chosen.filterKeys { id -> games.any { it.gameId == id && it.yourMove } },
            )
        }
        liveOwner?.reconcile()
        // Read inside the update: publish runs on OkHttp's threads too, and a retried update must
        // not write a view read before another publish's.
        states.update {
            val view = liveOwner?.view
            it.copy(linked = view?.gameId?.takeIf { view.open }, live = view?.gameId?.takeIf { view.live })
        }
        schedule(afterUserAction, stopIdlePeriodic)
    }

    /**
     * W7: the periodic job only while some Game waits on the opponent; a one-off job, after the user's
     * own action, when something still waits to send. The jobs themselves never enqueue the one-off job
     * (LightWork replaces a running one); they return Retry instead.
     */
    private fun schedule(afterUserAction: Boolean = false, stopIdlePeriodic: Boolean = false) {
        val games = correspondence.games
        val waiting = games.any { it.waitingOnOpponent }
        synchronized(this) {
            if (waiting && periodicOn != true) jobs.periodic(true)
            if (!waiting && (periodicOn == true || stopIdlePeriodic)) jobs.periodic(false)
            periodicOn = waiting
        }
        val unsent = correspondence.seats.isNotEmpty() ||
            games.any { it.halt == null && (it.pending != null || it.invite?.cancelling == true) }
        if (afterUserAction && unsent) jobs.soon()
    }

    companion object {
        /** How long a notice stays in the strip (W4: a rolled-back claim shows "Not yet" for 5 s). */
        const val NOTICE_MS = 5_000L

        private val owners = HashMap<String, FriendOwner>()

        /**
         * The process's owner for [filesDir], or null while the Relay URL is empty (W8): then nothing
         * of Play a friend exists, no client, no transport, no job.
         */
        fun of(filesDir: File, jobs: () -> SyncJobs, url: String = RelayConfig.url): FriendOwner? {
            if (url.isEmpty()) return null
            return synchronized(owners) {
                owners.getOrPut(filesDir.canonicalPath) {
                    val store = CorrespondenceStore(CorrespondenceStore.noBackupDir(filesDir))
                    val http = OkHttpTransport.defaultClient()
                    val correspondence = Correspondence(RelayClient(OkHttpTransport(url, http)), store)
                    val live = OkHttpLiveConnector(url, http)
                    FriendOwner(correspondence, jobs(), CoroutineScope(SupervisorJob() + Dispatchers.IO), live = live)
                }
            }
        }

        fun of(context: SealedLightContext): FriendOwner? = of(context.filesDir, { LightSyncJobs(context) })
    }
}

/** The background sync's two LightWork jobs (W7) and their one body. */
object FriendJobs {
    /** Every hour, only while some Game waits on the opponent (C2). */
    const val PERIODIC = "friend-sync"

    /** Once, soon after the user's entry was saved and not sent, with Retry backoff (C8). */
    const val SEND = "friend-send"

    val INTERVAL = 1.hours

    /**
     * The jobs' body: the process's [FriendOwner] runs syncAll. With the Relay URL empty there is no
     * owner, and the job does nothing (and none is ever scheduled then, W8). The one-off job asks
     * LightWork to run again while something still waits to send.
     */
    suspend fun run(owner: FriendOwner?, periodic: Boolean): LightJobResult {
        if (owner == null) return LightJobResult.Success()
        val report = try {
            owner.syncNow(periodicJob = periodic)
        } catch (e: CancellationException) {
            // LightWork stopped the job: it ends as cancelled, not as a sync that failed. A
            // TimeoutCancellationException is one too, so a `withTimeout` around a request must catch
            // its own timeout as a failure (Retry, C8) before it gets here.
            throw e
        } catch (e: Exception) {
            Log.w("Chess", "background sync failed", e)
            return if (periodic) LightJobResult.Success() else LightJobResult.Retry
        }
        return if (!periodic && report?.retry == true) LightJobResult.Retry else LightJobResult.Success()
    }
}

@LightJob(FriendJobs.PERIODIC)
val friendSync: LightJobHandler = { context, _ -> FriendJobs.run(FriendOwner.of(context), periodic = true) }

@LightJob(FriendJobs.SEND)
val friendSend: LightJobHandler = { context, _ -> FriendJobs.run(FriendOwner.of(context), periodic = false) }
