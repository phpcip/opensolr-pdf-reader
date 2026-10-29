package com.opensolr.pdf.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.opensolr.pdf.BuildConfig
import com.opensolr.pdf.MainActivity
import com.opensolr.pdf.R
import com.opensolr.pdf.ShareTargets
import com.opensolr.pdf.doc.PdfDoc
import kotlinx.coroutines.launch

private val Accent = Color(0xFFC05520)
private val Ink = Color(0xFF111111)
private val Muted = Color(0xFF4A4540)
private val Line = Color(0xFFD9D4CC)
private val Band = Color(0xFFFBFAF8)
private val Paper = Color(0xFFFFFFFF)

@Composable
fun App(activity: MainActivity) {
    val doc = activity.doc
    if (doc == null) Home(activity) else Viewer(activity, doc)
}

// ---------------- start screen ----------------

@Composable
private fun Home(activity: MainActivity) {
    val view = LocalView.current
    LaunchedEffect(Unit) { bars(view, true) }
    val top = max(WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), 24.dp)
    val bottom = max(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), 28.dp)
    Column(
        Modifier.fillMaxSize().background(Paper).padding(start = 24.dp, end = 24.dp, top = top + 24.dp, bottom = bottom),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.app_name), fontFamily = Fonts.grotesk, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.home_hint), fontFamily = Fonts.grotesk, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Muted)
        Spacer(Modifier.height(28.dp))
        AccentButton(stringResource(R.string.open_pdf)) { activity.pickFile() }
        activity.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(it), fontFamily = Fonts.grotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Accent)
        }
        Spacer(Modifier.weight(1f))
        if (com.opensolr.pdf.update.Updates.AVAILABLE && !com.opensolr.pdf.update.Updates.fromStore(activity)) UpdateRow(activity)
        else Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), fontFamily = Fonts.grotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Muted)
    }
}

@Composable
private fun UpdateRow(activity: MainActivity) {
    val u = activity.update
    Column(Modifier.fillMaxWidth().background(Band, RoundedCornerShape(2.dp)).border(1.dp, Line, RoundedCornerShape(2.dp)).padding(14.dp)) {
        Text(
            if (u != null) stringResource(R.string.update_available, u.version) else stringResource(R.string.version, BuildConfig.VERSION_NAME),
            fontFamily = Fonts.grotesk, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink,
        )
        val progress = activity.updateProgress
        val message = activity.updateMessage
        if (progress != null) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.updating, progress), fontFamily = Fonts.grotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Muted)
        } else if (message != null) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(message), fontFamily = Fonts.grotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Muted)
        }
        Spacer(Modifier.height(10.dp))
        if (u != null) AccentButton(stringResource(R.string.update_install)) { activity.installUpdate() }
        else GhostButton(stringResource(if (activity.checking) R.string.checking else R.string.check_updates)) { activity.checkForUpdate(true) }
    }
}

// ---------------- the document ----------------

