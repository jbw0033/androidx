/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.platform

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewStructure
import android.view.ViewTreeObserver
import android.view.autofill.AutofillValue
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.DrawLayerModifier
import androidx.compose.ui.Modifier
import androidx.compose.ui.RootMeasureBlocks
import androidx.compose.ui.autofill.AndroidAutofill
import androidx.compose.ui.autofill.Autofill
import androidx.compose.ui.autofill.AutofillTree
import androidx.compose.ui.autofill.performAutofill
import androidx.compose.ui.autofill.populateViewStructure
import androidx.compose.ui.autofill.registerCallback
import androidx.compose.ui.autofill.unregisterCallback
import androidx.compose.ui.drawLayer
import androidx.compose.ui.focus.ExperimentalFocus
import androidx.compose.ui.focus.FOCUS_TAG
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusManagerImpl
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.CanvasHolder
import androidx.compose.ui.hapticfeedback.AndroidHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.key.ExperimentalKeyInput
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventAndroid
import androidx.compose.ui.input.key.KeyInputModifier
import androidx.compose.ui.input.pointer.MotionEventAdapter
import androidx.compose.ui.input.pointer.PointerInputEventProcessor
import androidx.compose.ui.input.pointer.ProcessResult
import androidx.compose.ui.node.ExperimentalLayoutNodeApi
import androidx.compose.ui.node.InternalCoreApi
import androidx.compose.ui.node.OwnerScope
import androidx.compose.ui.node.LayoutNode
import androidx.compose.ui.node.LayoutNode.UsageByParent
import androidx.compose.ui.node.MeasureAndLayoutDelegate
import androidx.compose.ui.node.OwnedLayer
import androidx.compose.ui.semantics.SemanticsModifierCore
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.text.InternalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.input.TextInputServiceAndroid
import androidx.compose.ui.text.input.textInputServiceFactory
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.trace
import androidx.core.os.HandlerCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.lifecycle.ViewTreeViewModelStoreOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner
import java.lang.reflect.Method
import android.view.KeyEvent as AndroidKeyEvent

@SuppressLint("ViewConstructor")
@OptIn(
    ExperimentalComposeApi::class,
    ExperimentalFocus::class,
    ExperimentalKeyInput::class,
    ExperimentalLayoutNodeApi::class
)
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
internal class AndroidComposeView(context: Context) : ViewGroup(context), AndroidOwner {

    override val view: View = this

    override var density = Density(context)
        private set

    private val semanticsModifier = SemanticsModifierCore(
        id = SemanticsModifierCore.generateSemanticsId(),
        mergeAllDescendants = false,
        properties = {}
    )

    private val _focusManager: FocusManagerImpl = FocusManagerImpl()
    override val focusManager: FocusManager
        get() = _focusManager

    private val keyInputModifier = KeyInputModifier(null, null)

    private val canvasHolder = CanvasHolder()

    override val root = LayoutNode().also {
        it.measureBlocks = RootMeasureBlocks
        it.modifier = Modifier
            .drawLayer()
            .then(semanticsModifier)
            .then(_focusManager.modifier)
            .then(keyInputModifier)
    }

    override val semanticsOwner: SemanticsOwner = SemanticsOwner(root)
    private val accessibilityDelegate = AndroidComposeViewAccessibilityDelegateCompat(this)

    // Used by components that want to provide autofill semantic information.
    // TODO: Replace with SemanticsTree: Temporary hack until we have a semantics tree implemented.
    // TODO: Replace with SemanticsTree.
    //  This is a temporary hack until we have a semantics tree implemented.
    override val autofillTree = AutofillTree()

    // OwnedLayers that are dirty and should be redrawn.
    internal val dirtyLayers = mutableListOf<OwnedLayer>()

    private val motionEventAdapter = MotionEventAdapter()
    private val pointerInputEventProcessor = PointerInputEventProcessor(root)

    // TODO(mount): reinstate when coroutines are supported by IR compiler
    // private val ownerScope = CoroutineScope(Dispatchers.Main.immediate + Job())

