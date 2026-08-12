package com.mircowuffwuff.sharpemu

import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.color.MaterialColors
// the colour roles are Material's own attributes, and this module's R does not carry them: a
// non-transitive R class holds what the module itself declares and nothing a library does.
import com.google.android.material.R as MaterialR
import kotlin.math.hypot

/**
 * The panel the back button opens over a running game.
 *
 * **It is the only thing the back button does during a run, and that is the point.** A guest run has
 * no state that survives being left, so a back press that finished the activity would end a game
 * silently, at the depth of one accidental gesture. Here back opens this, back again closes it, and
 * leaving is a labelled button inside it — two deliberate acts, of which the second says what it
 * does.
 *
 * **It wears the scheme the settings scene names, and that arrives as a [Context].** [MainActivity]
 * keeps the framework fullscreen theme, because its window is a surface a guest renders into — so
 * every view here is built from [Theme.overlayContext] instead, which resolves the same colour roles
 * the app's own screens do. What is drawn over a game and what is drawn on the game list are one
 * palette; the window under them is not involved.
 *
 * **The panel is a third of the width and it is a weight rather than a measurement.** Dividing the
 * screen by three in pixels would be the same answer on this device and a wrong one on a panel of
 * another shape, and the layout already has a mechanism for "a third".
 */
class GuestOverlay(private val context: Context, private val onExit: Runnable) {

    /**
     * What the panel is filled with, and it is the background every other screen in the app draws.
     *
     * `colorSurface` rather than the container role a card takes: the panel is a screen's worth of
     * background that happens to be a third of the width, and the buttons on it are what sit above
     * it. Filling it with the raised role would leave a card on a card.
     */
    private val surface = MaterialColors.getColor(context, MaterialR.attr.colorSurface, FALLBACK_SURFACE)

    /**
     * The whole-screen dim, which is also what swallows a touch aimed past the panel.
     *
     * An [OverGuestSurface] rather than a plain layout, and **that class is the one thing here worth
     * reading before changing anything**: it is `INVISIBLE` rather than `GONE` while closed, so the
     * panel has a width to slide in from on the first open of a run, and the price of `INVISIBLE` is
     * that becoming visible has to ask for a layout or it never reaches the display.
     */
    private val root: OverGuestSurface = OverGuestSurface(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(SCRIM)
    }

