package com.metahumanz.pacilread

import android.animation.Animator
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

internal class HomePagerController(
    private val pageContainer: View?,
    private val callback: Callback,
) {
    interface Callback {
        fun sectionForPage(page: Int): View?
        fun isTouchBlocked(event: MotionEvent): Boolean
        fun onPageChanged(page: Int, syncFirst: Boolean)
        fun onSelectionChanged()
    }

    private val touchSlop = pageContainer?.let { ViewConfiguration.get(it.context).scaledTouchSlop } ?: 0
    private val pageSwitchInterpolator: Interpolator = PathInterpolator(0.22f, 0f, 0f, 1f)
    private val pageReboundInterpolator: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val activePages = ArrayList<Int>()

    private var currentPageValue = HomeNavigationController.PAGE_BOOKSHELF
    private var pendingPage = -1
    private var pendingDirection = 0
    private var swipeCandidate = false
    private var swipeDragging = false
    private var edgeDragging = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastEventTime = 0L
    private var velocityX = 0f
    private var animator: ValueAnimator? = null

    val currentPage: Int
        get() = currentPageValue

    fun setActivePages(pages: List<Int>?) {
        activePages.clear()
        if (pages != null) {
            activePages.addAll(pages)
        }
        if (!activePages.contains(currentPageValue) && activePages.isNotEmpty()) {
            currentPageValue = activePages[0]
        }
    }

    fun setCurrentPage(page: Int) {
        currentPageValue = page
    }

    fun showImmediate(page: Int) {
        cancelAnimation()
        currentPageValue = page
        normalizeSectionsForPage(page)
        callback.onSelectionChanged()
    }

    private fun normalizeSectionsForPage(visiblePage: Int) {
        for (candidate in ALL_PAGES) {
            val section = callback.sectionForPage(candidate) ?: continue
            section.animate().cancel()
            section.translationX = 0f
            section.visibility = if (candidate == visiblePage) View.VISIBLE else View.GONE
        }
    }

    fun selectPage(page: Int, animate: Boolean, syncFirst: Boolean) {
        if (!activePages.contains(page)) return
        if (page == currentPageValue) {
            callback.onPageChanged(page, syncFirst)
            return
        }
        val oldPage = currentPageValue
        currentPageValue = page
        if (animate && pageContainer != null && pageContainer.width > 0) {
            animatePageTransition(oldPage, page, CLICK_ANIMATION_DURATION)
        } else {
            showImmediate(page)
        }
        callback.onPageChanged(page, syncFirst)
    }

    fun handleTouchEvent(event: MotionEvent): Boolean {
        if (activePages.size <= 1 || pageContainer == null) return false
        if (callback.isTouchBlocked(event)) {
            resetSwipe()
            return false
        }
        val x = event.x
        val y = event.y
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (animator?.isRunning == true) {
                    cancelAnimation()
                    showImmediate(currentPageValue)
                }
                swipeCandidate = true
                swipeDragging = false
                edgeDragging = false
                downX = x
                downY = y
                lastX = x
                lastEventTime = event.eventTime
                velocityX = 0f
                pendingPage = -1
                pendingDirection = 0
                false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!swipeCandidate) return false
                val deltaX = x - downX
                val deltaY = y - downY
                if (!swipeDragging) {
                    if (abs(deltaY) > touchSlop && abs(deltaY) > abs(deltaX)) {
                        resetSwipe()
                        return false
                    }
                    if (abs(deltaX) <= touchSlop * 1.25f || abs(deltaX) <= abs(deltaY) * 1.15f) {
                        return false
                    }
                    pendingDirection = if (deltaX < 0f) 1 else -1
                    pendingPage = pageForDirection(pendingDirection)
                    swipeDragging = true
                    edgeDragging = pendingPage < 0
                    prepareSwipe()
                    cancelChildTouch(event)
                }
                updateSwipe(deltaX)
                updateVelocity(x, event.eventTime)
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (swipeDragging) {
                    finishSwipe(x - downX)
                    true
                } else {
                    resetSwipe()
                    false
                }
            }

            else -> false
        }
    }

    private fun animatePageTransition(oldPage: Int, newPage: Int, duration: Long) {
        val oldSection = callback.sectionForPage(oldPage)
        val newSection = callback.sectionForPage(newPage)
        if (oldSection == null || newSection == null || pageContainer == null) {
            showImmediate(newPage)
            return
        }
        val width = containerWidth()
        val direction = if (activePages.indexOf(newPage) > activePages.indexOf(oldPage)) 1 else -1
        oldSection.animate().cancel()
        newSection.animate().cancel()
        oldSection.visibility = View.VISIBLE
        newSection.visibility = View.VISIBLE
        oldSection.translationX = 0f
        newSection.translationX = direction * width.toFloat()
        animatePair(
            oldSection,
            newSection,
            0f,
            -direction * width.toFloat(),
            direction * width.toFloat(),
            0f,
            duration,
            pageSwitchInterpolator,
        ) {
            normalizeSectionsForPage(newPage)
            callback.onSelectionChanged()
        }
        callback.onSelectionChanged()
    }

    private fun pageForDirection(direction: Int): Int {
        val targetIndex = activePages.indexOf(currentPageValue) + direction
        return if (targetIndex < 0 || targetIndex >= activePages.size) -1 else activePages[targetIndex]
    }

    private fun prepareSwipe() {
        callback.sectionForPage(currentPageValue)?.let { current ->
            current.animate().cancel()
            current.visibility = View.VISIBLE
        }
        if (!edgeDragging) {
            callback.sectionForPage(pendingPage)?.let { target ->
                target.animate().cancel()
                target.visibility = View.VISIBLE
                target.translationX = pendingDirection * containerWidth().toFloat()
            }
        }
    }

    private fun updateSwipe(deltaX: Float) {
        val current = callback.sectionForPage(currentPageValue) ?: return
        val width = containerWidth()
        val clamped = max(-width.toFloat(), min(width.toFloat(), deltaX))
        if (edgeDragging) {
            current.translationX = clamped * EDGE_RESISTANCE
            return
        }
        val target = callback.sectionForPage(pendingPage) ?: return
        current.translationX = clamped
        target.translationX = pendingDirection * width + clamped
    }

    private fun finishSwipe(deltaX: Float) {
        if (edgeDragging || pendingPage < 0) {
            reboundCurrentPage()
            resetSwipe()
            return
        }
        val current = callback.sectionForPage(currentPageValue)
        val target = callback.sectionForPage(pendingPage)
        if (current == null || target == null) {
            resetSwipe()
            showImmediate(currentPageValue)
            return
        }
        val width = containerWidth()
        val commit = abs(deltaX) > width * COMMIT_DISTANCE_RATIO ||
            (abs(velocityX) > COMMIT_VELOCITY_PX_PER_MS && velocityX.sign == -pendingDirection.toFloat())
        val targetPage = pendingPage
        if (commit) {
            val currentFrom = current.translationX
            val targetFrom = target.translationX
            currentPageValue = targetPage
            animatePair(
                current,
                target,
                currentFrom,
                -pendingDirection * width.toFloat(),
                targetFrom,
                0f,
                SETTLE_ANIMATION_DURATION,
                pageSwitchInterpolator,
            ) {
                normalizeSectionsForPage(targetPage)
                callback.onSelectionChanged()
            }
            callback.onPageChanged(targetPage, false)
            callback.onSelectionChanged()
        } else {
            animatePair(
                current,
                target,
                current.translationX,
                0f,
                target.translationX,
                pendingDirection * width.toFloat(),
                REBOUND_ANIMATION_DURATION,
                pageReboundInterpolator,
            ) {
                normalizeSectionsForPage(currentPageValue)
                callback.onSelectionChanged()
            }
        }
        resetSwipe()
    }

    private fun reboundCurrentPage() {
        val current = callback.sectionForPage(currentPageValue) ?: return
        animateSingle(
            current,
            current.translationX,
            0f,
            REBOUND_ANIMATION_DURATION,
            pageReboundInterpolator,
        ) {
            normalizeSectionsForPage(currentPageValue)
            callback.onSelectionChanged()
        }
    }

    private fun updateVelocity(x: Float, eventTime: Long) {
        val elapsed = max(1L, eventTime - lastEventTime)
        velocityX = (x - lastX) / elapsed
        lastX = x
        lastEventTime = eventTime
    }

    private fun animatePair(
        first: View,
        second: View,
        firstFrom: Float,
        firstTo: Float,
        secondFrom: Float,
        secondTo: Float,
        duration: Long,
        interpolator: Interpolator,
        endAction: Runnable?,
    ) {
        cancelAnimation()
        animator = ValueAnimator.ofFloat(0f, 1f).also { valueAnimator ->
            valueAnimator.duration = duration
            valueAnimator.interpolator = interpolator
            valueAnimator.addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                first.translationX = lerp(firstFrom, firstTo, progress)
                second.translationX = lerp(secondFrom, secondTo, progress)
            }
            valueAnimator.addListener(SimpleAnimatorListener(endAction))
            valueAnimator.start()
        }
    }

    private fun animateSingle(
        view: View,
        from: Float,
        to: Float,
        duration: Long,
        interpolator: Interpolator,
        endAction: Runnable?,
    ) {
        cancelAnimation()
        animator = ValueAnimator.ofFloat(0f, 1f).also { valueAnimator ->
            valueAnimator.duration = duration
            valueAnimator.interpolator = interpolator
            valueAnimator.addUpdateListener { animation ->
                view.translationX = lerp(from, to, animation.animatedValue as Float)
            }
            valueAnimator.addListener(SimpleAnimatorListener(endAction))
            valueAnimator.start()
        }
    }

    private fun cancelAnimation() {
        animator?.cancel()
        animator = null
    }

    private fun resetSwipe() {
        swipeCandidate = false
        swipeDragging = false
        edgeDragging = false
        pendingPage = -1
        pendingDirection = 0
        velocityX = 0f
    }

    private fun cancelChildTouch(event: MotionEvent?) {
        val container = pageContainer ?: return
        event ?: return
        val cancelEvent = MotionEvent.obtain(event)
        cancelEvent.action = MotionEvent.ACTION_CANCEL
        container.dispatchTouchEvent(cancelEvent)
        cancelEvent.recycle()
    }

    private fun containerWidth(): Int = max(pageContainer?.width ?: 0, 1)

    private fun lerp(start: Float, end: Float, progress: Float): Float = start + (end - start) * progress

    private class SimpleAnimatorListener(private val endAction: Runnable?) : Animator.AnimatorListener {
        private var cancelled = false

        override fun onAnimationStart(animation: Animator) = Unit

        override fun onAnimationEnd(animation: Animator) {
            if (!cancelled) endAction?.run()
        }

        override fun onAnimationCancel(animation: Animator) {
            cancelled = true
        }

        override fun onAnimationRepeat(animation: Animator) = Unit
    }

    private companion object {
        val ALL_PAGES = intArrayOf(
            HomeNavigationController.PAGE_BOOKSHELF,
            HomeNavigationController.PAGE_STATS,
            HomeNavigationController.PAGE_BOOKMARKS,
            HomeNavigationController.PAGE_SETTINGS,
        )
        const val CLICK_ANIMATION_DURATION = 280L
        const val SETTLE_ANIMATION_DURATION = 220L
        const val REBOUND_ANIMATION_DURATION = 190L
        const val EDGE_RESISTANCE = 0.22f
        const val COMMIT_DISTANCE_RATIO = 0.23f
        const val COMMIT_VELOCITY_PX_PER_MS = 0.62f
    }
}