    // Used for updating the ConfigurationAmbient when configuration changes - consume the
    // configuration ambient instead of changing this observer if you are writing a component that
    // adapts to configuration changes.
    override var configurationChangeObserver: (Configuration) -> Unit = {}

    private val _autofill = if (autofillSupported()) AndroidAutofill(this, autofillTree) else null

    // Used as an ambient for performing autofill.
    override val autofill: Autofill? get() = _autofill

    private var observationClearRequested = false

    /**
     * Provide clipboard manager to the user. Use the Android version of clipboard manager.
     */
    override val clipboardManager = AndroidClipboardManager(context)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        Log.d(FOCUS_TAG, "Owner FocusChanged($gainFocus)")
        with(_focusManager) {
            if (gainFocus) takeFocus() else releaseFocus()
        }
    }

    override fun sendKeyEvent(keyEvent: KeyEvent): Boolean {
        return keyInputModifier.processKeyInput(keyEvent)
    }

    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
        return sendKeyEvent(KeyEventAndroid(event))
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)

        if (hasWindowFocus) {
            accessibilityDelegate.clipBoardManagerText = clipboardManager.getText()
        }
    }

    private val snapshotObserver = SnapshotStateObserver { command ->
        if (handler.looper === Looper.myLooper()) {
            command()
        } else {
            handler.post(command)
        }
    }

    private val onCommitAffectingMeasure: (LayoutNode) -> Unit = { layoutNode ->
        if (layoutNode.isValid) {
            onRequestMeasure(layoutNode)
        }
    }

    private val onCommitAffectingLayout: (LayoutNode) -> Unit = { layoutNode ->
        if (layoutNode.isValid && measureAndLayoutDelegate.requestRelayout(layoutNode)) {
            scheduleMeasureAndLayout()
        }
    }

    private val onCommitAffectingLayer: (OwnedLayer) -> Unit = { layer ->
        if (layer.isValid) {
            layer.invalidate()
        }
    }

    private val onCommitAffectingLayerParams: (OwnedLayer) -> Unit = { layer ->
        if (layer.isValid) {
            handler.postAtFrontOfQueue {
                updateLayerProperties(layer)
            }
        }
    }

    @OptIn(InternalCoreApi::class)
    override var showLayoutBounds = false

    override fun pauseModelReadObserveration(block: () -> Unit) =
        snapshotObserver.pauseObservingReads(block)

    private val clearInvalidObservations: Runnable = Runnable {
        if (observationClearRequested) {
            observationClearRequested = false
            snapshotObserver.removeObservationsFor { !(it as OwnerScope).isValid }
        }
    }

    init {
        setWillNotDraw(false)
        isFocusable = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusable = View.FOCUSABLE
            // not to add the default focus highlight to the whole compose view
            defaultFocusHighlightEnabled = false
        }
        isFocusableInTouchMode = true
        clipChildren = false
        clipboardManager.addChangeListener {
            accessibilityDelegate.clipBoardManagerText = clipboardManager.getText()
        }
        ViewCompat.setAccessibilityDelegate(this, accessibilityDelegate)
        AndroidOwner.onAndroidOwnerCreatedCallback?.invoke(this)
    }

    override fun onAttach(node: LayoutNode) {
    }

    override fun onDetach(node: LayoutNode) {
        measureAndLayoutDelegate.onNodeDetached(node)
        requestClearInvalidObservations()
    }

    fun requestClearInvalidObservations() {
        if (!observationClearRequested) {
            observationClearRequested = true
            handler.postAtFrontOfQueue(clearInvalidObservations)
        }
    }

    private var _androidViewsHandler: AndroidViewsHandler? = null
    private val androidViewsHandler: AndroidViewsHandler
        get() {
            if (_androidViewsHandler == null) {
                _androidViewsHandler = AndroidViewsHandler(context)
                addView(_androidViewsHandler)
            }
            return _androidViewsHandler!!
        }
    private val viewLayersContainer by lazy(LazyThreadSafetyMode.NONE) {
        ViewLayerContainer(context).also { addView(it) }
    }

    override fun addAndroidView(view: View, layoutNode: LayoutNode) {
        androidViewsHandler.layoutNode[view] = layoutNode
        androidViewsHandler.addView(view)
    }

    override fun removeAndroidView(view: View) {
        androidViewsHandler.removeView(view)
        androidViewsHandler.layoutNode.remove(view)
    }

    // [ Layout block start ]

    // The constraints being used by the last onMeasure. It is set to null in onLayout. It allows
    // us to detect the case when the View was measured twice with different constraints within
    // the same measure pass.
    private var onMeasureConstraints: Constraints? = null

    // Will be set to true when we were measured twice with different constraints during the last
    // measure pass.
    private var wasMeasuredWithMultipleConstraints = false

    private val measureAndLayoutDelegate = MeasureAndLayoutDelegate(root)

    private var measureAndLayoutScheduled = false

    private val measureAndLayoutHandler: Handler =
        HandlerCompat.createAsync(Looper.getMainLooper()) {
            measureAndLayoutScheduled = false
            measureAndLayout()
            true
        }

    private fun scheduleMeasureAndLayout(nodeToRemeasure: LayoutNode? = null) {
        if (!isLayoutRequested && isAttachedToWindow) {
            if (wasMeasuredWithMultipleConstraints && nodeToRemeasure != null) {
                // if nodeToRemeasure can potentially resize the root and the view was measured
                // twice with different constraints last time it means the constraints we have could
                // be not the final constraints and in fact our parent ViewGroup can remeasure us
                // with larger constraints if we call requestLayout()
                var node = nodeToRemeasure
                while (node != null && node.measuredByParent == UsageByParent.InMeasureBlock) {
                    node = node.parent
                }
                if (node === root) {
                    requestLayout()
                    return
                }
            }
            if (!measureAndLayoutScheduled) {
                measureAndLayoutScheduled = true
                measureAndLayoutHandler.sendEmptyMessage(0)
            }
        }
    }

    override val measureIteration: Long get() = measureAndLayoutDelegate.measureIteration

    override fun measureAndLayout() {
        val rootNodeResized = measureAndLayoutDelegate.measureAndLayout()
        if (rootNodeResized) {
            requestLayout()
        }
        measureAndLayoutDelegate.dispatchOnPositionedCallbacks()
    }

    override fun onRequestMeasure(layoutNode: LayoutNode) {
        if (measureAndLayoutDelegate.requestRemeasure(layoutNode)) {
            scheduleMeasureAndLayout(layoutNode)
        }
    }

    override fun onRequestRelayout(layoutNode: LayoutNode) {
        if (measureAndLayoutDelegate.requestRelayout(layoutNode)) {
            scheduleMeasureAndLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        trace("AndroidOwner:onMeasure") {
            val (minWidth, maxWidth) = convertMeasureSpec(widthMeasureSpec)
            val (minHeight, maxHeight) = convertMeasureSpec(heightMeasureSpec)

            val constraints = Constraints(minWidth, maxWidth, minHeight, maxHeight)
            if (onMeasureConstraints == null) {
                // first onMeasure after last onLayout
                onMeasureConstraints = constraints
                wasMeasuredWithMultipleConstraints = false
            } else if (onMeasureConstraints != constraints) {
                // we were remeasured twice with different constraints after last onLayout
                wasMeasuredWithMultipleConstraints = true
            }
            measureAndLayoutDelegate.updateRootConstraints(constraints)
            if (isAttachedToWindow) {
                measureAndLayoutDelegate.measureAndLayout()
                setMeasuredDimension(root.width, root.height)
            } else {
                setMeasuredDimension(minWidth, minHeight)
            }
        }
    }

    private fun convertMeasureSpec(measureSpec: Int): Pair<Int, Int> {
        val mode = MeasureSpec.getMode(measureSpec)
        val size = MeasureSpec.getSize(measureSpec)
        return when (mode) {
            MeasureSpec.EXACTLY -> size to size
            MeasureSpec.UNSPECIFIED -> 0 to Constraints.Infinity
            MeasureSpec.AT_MOST -> 0 to size
            else -> throw IllegalStateException()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        onMeasureConstraints = null
        // we postpone onPositioned callbacks until onLayout as LayoutCoordinates
        // are currently wrong if you try to get the global(activity) coordinates -
        // View is not yet laid out.
        updatePositionCacheAndDispatch()
        if (_androidViewsHandler != null && androidViewsHandler.isLayoutRequested) {
            // Even if we laid out during onMeasure, this can happen when the Views hierarchy
            // receives forceLayout(). We need to relayout to clear the isLayoutRequested info
            // on the Views, as otherwise further layout requests will be discarded.
            androidViewsHandler.layout(0, 0, r - l, b - t)
        }
    }

    override val hasPendingMeasureOrLayout
        get() = measureAndLayoutDelegate.hasPendingMeasureOrLayout

    private var globalPosition: IntOffset = IntOffset.Zero

    private val tmpPositionArray = intArrayOf(0, 0)

    // Used to track whether or not there was an exception while creating an MRenderNode
    // so that we don't have to continue using try/catch after fails once.
    private var isRenderNodeCompatible = true

    private fun updatePositionCacheAndDispatch() {
        var positionChanged = false
        getLocationOnScreen(tmpPositionArray)
        if (globalPosition.x != tmpPositionArray[0] || globalPosition.y != tmpPositionArray[1]) {
            globalPosition = IntOffset(tmpPositionArray[0], tmpPositionArray[1])
            positionChanged = true
        }
        measureAndLayoutDelegate.dispatchOnPositionedCallbacks(forceDispatch = positionChanged)
    }

    // [ Layout block end ]

    override fun observeLayoutModelReads(node: LayoutNode, block: () -> Unit) {
        snapshotObserver.observeReads(node, onCommitAffectingLayout, block)
    }

    override fun observeMeasureModelReads(node: LayoutNode, block: () -> Unit) {
        snapshotObserver.observeReads(node, onCommitAffectingMeasure, block)
    }

    override fun <T : OwnerScope> observeReads(
        target: T,
        onChanged: (T) -> Unit,
        block: () -> Unit
    ) {
        snapshotObserver.observeReads(target, onChanged, block)
    }

    fun observeLayerModelReads(layer: OwnedLayer, block: () -> Unit) {
        snapshotObserver.observeReads(layer, onCommitAffectingLayer, block)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
    }

    override fun createLayer(
        drawLayerModifier: DrawLayerModifier,
        drawBlock: (Canvas) -> Unit,
        invalidateParentLayer: () -> Unit
    ): OwnedLayer {
        val layer = instantiateLayer(drawLayerModifier, drawBlock, invalidateParentLayer)
        updateLayerProperties(layer)
        return layer
    }

    private fun instantiateLayer(
        drawLayerModifier: DrawLayerModifier,
        drawBlock: (Canvas) -> Unit,
        invalidateParentLayer: () -> Unit
    ): OwnedLayer {
        // RenderNode is supported on Q+ for certain, but may also be supported on M-O.
        // We can't be confident that RenderNode is supported, so we try and fail over to
        // the ViewLayer implementation. We'll try even on on P devices, but it will fail
        // until ART allows things on the unsupported list on P.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isRenderNodeCompatible) {
            try {
                return RenderNodeLayer(
                    this,
                    drawLayerModifier,
                    drawBlock,
                    invalidateParentLayer
                )
            } catch (_: Throwable) {
                isRenderNodeCompatible = false
            }
        }
        return ViewLayer(
            this,
            viewLayersContainer,
            drawLayerModifier,
            drawBlock,
            invalidateParentLayer
        )
    }

    override fun onSemanticsChange() {
        accessibilityDelegate.onSemanticsChange()
    }

    private fun updateLayerProperties(layer: OwnedLayer) {
        snapshotObserver.observeReads(layer, onCommitAffectingLayerParams) {
            layer.updateLayerProperties()
        }
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        measureAndLayout()
        // we don't have to observe here because the root has a layer modifier
        // that will observe all children. The AndroidComposeView has only the
        // root, so it doesn't have to invalidate itself based on model changes.
        canvasHolder.drawInto(canvas) { root.draw(this) }

        if (dirtyLayers.isNotEmpty()) {
            for (i in 0 until dirtyLayers.size) {
                val layer = dirtyLayers[i]
                layer.updateDisplayList()
            }
            dirtyLayers.clear()
        }
    }

    override var viewTreeOwners: AndroidOwner.ViewTreeOwners? = null
        private set

    override fun setOnViewTreeOwnersAvailable(callback: (AndroidOwner.ViewTreeOwners) -> Unit) {
        val viewTreeOwners = viewTreeOwners
        if (viewTreeOwners != null) {
            callback(viewTreeOwners)
        } else {
            onViewTreeOwnersAvailable = callback
        }
    }

    private var onViewTreeOwnersAvailable: ((AndroidOwner.ViewTreeOwners) -> Unit)? = null

    // executed when the layout pass has been finished. as a result of it our view could be moved
    // inside the window (we are interested not only in the event when our parent positioned us
    // on a different position, but also in the position of each of the grandparents as all these
    // positions add up to final global position)
    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        updatePositionCacheAndDispatch()
    }

    // executed when a scrolling container like ScrollView of RecyclerView performed the scroll,
    // this could affect our global position
    private val scrollChangedListener = ViewTreeObserver.OnScrollChangedListener {
        updatePositionCacheAndDispatch()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        showLayoutBounds = getIsShowingLayoutBounds()
        snapshotObserver.enableStateUpdatesObserving(true)
        ifDebug { if (autofillSupported()) _autofill?.registerCallback() }
        root.attach(this)

        if (viewTreeOwners == null) {
            val lifecycleOwner = ViewTreeLifecycleOwner.get(this) ?: throw IllegalStateException(
                "Composed into the View which doesn't propagate ViewTreeLifecycleOwner!"
            )
            val viewModelStoreOwner =
                ViewTreeViewModelStoreOwner.get(this) ?: throw IllegalStateException(
                    "Composed into the View which doesn't propagate ViewTreeViewModelStoreOwner!"
                )
            val savedStateRegistryOwner =
                ViewTreeSavedStateRegistryOwner.get(this) ?: throw IllegalStateException(
                    "Composed into the View which doesn't propagate" +
                        "ViewTreeSavedStateRegistryOwner!"
                )
            val viewTreeOwners = AndroidOwner.ViewTreeOwners(
                lifecycleOwner = lifecycleOwner,
                viewModelStoreOwner = viewModelStoreOwner,
                savedStateRegistryOwner = savedStateRegistryOwner
            )
            this.viewTreeOwners = viewTreeOwners
            onViewTreeOwnersAvailable?.invoke(viewTreeOwners)
            onViewTreeOwnersAvailable = null
        }
        viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        viewTreeObserver.addOnScrollChangedListener(scrollChangedListener)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        snapshotObserver.clear()
        snapshotObserver.enableStateUpdatesObserving(false)
        ifDebug { if (autofillSupported()) _autofill?.unregisterCallback() }
        if (measureAndLayoutScheduled) {
            measureAndLayoutHandler.removeMessages(0)
        }
        root.detach()
        viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
        viewTreeObserver.removeOnScrollChangedListener(scrollChangedListener)
    }

    override fun onProvideAutofillVirtualStructure(structure: ViewStructure?, flags: Int) {
        if (autofillSupported() && structure != null) _autofill?.populateViewStructure(structure)
    }

    override fun autofill(values: SparseArray<AutofillValue>) {
        if (autofillSupported()) _autofill?.performAutofill(values)
    }

    // TODO(shepshapard): Test this method.
    override fun dispatchTouchEvent(motionEvent: MotionEvent): Boolean {
        measureAndLayout()
        // TODO(b/166848812): Calling updatePositionCacheAndDispatch here seems necessary because
        //  if the soft keyboard being displayed causes the AndroidComposeView to be offset from
        //  the screen, we don't seem to have any timely callback that updates our globalPosition
        //  cache. ViewTreeObserver.OnGlobalLayoutListener gets called, but not when the keyboard
        //  opens. And when it gets called as the keyboard is closing, it is called before the
        //  keyboard actually closes causing the globalPosition to be wrong.
        // TODO(shepshapard): There is no test to garuntee that this method is called here as doing
        //  so proved to be very difficult. A test should be added.
        updatePositionCacheAndDispatch()
        val processResult = trace("AndroidOwner:onTouch") {
            val pointerInputEvent = motionEventAdapter.convertToPointerInputEvent(motionEvent)
            if (pointerInputEvent != null) {
                pointerInputEventProcessor.process(pointerInputEvent)
            } else {
                pointerInputEventProcessor.processCancel()
                ProcessResult(
                    dispatchedToAPointerInputModifier = false,
                    anyMovementConsumed = false
                )
            }
        }

        if (processResult.anyMovementConsumed) {
            parent.requestDisallowInterceptTouchEvent(true)
        }

        return processResult.dispatchedToAPointerInputModifier
    }

    private val textInputServiceAndroid = TextInputServiceAndroid(this)

    override val textInputService =
        @OptIn(InternalTextApi::class)
        @Suppress("DEPRECATION_ERROR")
        textInputServiceFactory(textInputServiceAndroid)

    override val fontLoader: Font.ResourceLoader = AndroidFontResourceLoader(context)

    override var layoutDirection = context.resources.configuration.localeLayoutDirection
        private set

    /**
     * Provide haptic feedback to the user. Use the Android version of haptic feedback.
     */
    override val hapticFeedBack: HapticFeedback =
        AndroidHapticFeedback(this)

    /**
     * Provide textToolbar to the user, for text-related operation. Use the Android version of
     * floating toolbar(post-M) and primary toolbar(pre-M).
     */
    override val textToolbar: TextToolbar = AndroidTextToolbar(this)

    override fun onCheckIsTextEditor(): Boolean = textInputServiceAndroid.isEditorFocused()

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        textInputServiceAndroid.createInputConnection(outAttrs)

    override fun calculatePosition(): IntOffset = globalPosition

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        density = Density(context)
        layoutDirection = context.resources.configuration.localeLayoutDirection
        configurationChangeObserver(newConfig)
    }

    private fun autofillSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    public override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        return accessibilityDelegate.dispatchHoverEvent(event)
    }

    companion object {
        private var systemPropertiesClass: Class<*>? = null
        private var getBooleanMethod: Method? = null

        // TODO(mount): replace with ViewCompat.isShowingLayoutBounds() when it becomes available.
        @SuppressLint("PrivateApi")
        private fun getIsShowingLayoutBounds(): Boolean = try {
            if (systemPropertiesClass == null) {
                systemPropertiesClass = Class.forName("android.os.SystemProperties")
                getBooleanMethod = systemPropertiesClass?.getDeclaredMethod(
                    "getBoolean",
                    String::class.java,
                    Boolean::class.java
                )
            }
            getBooleanMethod?.invoke(null, "debug.layout", false) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Return the layout direction set by the [Locale][java.util.Locale].
 *
 * A convenience getter that translates [Configuration.getLayoutDirection] result into
 * [LayoutDirection] instance.
 */
internal val Configuration.localeLayoutDirection: LayoutDirection
    // We don't use the attached View's layout direction here since that layout direction may not
    // be resolved since the composables may be composed without attaching to the RootViewImpl.
    // In Jetpack Compose, use the locale layout direction (i.e. layoutDirection came from
    // configuration) as a default layout direction.
    get() = when (layoutDirection) {
        android.util.LayoutDirection.LTR -> LayoutDirection.Ltr
        android.util.LayoutDirection.RTL -> LayoutDirection.Rtl
        // Configuration#getLayoutDirection should only return a resolved layout direction, LTR
        // or RTL. Fallback to LTR for unexpected return value.
        else -> LayoutDirection.Ltr
    }
