package com.metahumanz.pacilread.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class LaunchSourceTransition private constructor() {
    class Source private constructor(bounds: Rect?, internal val sourceSnapshot: Bitmap?) {
        internal val sourceBounds = bounds?.let(::Rect)
        fun bounds(): Rect? = sourceBounds?.let(::Rect)
        fun snapshot(): Bitmap? = sourceSnapshot

        companion object {
            internal fun create(bounds: Rect?, snapshot: Bitmap?) = Source(bounds, snapshot)
        }
    }

    class Options private constructor(
        @JvmField val durationMs: Long,
        @JvmField val snapshotFadeStartFraction: Float,
        @JvmField val interpolator: Interpolator,
        @JvmField val enterUsesSnapshotOverlay: Boolean,
        @JvmField val enterFadesContent: Boolean,
        @JvmField val enterAnimatesLiveContent: Boolean,
        @JvmField val exitUsesScreenCornerClip: Boolean,
    ) {
        fun withDuration(durationMs: Long) = Options(durationMs, snapshotFadeStartFraction, interpolator,
            enterUsesSnapshotOverlay, enterFadesContent, enterAnimatesLiveContent, exitUsesScreenCornerClip)
        fun withSnapshotFadeStartFraction(fraction: Float) = Options(durationMs, clampOption(fraction, 0f, 1f), interpolator,
            enterUsesSnapshotOverlay, enterFadesContent, enterAnimatesLiveContent, exitUsesScreenCornerClip)
        fun withEnterSnapshotOverlay(useSnapshotOverlay: Boolean) = Options(durationMs, snapshotFadeStartFraction, interpolator,
            useSnapshotOverlay, enterFadesContent, enterAnimatesLiveContent, exitUsesScreenCornerClip)
        fun withEnterContentFade(fadeContent: Boolean) = Options(durationMs, snapshotFadeStartFraction, interpolator,
            enterUsesSnapshotOverlay, fadeContent, enterAnimatesLiveContent, exitUsesScreenCornerClip)
        fun withInterpolator(newInterpolator: Interpolator) = Options(durationMs, snapshotFadeStartFraction, newInterpolator,
            enterUsesSnapshotOverlay, enterFadesContent, enterAnimatesLiveContent, exitUsesScreenCornerClip)
        fun withEnterAnimatesLiveContent(animatesLiveContent: Boolean) = Options(durationMs, snapshotFadeStartFraction, interpolator,
            enterUsesSnapshotOverlay, enterFadesContent, animatesLiveContent, exitUsesScreenCornerClip)
        fun withExitScreenCornerClip(useScreenCornerClip: Boolean) = Options(durationMs, snapshotFadeStartFraction, interpolator,
            enterUsesSnapshotOverlay, enterFadesContent, enterAnimatesLiveContent, useScreenCornerClip)

        companion object {
            @JvmStatic
            fun defaults() = Options(260L, 0.5f, DecelerateInterpolator(), true, true, true, true)
            private fun clampOption(value: Float, minimum: Float, maximum: Float) = max(minimum, min(maximum, value))
        }
    }

    companion object {
        private const val EXTRA_LEFT = "com.metahumanz.pacilread.EXTRA_LAUNCH_SOURCE_LEFT"
        private const val EXTRA_TOP = "com.metahumanz.pacilread.EXTRA_LAUNCH_SOURCE_TOP"
        private const val EXTRA_RIGHT = "com.metahumanz.pacilread.EXTRA_LAUNCH_SOURCE_RIGHT"
        private const val EXTRA_BOTTOM = "com.metahumanz.pacilread.EXTRA_LAUNCH_SOURCE_BOTTOM"
        private const val EXTRA_SNAPSHOT_PATH = "com.metahumanz.pacilread.EXTRA_LAUNCH_SOURCE_SNAPSHOT_PATH"
        private const val LEGACY_EXTRA_LEFT = "com.metahumanz.pacilread.EXTRA_READER_SOURCE_LEFT"
        private const val LEGACY_EXTRA_TOP = "com.metahumanz.pacilread.EXTRA_READER_SOURCE_TOP"
        private const val LEGACY_EXTRA_RIGHT = "com.metahumanz.pacilread.EXTRA_READER_SOURCE_RIGHT"
        private const val LEGACY_EXTRA_BOTTOM = "com.metahumanz.pacilread.EXTRA_READER_SOURCE_BOTTOM"
        private const val SNAPSHOT_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val SNAPSHOT_CACHE_SOFT_LIMIT_BYTES = 16L * 1024L * 1024L
        private const val SNAPSHOT_CACHE_TARGET_BYTES = 8L * 1024L * 1024L

        @JvmStatic
        fun attach(intent: Intent?, sourceView: View?) {
            val source = captureSource(sourceView)
            val bounds = source?.sourceBounds ?: return
            if (intent == null) return
            putBounds(intent, bounds)
            persistSnapshot(sourceView, source.sourceSnapshot)?.let { intent.putExtra(EXTRA_SNAPSHOT_PATH, it) }
        }

        @JvmStatic
        fun attachBoundsOnly(intent: Intent?, sourceView: View?) {
            val bounds = captureBounds(sourceView) ?: return
            if (intent != null) putBounds(intent, bounds)
        }

        @JvmStatic
        fun captureSource(sourceView: View?): Source? {
            val bounds = captureBounds(sourceView) ?: return null
            return Source.create(bounds, captureSnapshot(sourceView))
        }

        @JvmStatic
        fun sourceFromBounds(bounds: Rect?): Source? = bounds?.let { Source.create(it, null) }

        @JvmStatic
        fun captureBounds(sourceView: View?): Rect? {
            if (sourceView == null || sourceView.width <= 0 || sourceView.height <= 0) return null
            val location = IntArray(2)
            sourceView.getLocationOnScreen(location)
            return Rect(location[0], location[1], location[0] + sourceView.width, location[1] + sourceView.height)
        }

        @JvmStatic
        fun fromIntentSource(intent: Intent?): Source? {
            val bounds = fromIntent(intent) ?: return null
            var snapshot: Bitmap? = null
            val snapshotPath = intent?.getStringExtra(EXTRA_SNAPSHOT_PATH)
            if (!snapshotPath.isNullOrBlank()) {
                val snapshotFile = File(snapshotPath)
                try {
                    snapshot = BitmapFactory.decodeFile(snapshotPath)
                } finally {
                    if (snapshotFile.exists()) snapshotFile.delete()
                }
            }
            return Source.create(bounds, snapshot)
        }

        @JvmStatic
        fun fromIntent(intent: Intent?): Rect? {
            intent ?: return null
            val current = intent.hasExtra(EXTRA_LEFT)
            val leftKey = if (current) EXTRA_LEFT else LEGACY_EXTRA_LEFT
            if (!intent.hasExtra(leftKey)) return null
            val bounds = Rect(
                intent.getIntExtra(leftKey, 0),
                intent.getIntExtra(if (current) EXTRA_TOP else LEGACY_EXTRA_TOP, 0),
                intent.getIntExtra(if (current) EXTRA_RIGHT else LEGACY_EXTRA_RIGHT, 0),
                intent.getIntExtra(if (current) EXTRA_BOTTOM else LEGACY_EXTRA_BOTTOM, 0),
            )
            return bounds.takeIf { it.width() > 0 && it.height() > 0 }
        }

        private fun putBounds(intent: Intent, bounds: Rect) {
            intent.putExtra(EXTRA_LEFT, bounds.left)
            intent.putExtra(EXTRA_TOP, bounds.top)
            intent.putExtra(EXTRA_RIGHT, bounds.right)
            intent.putExtra(EXTRA_BOTTOM, bounds.bottom)
        }

        @JvmStatic
        fun animateExitToSource(targetView: View?, targetBounds: Rect?, durationMs: Long, onComplete: Runnable?): Boolean =
            animateExitToSource(targetView, Source.create(targetBounds, null), Options.defaults().withDuration(durationMs), onComplete)

        @JvmStatic
        fun animateExitToSource(targetView: View?, source: Source?, durationMs: Long, onComplete: Runnable?): Boolean =
            animateExitToSource(targetView, source, Options.defaults().withDuration(durationMs), onComplete)

        @JvmStatic
        fun animateExitToSource(targetView: View?, source: Source?, options: Options, onComplete: Runnable?): Boolean {
            val targetBounds = source?.sourceBounds
            if (targetView == null || targetBounds == null || targetBounds.width() <= 0 || targetBounds.height() <= 0) return false
            if (targetView.width <= 0 || targetView.height <= 0) return false
            if (options.exitUsesScreenCornerClip) ScreenCornerClipper.apply(targetView)
            else ScreenCornerClipper.setClipEnabled(targetView, false)
            targetView.animate().cancel()
            val pivotX = targetView.width / 2f
            val pivotY = targetView.height / 2f
            targetView.pivotX = pivotX
            targetView.pivotY = pivotY
            val targetLocation = untransformedLocationOnScreen(targetView)
            val destScaleX = clampScale(targetBounds.width().toFloat() / targetView.width)
            val destScaleY = clampScale(targetBounds.height().toFloat() / targetView.height)
            val destTransX = targetBounds.centerX() - targetLocation[0] - pivotX
            val destTransY = targetBounds.centerY() - targetLocation[1] - pivotY
            val startScaleX = targetView.scaleX
            val startScaleY = targetView.scaleY
            val startTransX = targetView.translationX
            val startTransY = targetView.translationY
            val startAlpha = targetView.alpha
            val snapshotView = createSnapshotView(targetView, source.sourceSnapshot, targetLocation)
            snapshotView?.let { it.pivotX = pivotX; it.pivotY = pivotY }
            val fadeStart = options.snapshotFadeStartFraction
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = options.durationMs
                interpolator = options.interpolator
                addUpdateListener { animation ->
                    val fraction = animation.animatedFraction
                    targetView.scaleX = lerp(startScaleX, destScaleX, fraction)
                    targetView.scaleY = lerp(startScaleY, destScaleY, fraction)
                    targetView.translationX = lerp(startTransX, destTransX, fraction)
                    targetView.translationY = lerp(startTransY, destTransY, fraction)
                    val originalAlpha = if (fraction < fadeStart) startAlpha else lerp(startAlpha, 0f, (fraction - fadeStart) / max(1f - fadeStart, 0.001f))
                    targetView.alpha = clampAlpha(originalAlpha)
                    snapshotView?.let { view ->
                        val snapshotAlpha = if (fraction < fadeStart) 0f else (fraction - fadeStart) / max(1f - fadeStart, 0.001f)
                        view.scaleX = lerp(startScaleX, destScaleX, fraction)
                        view.scaleY = lerp(startScaleY, destScaleY, fraction)
                        view.translationX = targetView.translationX
                        view.translationY = targetView.translationY
                        view.alpha = clampAlpha(snapshotAlpha)
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        val parent = snapshotView?.parent
                        if (parent is ViewGroup) parent.overlay.remove(snapshotView)
                        onComplete?.run()
                    }
                })
            }
            animator.start()
            return true
        }

        @JvmStatic
        fun animateExitToSourceWithClip(targetView: View?, source: Source?, options: Options, onComplete: Runnable?): Boolean {
            val targetBounds = source?.sourceBounds
            if (targetView == null || targetBounds == null || targetBounds.width() <= 0 || targetBounds.height() <= 0) return false
            if (targetView.width <= 0 || targetView.height <= 0) return false
            ScreenCornerClipper.apply(targetView)
            targetView.animate().cancel()
            val pivotX = targetView.width / 2f
            val pivotY = targetView.height / 2f
            targetView.pivotX = pivotX
            targetView.pivotY = pivotY
            val targetLocation = untransformedLocationOnScreen(targetView)
            val startScaleX = clampScale(targetView.scaleX)
            val startScaleY = clampScale(targetView.scaleY)
            val startTransX = targetView.translationX
            val startTransY = targetView.translationY
            val startAlpha = targetView.alpha
            val destTransX = targetBounds.centerX() - targetLocation[0] - pivotX
            val destTransY = targetBounds.centerY() - targetLocation[1] - pivotY
            val endScaleX = max(startScaleX, clampScale(targetBounds.width().toFloat() / targetView.width))
            val endScaleY = max(startScaleY, clampScale(targetBounds.height().toFloat() / targetView.height))
            val finalClipWidth = min(targetView.width.toFloat(), max(1f, targetBounds.width() / endScaleX))
            val finalClipHeight = min(targetView.height.toFloat(), max(1f, targetBounds.height() / endScaleY))
            val startClip = targetView.clipBounds ?: Rect(0, 0, targetView.width, targetView.height)
            val endClip = Rect(
                (pivotX - finalClipWidth / 2f).roundToInt(), (pivotY - finalClipHeight / 2f).roundToInt(),
                (pivotX + finalClipWidth / 2f).roundToInt(), (pivotY + finalClipHeight / 2f).roundToInt(),
            )
            clampRectToView(endClip, targetView.width, targetView.height)
            val geoEndFraction = 0.72f
            val animatedClip = Rect(startClip)
            val finalStartClip = Rect(startClip)
            targetView.clipBounds = null
            ScreenCornerClipper.apply(targetView, animatedClip)
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = options.durationMs
                interpolator = options.interpolator
                addUpdateListener { animation ->
                    val fraction = animation.animatedFraction
                    val geoFraction = min(1f, fraction / geoEndFraction)
                    targetView.scaleX = lerp(startScaleX, endScaleX, geoFraction)
                    targetView.scaleY = lerp(startScaleY, endScaleY, geoFraction)
                    targetView.translationX = lerp(startTransX, destTransX, geoFraction)
                    targetView.translationY = lerp(startTransY, destTransY, geoFraction)
                    animatedClip.set(
                        lerp(finalStartClip.left.toFloat(), endClip.left.toFloat(), geoFraction).roundToInt(),
                        lerp(finalStartClip.top.toFloat(), endClip.top.toFloat(), geoFraction).roundToInt(),
                        lerp(finalStartClip.right.toFloat(), endClip.right.toFloat(), geoFraction).roundToInt(),
                        lerp(finalStartClip.bottom.toFloat(), endClip.bottom.toFloat(), geoFraction).roundToInt(),
                    )
                    targetView.invalidateOutline()
                    val alpha = if (fraction < geoEndFraction) startAlpha else lerp(startAlpha, 0f, (fraction - geoEndFraction) / max(1f - geoEndFraction, 0.001f))
                    targetView.alpha = clampAlpha(alpha)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { onComplete?.run() }
                })
            }
            animator.start()
            return true
        }

        @JvmStatic
        fun animateEnterFromSource(targetView: View?, source: Source?, options: Options, onComplete: Runnable?): Boolean {
            val sourceBounds = source?.sourceBounds
            if (targetView == null || sourceBounds == null || sourceBounds.width() <= 0 || sourceBounds.height() <= 0) return false
            if (targetView.width <= 0 || targetView.height <= 0) return false
            targetView.animate().cancel()
            val pivotX = targetView.width / 2f
            val pivotY = targetView.height / 2f
            targetView.pivotX = pivotX
            targetView.pivotY = pivotY
            val targetLocation = untransformedLocationOnScreen(targetView)
            val sourceScaleX = clampScale(sourceBounds.width().toFloat() / targetView.width)
            val sourceScaleY = clampScale(sourceBounds.height().toFloat() / targetView.height)
            val startTranslationX = sourceBounds.centerX() - targetLocation[0] - pivotX
            val startTranslationY = sourceBounds.centerY() - targetLocation[1] - pivotY
            val snapshotOnly = options.enterUsesSnapshotOverlay && !options.enterAnimatesLiveContent && source.sourceSnapshot != null
            if (snapshotOnly) {
                targetView.scaleX = 1f
                targetView.scaleY = 1f
                targetView.translationX = 0f
                targetView.translationY = 0f
                targetView.alpha = 0f
                targetView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                val snapshotView = createEnterSnapshotOverlay(targetView, source, targetLocation, sourceScaleX, sourceScaleY,
                    startTranslationX, startTranslationY, pivotX, pivotY)
                if (snapshotView != null) {
                    snapshotView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    val fadeStart = options.snapshotFadeStartFraction
                    val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = options.durationMs
                        interpolator = options.interpolator
                        addUpdateListener { animation ->
                            val fraction = animation.animatedFraction
                            snapshotView.scaleX = lerp(sourceScaleX, 1f, fraction)
                            snapshotView.scaleY = lerp(sourceScaleY, 1f, fraction)
                            snapshotView.translationX = lerp(startTranslationX, 0f, fraction)
                            snapshotView.translationY = lerp(startTranslationY, 0f, fraction)
                            val snapshotAlpha = if (fraction < fadeStart) 1f else lerp(1f, 0f, (fraction - fadeStart) / max(1f - fadeStart, 0.001f))
                            snapshotView.alpha = clampAlpha(snapshotAlpha)
                            targetView.alpha = clampAlpha(1f - snapshotAlpha)
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                val parent = snapshotView.parent
                                if (parent is ViewGroup) parent.overlay.remove(snapshotView)
                                targetView.alpha = 1f
                                targetView.scaleX = 1f
                                targetView.scaleY = 1f
                                targetView.translationX = 0f
                                targetView.translationY = 0f
                                targetView.setLayerType(View.LAYER_TYPE_NONE, null)
                                onComplete?.run()
                            }
                        })
                    }
                    animator.start()
                    return true
                }
            }
            targetView.scaleX = sourceScaleX
            targetView.scaleY = sourceScaleY
            targetView.translationX = startTranslationX
            targetView.translationY = startTranslationY
            targetView.alpha = if (options.enterFadesContent) 0f else 1f
            targetView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            val snapshotView = if (options.enterUsesSnapshotOverlay) createEnterSnapshotOverlay(targetView, source, targetLocation,
                sourceScaleX, sourceScaleY, startTranslationX, startTranslationY, pivotX, pivotY) else null
            val animator = targetView.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(options.durationMs).setInterpolator(options.interpolator).withLayer()
            if (options.enterFadesContent) animator.alpha(1f)
            animator.withEndAction {
                val parent = snapshotView?.parent
                if (parent is ViewGroup) parent.overlay.remove(snapshotView)
                onComplete?.run()
            }.start()
            snapshotView?.let {
                it.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                it.animate().alpha(0f).setDuration(options.durationMs).setInterpolator(options.interpolator).withLayer().start()
            }
            return true
        }

        @JvmStatic
        fun animateEnterFromSource(targetView: View?, source: Source?, durationMs: Long, onComplete: Runnable?): Boolean =
            animateEnterFromSource(targetView, source, Options.defaults().withDuration(durationMs), onComplete)

        private fun createEnterSnapshotOverlay(
            targetView: View, source: Source, targetLocation: IntArray,
            sourceScaleX: Float, sourceScaleY: Float, startTranslationX: Float, startTranslationY: Float,
            pivotX: Float, pivotY: Float,
        ): ImageView? {
            val snapshot = source.sourceSnapshot ?: return null
            val root = targetView.rootView as? ViewGroup ?: return null
            val rootLocation = IntArray(2)
            root.getLocationOnScreen(rootLocation)
            val left = targetLocation[0] - rootLocation[0]
            val top = targetLocation[1] - rootLocation[1]
            return ImageView(targetView.context).apply {
                setImageBitmap(snapshot)
                scaleType = ImageView.ScaleType.FIT_XY
                alpha = 1f
                this.pivotX = pivotX
                this.pivotY = pivotY
                scaleX = sourceScaleX
                scaleY = sourceScaleY
                translationX = startTranslationX
                translationY = startTranslationY
                layout(left, top, left + targetView.width, top + targetView.height)
                measure(View.MeasureSpec.makeMeasureSpec(targetView.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(targetView.height, View.MeasureSpec.EXACTLY))
                root.overlay.add(this)
            }
        }

        private fun createSnapshotView(targetView: View, snapshot: Bitmap?, targetLocation: IntArray): ImageView? {
            snapshot ?: return null
            val root = targetView.rootView as? ViewGroup ?: return null
            val rootLocation = IntArray(2)
            root.getLocationOnScreen(rootLocation)
            val left = targetLocation[0] - rootLocation[0]
            val top = targetLocation[1] - rootLocation[1]
            return ImageView(targetView.context).apply {
                setImageBitmap(snapshot)
                scaleType = ImageView.ScaleType.FIT_XY
                alpha = 0f
                pivotX = targetView.width / 2f
                pivotY = targetView.height / 2f
                layout(left, top, left + targetView.width, top + targetView.height)
                scaleX = targetView.scaleX
                scaleY = targetView.scaleY
                translationX = targetView.translationX
                translationY = targetView.translationY
                measure(View.MeasureSpec.makeMeasureSpec(targetView.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(targetView.height, View.MeasureSpec.EXACTLY))
                root.overlay.add(this)
            }
        }

        private fun captureSnapshot(sourceView: View?): Bitmap? {
            if (sourceView == null || sourceView.width <= 0 || sourceView.height <= 0) return null
            return try {
                Bitmap.createBitmap(sourceView.width, sourceView.height, Bitmap.Config.ARGB_8888).also { sourceView.draw(Canvas(it)) }
            } catch (_: RuntimeException) { null }
        }

        private fun persistSnapshot(sourceView: View?, snapshot: Bitmap?): String? {
            if (sourceView == null || snapshot == null) return null
            val dir = File(sourceView.context.cacheDir, "launch_sources")
            if (!dir.exists() && !dir.mkdirs()) return null
            cleanupSnapshotCache(dir)
            val file = try { File.createTempFile("source_", ".png", dir) } catch (_: IOException) { return null }
            return try {
                FileOutputStream(file).use { output ->
                    if (snapshot.compress(Bitmap.CompressFormat.PNG, 100, output)) file.absolutePath
                    else { file.delete(); null }
                }
            } catch (_: IOException) { file.delete(); null }
        }

        private fun cleanupSnapshotCache(dir: File?) {
            val files = dir?.listFiles() ?: return
            val now = System.currentTimeMillis()
            var total = 0L
            for (file in files) {
                if (!isSnapshotCacheFile(file)) continue
                if (now - file.lastModified() > SNAPSHOT_MAX_AGE_MS) { file.delete(); continue }
                total += file.length()
            }
            while (total > SNAPSHOT_CACHE_SOFT_LIMIT_BYTES) {
                val oldest = findOldestSnapshotCacheFile(dir) ?: return
                val size = oldest.length()
                if (!oldest.delete()) return
                total -= size
                if (total <= SNAPSHOT_CACHE_TARGET_BYTES) return
            }
        }

        private fun findOldestSnapshotCacheFile(dir: File?): File? {
            val files = dir?.listFiles() ?: return null
            var oldest: File? = null
            for (file in files) if (isSnapshotCacheFile(file) && (oldest == null || file.lastModified() < oldest.lastModified())) oldest = file
            return oldest
        }

        private fun isSnapshotCacheFile(file: File?): Boolean = file != null && file.isFile && file.name.startsWith("source_") && file.name.endsWith(".png")

        private fun untransformedLocationOnScreen(targetView: View): IntArray {
            val scaleX = targetView.scaleX
            val scaleY = targetView.scaleY
            val translationX = targetView.translationX
            val translationY = targetView.translationY
            val alpha = targetView.alpha
            targetView.scaleX = 1f
            targetView.scaleY = 1f
            targetView.translationX = 0f
            targetView.translationY = 0f
            val location = IntArray(2)
            targetView.getLocationOnScreen(location)
            targetView.scaleX = scaleX
            targetView.scaleY = scaleY
            targetView.translationX = translationX
            targetView.translationY = translationY
            targetView.alpha = alpha
            return location
        }

        private fun clampScale(scale: Float) = max(0.01f, min(1f, scale))
        private fun clampAlpha(alpha: Float) = max(0f, min(1f, alpha))

        private fun clampRectToView(rect: Rect, width: Int, height: Int) {
            val rectWidth = max(1, rect.width())
            val rectHeight = max(1, rect.height())
            if (rect.left < 0) { rect.left = 0; rect.right = min(width, rectWidth) }
            if (rect.top < 0) { rect.top = 0; rect.bottom = min(height, rectHeight) }
            if (rect.right > width) { rect.right = width; rect.left = max(0, width - rectWidth) }
            if (rect.bottom > height) { rect.bottom = height; rect.top = max(0, height - rectHeight) }
        }

        private fun lerp(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction
    }
}