@Composable
private fun Viewer(activity: MainActivity, doc: PdfDoc) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var chrome by remember { mutableStateOf(false) }
    var pageNow by remember(doc) { mutableIntStateOf(0) }
    var selection by remember(doc) { mutableStateOf<String?>(null) }
    var selTop by remember(doc) { mutableStateOf(0f) }
    var selBottom by remember(doc) { mutableStateOf(0f) }
    var reading by remember(doc) { mutableStateOf(false) }
    var targets by remember { mutableStateOf(Targets.read(context)) }
    var pdfView by remember { mutableStateOf<PdfView?>(null) }

    // the apps sharing goes to change while the app is away: read again on every return
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) targets = Targets.read(context) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(chrome) { bars(view, chrome) }
    BackHandler {
        when {
            selection != null -> pdfView?.clearSelection()
            else -> activity.back()
        }
    }

    fun share(target: ShareTargets.Target?) {
        Haptics.tap(view)
        scope.launch {
            val uri = activity.shareUri() ?: return@launch
            if (target == null) ShareTargets.chooser(context, uri, context.getString(R.string.share)) else ShareTargets.sendTo(context, uri, target)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFFE9E6E1))) {
        AndroidView(
            factory = { ctx ->
                PdfView(ctx).also { v ->
                    pdfView = v
                    v.listener = object : PdfView.Listener {
                        override fun onTap() { chrome = !chrome }
                        override fun onPage(page: Int, count: Int) { pageNow = page }
                        override fun onSelection(text: String?, top: Float, bottom: Float) { selection = text; selTop = top; selBottom = bottom }
                        override fun onBusy(busy: Boolean) { reading = busy }
                        override fun onNoText() { Toast.makeText(ctx, R.string.no_text, Toast.LENGTH_SHORT).show() }
                        override fun onLongPress() { Haptics.tick(v, true) }
                    }
                    v.doc = doc
                }
            },
            update = { it.doc = doc },
            modifier = Modifier.fillMaxSize(),
        )

        val bottom = max(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), 28.dp)

        if (reading) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = max(WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), 24.dp) + 8.dp)) {
                Pill(stringResource(R.string.reading_text))
            }
        }

        // Copy and Whole page float above the selection, or under it when there is no room above
        selection?.let { text ->
            val density = androidx.compose.ui.platform.LocalDensity.current
            val topInset = max(WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), 24.dp)
            val barH = 44.dp
            val gapPx = with(density) { 10.dp.toPx() }
            val above = selTop - gapPx - with(density) { barH.toPx() }
            val y = if (above > with(density) { topInset.toPx() }) above else selBottom + gapPx
            Row(
                Modifier.align(Alignment.TopCenter).offset { androidx.compose.ui.unit.IntOffset(0, y.toInt()) },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AccentButton(stringResource(R.string.copy), compact = true) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(activity.name, text))
                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                    pdfView?.clearSelection()
                }
                GhostButton(stringResource(R.string.select_page), compact = true, filled = true) { pdfView?.selectPage() }
            }
        }

        if (chrome) {
            val topInset = max(WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), 24.dp)
            Row(
                Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = topInset + 6.dp)
                    .background(Band, RoundedCornerShape(2.dp)).border(1.dp, Line, RoundedCornerShape(2.dp))
                    .clickable { Haptics.tap(view); activity.back() }
                    .padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(R.drawable.ic_back), contentDescription = null, modifier = Modifier.size(22.dp))
                Text(
                    activity.caller ?: stringResource(R.string.back),
                    fontFamily = Fonts.grotesk, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Accent,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp),
                )
            }
        }

        // the page on screen and the file's name, discreet, at the top right; a tap on the number jumps to a page
        if (chrome) {
            val topInset = max(WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), 24.dp)
            Column(
                Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = topInset + 6.dp).widthIn(max = 180.dp)
                    .background(Band, RoundedCornerShape(2.dp)).border(1.dp, Line, RoundedCornerShape(2.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PageJump(pageNow + 1, doc.pageCount) { n -> pdfView?.goToPage(n - 1) }
                Text(
                    activity.name, fontFamily = Fonts.grotesk, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (chrome) {
                Row(
                    Modifier.fillMaxWidth().background(Band).border(1.dp, Line).padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = bottom),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.page_of, pageNow + 1, doc.pageCount),
                        fontFamily = Fonts.grotesk, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    // read from the right edge: Add to Dropbox, the two apps shared to most, then Share
                    IconButton(R.drawable.ic_share, stringResource(R.string.share)) { share(null) }
                    targets.top.reversed().forEach { t -> AppIcon(t) { share(t) } }
                    targets.dropbox?.let { t -> AppIcon(t) { share(t) } }
                }
            }
        }
    }
}

/** Add to Dropbox (when the Dropbox app is installed) and the two apps shared to most. */
private class Targets(val dropbox: ShareTargets.Target?, val top: List<ShareTargets.Target>) {
    companion object {
        fun read(context: Context) = Targets(ShareTargets.dropbox(context), ShareTargets.top(context))
    }
}