    private val panel: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(surface)
        gravity = Gravity.BOTTOM
    }

    /** Whether a back press closes this or opens it. Flipped before the animation, not after it. */
    var isOpen: Boolean = false
        private set

    /**
     * The panel's own padding, in pixels, since the panel itself is not inflated.
     *
     * **It is the settings list's padding and not the whole gap**, the rest being the 6dp each
     * button carries in [R.layout.item_guest_action] — which is the settings scene's arrangement
     * exactly: the list pads, the card has a margin, and two buttons meeting have twice the margin
     * between them while an edge has the margin plus this.
     */
    private val pad = (PAD * context.resources.displayMetrics.density).toInt()

    init {
        panel.addView(action(R.string.overlay_exit, R.drawable.ic_exit_to_app) {
            // the run ends here and the process ends with it, which is the same ending every launch
            // that is not exit_group already takes. the guest is not asked to stop first because
            // there is nothing to ask with: its threads are inside translated code, which is the
            // very reason the host layer answers exit_group with _exit.
            close()
            onExit.run()
        })

        // a cutout sits over the panel in landscape and over nothing else, since the surface below
        // is meant to reach every edge. so the padding is the panel's rather than the window's, and
        // MainActivity stays what SystemBars documents it as: a screen that pads for nothing.
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(pad + insets.left, pad + insets.top, pad, pad + insets.bottom)
            windowInsets
        }
        panel.updatePadding(pad, pad, pad, pad)

        val past = View(context)
        past.setOnClickListener { close() }

        root.addView(panel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(past, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))
        // the panel eats what lands on it, so a touch beside a button does not read as a tap past
        // the overlay and close it.
        panel.isClickable = true
    }

    /** Added over the surface by [MainActivity], above the unpacking bar. */
    fun view(): View = root

    fun open() {
        if (isOpen) {
            return
        }
        isOpen = true
        root.show()
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(SLIDE).start()
        panel.translationX = -panel.width.toFloat()
        panel.animate().translationX(0f).setDuration(SLIDE).start()
    }

    fun close() {
        if (!isOpen) {
            return
        }
        isOpen = false
        root.animate().alpha(0f).setDuration(SLIDE)
            .withEndAction { root.hide() }.start()
        panel.animate().translationX(-panel.width.toFloat()).setDuration(SLIDE).start()
    }

    /**
     * One button on the panel: the settings scene's section button, with one line rather than two.
     *
     * **Inflated, so that it is that component rather than a copy of it.** The card, the corner, the
     * outline, the icon size and the type all come from [R.layout.item_guest_action], which shares
     * its numbers with the section button deliberately — a hand-drawn imitation would be a second
     * place to change when either moves.
     *
     * Inflated against [panel] without attaching, which is what keeps the layout's own margin: an
     * inflation with no parent drops the `layout_` attributes on the floor and the button would sit
     * hard against the panel's padding.
     */
    private fun action(label: Int, icon: Int, onClick: () -> Unit): View {
        val card = LayoutInflater.from(context).inflate(R.layout.item_guest_action, panel, false)
        card.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        card.findViewById<TextView>(R.id.title).setText(label)
        card.setOnClickListener { onClick() }
        recoverCancelledTap(card)
        return card
    }

    /**
     * Makes a press that the input system cancels count anyway, when the press was going to be a tap.
     *
     * **A gesture on this panel can be cancelled by something that is not the user and not this app.**
     * The platform reconfigures an input device while a finger is already down -- a touchscreen coming
     * back from idle is enough -- and every stream in flight is cancelled at that moment. The button
     * has already lit, because the ripple starts on the press; what never arrives is the click. So the
     * press looks understood, the panel stays open, and the run does not end, which for **the only way
     * out of a game that is not the guest's own exit** is the one failure worth writing code against.
     *
     * The test is what separates that from a gesture the user abandoned: one finger, no travel beyond
     * the slop that already defines a tap, and a cancel arriving sooner than a long press. A drag off
     * the button travels, and a hold that is stolen by something the user *meant* -- a system gesture,
     * a drag -- is either long or has moved. What is left is a finger that pressed and did not move,
     * whose intent is not in question.
     *
     * **The comparison is in display coordinates on both sides**, which is not a stylistic choice: a
     * cancel is dispatched to a child without the transform an ordinary event is given, so its view
     * coordinates and the down's are not in the same space and subtracting them measures the view's
     * own offset rather than a finger.
     *
     * It returns false throughout, so the card handles every gesture exactly as it does without this.
     */
    private fun recoverCancelledTap(card: View) {
        var downX = 0f
        var downY = 0f
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val held = ViewConfiguration.getLongPressTimeout()
        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                }
                MotionEvent.ACTION_CANCEL -> {
                    val moved = hypot(event.rawX - downX, event.rawY - downY)
                    if (event.pointerCount == 1 && moved <= slop &&
                        event.eventTime - event.downTime < held) {
                        // the one line this leaves in an ordinary run, and it is here because the
                        // event it reports cannot be provoked: it says the press was rescued rather
                        // than lost, which is the difference nobody can see from the outside -- a
                        // rescued press and one that was never cancelled look identical on screen.
                        Log.i(TAG, "[app] a cancelled press on the overlay counted as a tap")
                        view.performClick()
                    }
                }
            }
            false
        }
    }

    private companion object {
        /**
         * The dim over the game, and **the one colour here that is not the scheme's**.
         *
         * It is a shade cast on somebody else's picture rather than a surface of ours, so it is black
         * in every scheme: a light scheme's own surface used as a dim would wash the game out and
         * leave the panel with nothing to stand against, which is the opposite of what the dim is
         * for. The panel over it is the scheme's, at whatever lightness the scheme is.
         */
        const val SCRIM = 0x99000000.toInt()
        /** What a role resolves to on a theme that does not name it, which is no theme this app has. */
        const val FALLBACK_SURFACE = 0xFF121418.toInt()
        /** The settings list's own horizontal padding. The rest of the gap is each button's margin. */
        const val PAD = 10
        const val SLIDE = 160L
        /** [MainActivity]'s own, so one logcat filter catches a run and what the overlay did in it. */
        const val TAG = "sharpemu"
    }
}
