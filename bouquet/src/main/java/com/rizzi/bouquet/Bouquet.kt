package com.rizzi.bouquet

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.google.accompanist.pager.ExperimentalPagerApi
import com.google.accompanist.pager.HorizontalPager
import com.rizzi.bouquet.network.getDownloadInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.URL

@Composable
fun VerticalPDFReader(
    state: VerticalPdfReaderState,
    modifier: Modifier
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        val ctx = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val density = LocalDensity.current
        val lazyState = state.lazyState
        DisposableEffect(key1 = Unit) {
            load(
                coroutineScope,
                ctx,
                state,
                constraints.maxWidth,
                constraints.maxHeight,
                true
            )
            onDispose {
                state.close()
            }
        }
        state.pdfRender?.let { pdf ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures(true) { centroid, pan, zoom, rotation ->
                            if (!state.mIsZoomEnable) return@detectTransformGestures
                            val nScale = (state.scale * zoom)
                                .coerceAtLeast(1f)
                                .coerceAtMost(3f)
                            val nOffset = if (nScale > 1f) {
                                val maxT =
                                    (constraints.maxWidth * state.scale) - constraints.maxWidth
                                Offset(
                                    x = (state.offset.x + pan.x).coerceIn(
                                        minimumValue = -maxT / 2,
                                        maximumValue = maxT / 2
                                    ),
                                    y = 0f
                                )
                            } else {
                                Offset(0f, 0f)
                            }
                            val scaleDiff = nScale - state.scale
                            val oldScale = state.scale
                            val scroll = lazyState.firstVisibleItemScrollOffset / oldScale
                            state.mScale = nScale
                            state.offset = nOffset
                            coroutineScope.launch {
                                lazyState.scrollBy((centroid.y + scroll / 2) * scaleDiff)
                            }
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                state = lazyState
            ) {
                items(pdf.pageCount) {
                    val pageContent = pdf.pageLists[it].stateFlow.collectAsState().value
                    DisposableEffect(key1 = Unit) {
                        pdf.pageLists[it].load()
                        onDispose {
                            pdf.pageLists[it].recycle()
                        }
                    }
                    val height = pageContent.bitmap.height * state.scale
                    val width = constraints.maxWidth * state.scale
                    PdfImage(
                        graphicsLayerData = {
                            GraphicsLayerData(
                                scale = state.scale,
                                translationX = state.offset.x,
                                translationY = state.offset.y
                            )
                        },
                        bitmap = {
                            pageContent.bitmap.asImageBitmap()
                        },
                        contentDescription = pageContent.contentDescription,
                        dimension = {
                            Dimension(
                                height = with(density) { height.toDp() },
                                width = with(density) { width.toDp() }
                            )
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalPagerApi::class)
@Composable
fun HorizontalPDFReader(
    state: HorizontalPdfReaderState,
    modifier: Modifier
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        val ctx = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val density = LocalDensity.current
        DisposableEffect(key1 = Unit) {
            load(
                coroutineScope,
                ctx,
                state,
                constraints.maxWidth,
                constraints.maxHeight,
                constraints.maxHeight > constraints.maxWidth
            )
            onDispose {
                state.close()
            }
        }
        state.pdfRender?.let { pdf ->
            HorizontalPager(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures(true) { centroid, pan, zoom, rotation ->
                            if (!state.mIsZoomEnable) return@detectTransformGestures
                            val nScale = (state.scale * zoom)
                                .coerceAtLeast(1f)
                                .coerceAtMost(3f)
                            val nOffset = if (nScale > 1f) {
                                val maxT = constraints.maxWidth * (state.scale - 1)
                                val maxH = constraints.maxHeight * (state.scale - 1)
                                Offset(
                                    x = (state.offset.x + pan.x).coerceIn(
                                        minimumValue = -maxT / 2,
                                        maximumValue = maxT / 2
                                    ),
                                    y = (state.offset.y + pan.y).coerceIn(
                                        minimumValue = -maxH / 2,
                                        maximumValue = maxH / 2
                                    )
                                )
                            } else {
                                Offset(0f, 0f)
                            }
                            state.mScale = nScale
                            state.offset = nOffset
                        }
                    },
                count = state.pdfPageCount,
                state = state.pagerState,
                userScrollEnabled = state.scale == 1f
            ) { page ->
                val pageContent = pdf.pageLists[page].stateFlow.collectAsState().value
                DisposableEffect(key1 = Unit) {
                    pdf.pageLists[page].load()
                    onDispose {
                        pdf.pageLists[page].recycle()
                    }
                }
                val height = pageContent.bitmap.height * state.scale
                val width = constraints.maxWidth * state.scale
                PdfImage(
                    graphicsLayerData = {
                        if (page == state.currentPage) {
                            GraphicsLayerData(
                                scale = state.scale,
                                translationX = state.offset.x,
                                translationY = state.offset.y
                            )
                        } else {
                            GraphicsLayerData(
                                scale = 1f,
                                translationX = 0f,
                                translationY = 0f
                            )
                        }
                    },
                    bitmap = { pageContent.bitmap.asImageBitmap() },
                    contentDescription = pageContent.contentDescription,
                    dimension = {
                        Dimension(
                            height = with(density) { height.toDp() },
                            width = with(density) { width.toDp() }
                        )
                    }
                )
            }
        }
    }
}


private fun load(
    coroutineScope: CoroutineScope,
    context: Context,
    state: PdfReaderState,
    width: Int,
    height: Int,
    portrait: Boolean
) {
    runCatching {
        if (state.isLoaded) {
            coroutineScope.launch(Dispatchers.IO) {
                val pFD =
                    ParcelFileDescriptor.open(state.mFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val textForEachPage = if (state.isAccessibleEnable) getTextByPage(context, pFD) else emptyList()
                state.pdfRender = BouquetPdfRender(pFD, textForEachPage, width, height, portrait)
            }
        } else {
            when (val res = state.resource) {
                is ResourceType.Local -> {
                    coroutineScope.launch(Dispatchers.IO) {
                        context.contentResolver.openFileDescriptor(res.uri, "r")?.let {
                            val textForEachPage = if (state.isAccessibleEnable) {
                                getTextByPage(context, it)
                            } else emptyList()
                            state.pdfRender = BouquetPdfRender(it, textForEachPage, width, height, portrait)
                            state.mFile = context.uriToFile(res.uri)
                        } ?: run {
                            state.mError = IOException("File not found")
                        }
                    }
                }
                is ResourceType.Remote -> {
                    coroutineScope.launch(Dispatchers.IO) {
                        runCatching {
                            val bufferSize = 8192
                            var downloaded = 0
                            val file = File(context.cacheDir, generateFileName())
                            val response = getDownloadInterface(
                                res.headers
                            ).downloadFile(
                                res.url
                            )
                            val byteStream = response.byteStream()
                            byteStream.use { input ->
                                file.outputStream().use { output ->
                                    val totalBytes = response.contentLength()
                                    var data = ByteArray(bufferSize)
                                    var count = input.read(data)
                                    while (count != -1) {
                                        if (totalBytes > 0) {
                                            downloaded += bufferSize
                                            state.mLoadPercent =
                                                (downloaded * (100 / totalBytes.toFloat())).toInt()
                                        }
                                        output.write(data, 0, count)
                                        data = ByteArray(bufferSize)
                                        count = input.read(data)
                                    }
                                }
                            }
                            val pFD = ParcelFileDescriptor.open(
                                file,
                                ParcelFileDescriptor.MODE_READ_ONLY
                            )
                            val textForEachPage = if (state.isAccessibleEnable) {
                                getTextByPage(context, pFD)
                            } else emptyList()
                            state.pdfRender = BouquetPdfRender(pFD, textForEachPage, width, height, portrait)
                            state.mFile = file
                        }.onFailure {
                            state.mError = it
                        }
                    }
                }
                is ResourceType.Base64 -> {
                    coroutineScope.launch(Dispatchers.IO) {
                        runCatching {
                            val file = context.base64ToPdf(res.file)
                            val pFD = ParcelFileDescriptor.open(
                                file,
                                ParcelFileDescriptor.MODE_READ_ONLY
                            )
                            val textForEachPage = if (state.isAccessibleEnable) {
                                getTextByPage(context, pFD)
                            } else emptyList()
                            state.pdfRender = BouquetPdfRender(pFD, textForEachPage, width, height, portrait)
                            state.mFile = file
                        }.onFailure {
                            state.mError = it
                        }
                    }
                }
            }
        }
    }.onFailure {
        state.mError = it
    }
}