/** System bars only with the controls; the pages get the whole screen otherwise. */
private fun bars(view: View, show: Boolean) {
    val window = (view.context as? android.app.Activity)?.window ?: return
    val c = WindowCompat.getInsetsController(window, view)
    c.isAppearanceLightStatusBars = true
    c.isAppearanceLightNavigationBars = true
    if (show) c.show(WindowInsetsCompat.Type.systemBars())
    else {
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }
}

// ---------------- controls ----------------

@Composable
private fun AccentButton(label: String, compact: Boolean = false, onClick: () -> Unit) {
    val view = LocalView.current
    Box(
        Modifier.then(if (compact) Modifier else Modifier.fillMaxWidth()).height(if (compact) 44.dp else 50.dp)
            .background(Accent, RoundedCornerShape(2.dp)).border(1.dp, Accent, RoundedCornerShape(2.dp))
            .clickable { Haptics.tick(view, true); onClick() }.padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Fonts.grotesk, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Paper, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GhostButton(label: String, compact: Boolean = false, filled: Boolean = false, onClick: () -> Unit) {
    val view = LocalView.current
    Box(
        Modifier.then(if (compact) Modifier else Modifier.fillMaxWidth()).height(44.dp)
            .background(if (filled) Paper else Color.Transparent, RoundedCornerShape(2.dp)).border(1.dp, Ink, RoundedCornerShape(2.dp))
            .clickable { Haptics.tap(view); onClick() }.padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Fonts.grotesk, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink)
    }
}

@Composable
private fun IconButton(icon: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).background(Paper, RoundedCornerShape(2.dp)).border(1.dp, Line, RoundedCornerShape(2.dp))
            .clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AppIcon(target: ShareTargets.Target, onClick: () -> Unit) {
    val bitmap = remember(target.component) { target.icon.toBitmap(96, 96).asImageBitmap() }
    Box(
        Modifier.size(44.dp).background(Paper, RoundedCornerShape(2.dp)).border(1.dp, Line, RoundedCornerShape(2.dp))
            .clickable(onClick = onClick).semantics { contentDescription = target.label },
        contentAlignment = Alignment.Center,
    ) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun Pill(text: String) {
    Box(Modifier.background(Paper, RoundedCornerShape(2.dp)).border(1.dp, Line, RoundedCornerShape(2.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, fontFamily = Fonts.grotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Ink)
    }
}

/**
 * "3 / 6"; a tap turns it into a small box for a page number and Go (or the keyboard's Go / Enter), which
 * jumps there. Leaving the box without Go puts the number back.
 */
@Composable
private fun PageJump(page: Int, count: Int, onGo: (Int) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val style = androidx.compose.ui.text.TextStyle(fontFamily = Fonts.grotesk, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Ink)
    fun go() {
        typed.text.toIntOrNull()?.let { onGo(it.coerceIn(1, count.coerceAtLeast(1))) }
        keyboard?.hide()
        editing = false
    }
    if (!editing) {
        Text(
            stringResource(R.string.page_of, page, count), style = style,
            modifier = Modifier.clickable { val t = page.toString(); typed = androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(0, t.length)); editing = true },
        )
        return
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    var had by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.foundation.text.BasicTextField(
            value = typed, onValueChange = { v -> typed = v.copy(text = v.text.filter { it.isDigit() }.take(5)) },
            singleLine = true, textStyle = style.copy(textAlign = TextAlign.Center), cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Go),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { go() }),
            modifier = Modifier.width(48.dp).background(Paper, RoundedCornerShape(2.dp)).border(1.dp, Line, RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 2.dp)
                .onPreviewKeyEvent { e -> if (e.key == Key.Enter || e.key == Key.NumPadEnter) { if (e.type == KeyEventType.KeyUp) go(); true } else false }
                .focusRequester(focus).onFocusChanged { if (it.isFocused) had = true else if (had) editing = false },
        )
        Text("/ $count", style = style)
        Box(Modifier.border(1.dp, Ink, RoundedCornerShape(2.dp)).clickable { go() }.padding(horizontal = 8.dp, vertical = 2.dp)) { Text("Go", style = style) }
    }
}
