package com.hana.reader.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hana.reader.auth.GoogleAuth
import com.hana.reader.auth.GoogleSignInOutcome
import com.hana.reader.data.Book
import com.hana.reader.data.Chapter
import com.hana.reader.data.EpubImport
import com.hana.reader.data.ProgressStore
import com.hana.reader.data.TextUtil
import com.hana.reader.tts.HanaPlayer
import com.hana.reader.tts.TtsPacks
import com.hana.reader.tts.VoiceCatalog
import com.hana.reader.tts.VoiceProfile
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

@Composable
fun HanaApp() {
    val context = LocalContext.current
    val store = remember { ProgressStore(context) }
    var session by remember { mutableStateOf(store.session()) }
    // Drive the gate with Compose state so we leave LoginScreen in the same frame
    // after a successful commit — do not rely only on re-reading prefs.
    var signedIn by remember { mutableStateOf(store.signedIn()) }
    val nav = rememberNavController()
    HanaTheme {
        if (!signedIn) {
            LoginScreen(
                onGoogle = { email, photo ->
                    if (store.saveGoogle(email, photo)) {
                        session = store.session()
                        signedIn = true
                    }
                },
                onLocal = {
                    if (store.saveLocal()) {
                        session = store.session()
                        signedIn = true
                    }
                }
            )
        } else {
            Box(Modifier.fillMaxSize().background(Paper)) {
                NavHost(navController = nav, startDestination = "library") {
                    composable("library") { LibraryScreen(store, nav, session.email) }
                    composable("voices") { VoicesScreen(nav) }
                    composable("read/{id}") { entry ->
                        val id = entry.arguments?.getString("id").orEmpty()
                        val book = store.book(id)
                        if (book != null) ReaderScreen(book, store, nav) else nav.popBackStack()
                    }
                }
                MiniPlayer(nav, Modifier.align(Alignment.BottomCenter))
                VoiceBusyOverlay()
            }
        }
    }
}

@Composable
private fun LoginScreen(
    onGoogle: (String, String?) -> Unit,
    onLocal: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var localLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Surface(
            modifier = Modifier.size(88.dp, 116.dp),
            color = Color(0xFFD9B7A4),
            shape = RoundedCornerShape(10.dp),
            shadowElevation = 8.dp
        ) {
            Box(Modifier.padding(12.dp), contentAlignment = Alignment.BottomStart) {
                Text("Hana", color = Color(0xFF3A2A24), fontFamily = FontFamily.Serif, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("Listen first.", fontFamily = FontFamily.Serif, fontSize = 32.sp, color = Ink)
        Spacer(Modifier.height(10.dp))
        Text(
            "A calm voice for books you would rather hear than stare at.",
            color = Muted,
            fontSize = 15.sp
        )
        Spacer(Modifier.height(36.dp))
        Button(
            onClick = {
                val activity = GoogleAuth.findActivity(context) ?: return@Button
                loading = true
                error = null
                scope.launch {
                    when (val out = GoogleAuth.signIn(activity)) {
                        is GoogleSignInOutcome.Success -> onGoogle(out.email, out.profilePicUrl)
                        is GoogleSignInOutcome.Canceled ->
                            error = "Sign-in didn't finish. You can continue on this phone."
                        is GoogleSignInOutcome.Failed -> error = out.message
                    }
                    loading = false
                }
            },
            enabled = !loading && !localLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Rose, contentColor = PaperElevated),
            shape = CircleShape
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = PaperElevated, strokeWidth = 2.dp)
            else Text("Continue with Google", fontWeight = FontWeight.Medium)
        }
        TextButton(
            onClick = {
                if (loading || localLoading) return@TextButton
                localLoading = true
                onLocal()
                localLoading = false
            },
            enabled = !loading && !localLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (localLoading) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Ink, strokeWidth = 2.dp)
            } else {
                Text("Continue on this phone", color = Ink, fontWeight = FontWeight.Medium)
            }
        }
        if (error != null) {
            Text(error!!, color = Rose, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(
            "Progress is saved on this device. Google keeps it tied to you.",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 28.dp)
        )
    }
}

@Composable
private fun LibraryScreen(store: ProgressStore, nav: NavHostController, email: String?) {
    val context = LocalContext.current
    var books by remember { mutableStateOf(store.allBooks()) }
    // CONTINUE only for real saved progress on an imported book.
    val continueBook = remember(books) { store.latest()?.let { store.book(it.bookId) } }
    val player = HanaPlayer.get(context)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                books = store.allBooks()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // No Library auto warmPrepare / Smooth download — wait for Listen or Voices.
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }
    var pendingRename by remember { mutableStateOf<Book?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val activity = GoogleAuth.findActivity(context) ?: return@rememberLauncherForActivityResult
            runCatching { importUri(activity, uri, store) }
                .onSuccess { book ->
                    error = null
                    books = store.allBooks()
                    nav.navigate("read/${book.id}")
                }
                .onFailure { error = it.message ?: "Could not import that file." }
        }
    }
    val openImport = {
        picker.launch(arrayOf(
            "application/epub+zip",
            "text/plain",
            "text/markdown",
            "text/x-markdown"
        ))
    }
    fun confirmDelete(book: Book) {
        if (player.state.value.book?.id == book.id) player.releaseBook(book.id)
        if (store.removeImported(book.id)) {
            books = store.allBooks()
        }
        pendingDelete = null
    }
    fun confirmRename(book: Book, title: String) {
        if (store.renameImported(book.id, title)) {
            books = store.allBooks()
        }
        pendingRename = null
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("HANA", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (email != null) email.substringBefore("@") else "Library",
                    fontFamily = FontFamily.Serif,
                    fontSize = 28.sp,
                    color = Ink
                )
            }
            IconButton(onClick = openImport) {
                Icon(Icons.Default.Add, contentDescription = "Add your book", tint = Ink)
            }
            IconButton(onClick = { nav.navigate("voices") }) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = "Voices", tint = Ink)
            }
        }
        // Visible full-width divider under the top bar (cream-safe contrast; 1dp solid ink).
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Ink.copy(alpha = 0.32f))
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            if (error != null) {
                Text(error!!, color = Rose, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
            if (continueBook != null) {
                Surface(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .clickable { nav.navigate("read/${continueBook.id}") },
                    color = PaperElevated,
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 1.dp
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("CONTINUE", color = Muted, fontSize = 10.sp, letterSpacing = 1.4.sp)
                            Text(
                                continueBook.title,
                                fontFamily = FontFamily.Serif,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = {
                                player.play(continueBook)
                                nav.navigate("read/${continueBook.id}")
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Headphones, contentDescription = "Listen", tint = Rose)
                        }
                    }
                }
            }
            if (books.isEmpty()) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Perpustakaan masih kosong.",
                        fontFamily = FontFamily.Serif,
                        fontSize = 20.sp,
                        color = Ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        "Ketuk + untuk menambahkan buku — EPUB, TXT, atau Markdown.\nYour library is quiet. Tap + to add a book.",
                        color = Muted,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            } else {
                Text(
                    "BOOKS",
                    color = Muted,
                    fontSize = 11.sp,
                    letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
                        LibraryBookListItem(
                            number = index + 1,
                            book = book,
                            canManage = store.isImported(book.id),
                            onOpen = { nav.navigate("read/${book.id}") },
                            onRequestRename = {
                                pendingRename = book
                                renameDraft = book.title
                            },
                            onRequestDelete = { pendingDelete = book }
                        )
                    }
                }
            }
        }
    }
    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Hapus buku?") },
            text = {
                Text("“${book.title}” akan dihapus dari perpustakaan, beserta progresnya.")
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete(book) }) {
                    Text("Hapus", color = Rose)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Batal", color = Muted)
                }
            }
        )
    }
    pendingRename?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("Ganti nama") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true,
                    label = { Text("Judul") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Ink.copy(alpha = 0.45f),
                        unfocusedBorderColor = Ink.copy(alpha = 0.22f),
                        focusedLabelColor = Muted,
                        unfocusedLabelColor = Muted,
                        cursorColor = Ink,
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmRename(book, renameDraft) },
                    enabled = renameDraft.trim().isNotEmpty()
                ) {
                    Text("Simpan", color = Rose)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) {
                    Text("Batal", color = Muted)
                }
            }
        )
    }
}

@Composable
private fun LibraryBookListItem(
    number: Int,
    book: Book,
    canManage: Boolean,
    onOpen: () -> Unit,
    onRequestRename: () -> Unit,
    onRequestDelete: () -> Unit
) {
    LibraryBookRow(
        number = number,
        book = book,
        canManage = canManage,
        onOpen = onOpen,
        onRequestRename = onRequestRename,
        onRequestDelete = onRequestDelete
    )
}

@Composable
private fun LibraryBookRow(
    number: Int,
    book: Book,
    canManage: Boolean,
    onOpen: () -> Unit,
    onRequestRename: () -> Unit,
    onRequestDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "%d".format(number),
            color = Muted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(28.dp)
        )
        Text(
            book.title,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontFamily = FontFamily.Serif,
            fontSize = 17.sp,
            color = Ink
        )
        if (canManage) {
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Book options",
                        tint = Muted
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuOpen = false
                            onRequestRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = Rose) },
                        onClick = {
                            menuOpen = false
                            onRequestDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderScreen(book: Book, store: ProgressStore, nav: NavHostController) {
    val context = LocalContext.current
    val player = HanaPlayer.get(context)
    val snap by player.state.collectAsState()
    var displayBook by remember(book.id) { mutableStateOf(book) }
    val saved = store.get(displayBook.id)
    var night by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(false) }
    var pendingRename by remember { mutableStateOf(false) }
    var renameDraft by remember { mutableStateOf(book.title) }
    val canManage = store.isImported(displayBook.id)
    // No OfflineTts / prebuffer on open — show text ASAP; prepare only on Listen / Voices.
    val bg = if (night) Color(0xFF161310) else Paper
    val fg = if (night) Color(0xFFF3ECE3) else Ink
    val divider = if (night) Color.White.copy(alpha = 0.22f) else Ink.copy(alpha = 0.32f)
    // Honest highlight: no warm-ink jump (sentence sync is not word-accurate).
    // Auto-scroll to the active sentence remains below.
    val listState = rememberLazyListState()
    val chapterSentences = remember(displayBook.id, displayBook.chapters) {
        displayBook.chapters.map { chapter -> TextUtil.splitSentences(chapter.body) }
    }
    // Flat LazyColumn indices: 0 = header; each chapter = title + sentences.
    val sentenceListIndex = remember(chapterSentences) {
        val firstSentenceIndex = IntArray(chapterSentences.size)
        var idx = 1 // after header
        chapterSentences.forEachIndexed { i, sentences ->
            firstSentenceIndex[i] = idx + 1 // skip chapter title item
            idx += 1 + sentences.size
        }
        firstSentenceIndex
    }
    val isThisBook = snap.book?.id == displayBook.id
    LaunchedEffect(isThisBook, snap.chapterIndex, snap.sentenceIndex, snap.playing) {
        if (!isThisBook) return@LaunchedEffect
        val ci = snap.chapterIndex
        val si = snap.sentenceIndex
        if (ci !in chapterSentences.indices) return@LaunchedEffect
        if (si !in chapterSentences[ci].indices) return@LaunchedEffect
        val target = sentenceListIndex[ci] + si
        runCatching { listState.animateScrollToItem(target) }
    }
    fun confirmRename(title: String) {
        if (store.renameImported(displayBook.id, title)) {
            val refreshed = store.book(displayBook.id) ?: displayBook.copy(title = title.trim())
            displayBook = refreshed
            player.refreshBookMetadata(refreshed)
        }
        pendingRename = false
    }
    fun confirmDelete() {
        if (player.state.value.book?.id == displayBook.id) player.releaseBook(displayBook.id)
        if (store.removeImported(displayBook.id)) {
            pendingDelete = false
            nav.popBackStack()
        } else {
            pendingDelete = false
        }
    }
    Column(Modifier.fillMaxSize().background(bg).statusBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 4.dp)
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = fg)
            }
            Text(
                displayBook.title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = fg,
                fontWeight = FontWeight.Medium
            )
            if (canManage) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Book options",
                            tint = Muted
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                menuOpen = false
                                renameDraft = displayBook.title
                                pendingRename = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = Rose) },
                            onClick = {
                                menuOpen = false
                                pendingDelete = true
                            }
                        )
                    }
                }
            }
            TextButton(onClick = { night = !night }) {
                Text(if (night) "Paper" else "Night", color = Muted)
            }
        }
        // Full-width bottom border under back / title / ⋯ / Night (matches Library strength).
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(divider)
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = 160.dp)
        ) {
            item(key = "header-${displayBook.id}") {
                Row(Modifier.padding(vertical = 16.dp)) {
                    Cover(displayBook, Modifier.size(72.dp, 96.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(displayBook.title, fontFamily = FontFamily.Serif, fontSize = 26.sp, color = fg)
                        Text(displayBook.author, color = Muted, fontSize = 14.sp)
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                if (snap.book?.id == displayBook.id && snap.playing) player.pause()
                                else player.play(displayBook, saved?.chapterIndex, saved?.sentenceIndex)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Rose),
                            shape = CircleShape,
                            enabled = !snap.busy
                        ) {
                            val listening = snap.book?.id == displayBook.id && snap.playing
                            val waitLabel = snap.status?.takeIf { listening && (
                                it.contains("Starting", true) ||
                                    it.contains("Getting first", true) ||
                                    it.contains("Synthesizing", true) ||
                                    it.contains("Preparing", true) ||
                                    it.contains("Downloading", true)
                                ) }
                            Icon(
                                if (listening && waitLabel == null) Icons.Default.Pause else Icons.Default.Headphones,
                                null,
                                Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    waitLabel != null -> waitLabel
                                    listening -> "Pause"
                                    else -> "Listen with Hana"
                                }
                            )
                        }
                    }
                }
            }
            displayBook.chapters.forEachIndexed { ci, chapter ->
                item(key = "${displayBook.id}-ch-$ci-title-${chapter.id}") {
                    Text(
                        chapter.title,
                        fontFamily = FontFamily.Serif,
                        fontSize = 20.sp,
                        color = fg,
                        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
                    )
                }
                chapterSentences[ci].forEachIndexed { si, sentence ->
                    item(key = "${displayBook.id}-ch-$ci-s-$si") {
                        Text(
                            text = sentence,
                            color = fg,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Normal,
                            fontSize = 18.sp,
                            lineHeight = 30.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .clickable(enabled = !snap.busy) {
                                    player.play(displayBook, ci, si)
                                }
                        )
                    }
                }
            }
        }
    }
    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("Hapus buku?") },
            text = {
                Text("“${displayBook.title}” akan dihapus dari perpustakaan, beserta progresnya.")
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete() }) {
                    Text("Hapus", color = Rose)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) {
                    Text("Batal", color = Muted)
                }
            }
        )
    }
    if (pendingRename) {
        AlertDialog(
            onDismissRequest = { pendingRename = false },
            title = { Text("Ganti nama") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true,
                    label = { Text("Judul") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Ink.copy(alpha = 0.45f),
                        unfocusedBorderColor = Ink.copy(alpha = 0.22f),
                        focusedLabelColor = Muted,
                        unfocusedLabelColor = Muted,
                        cursorColor = Ink,
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmRename(renameDraft) },
                    enabled = renameDraft.trim().isNotEmpty()
                ) {
                    Text("Simpan", color = Rose)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = false }) {
                    Text("Batal", color = Muted)
                }
            }
        )
    }
}

@Composable
private fun MiniPlayer(nav: NavHostController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = HanaPlayer.get(context)
    val snap by player.state.collectAsState()
    val book = snap.book ?: return
    val scope = rememberCoroutineScope()
    val prefs = remember { player.voicePreferences() }
    val lang = book.language
    val voices = remember(lang) { VoiceCatalog.forLanguage(lang) }
    val selectedId = prefs.selectedVoiceId(lang)
    val switching = snap.busy
    var showVoiceSettings by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(12.dp),
        color = PaperElevated,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(
                    book,
                    Modifier
                        .size(40.dp, 52.dp)
                        .clickable { nav.navigate("read/${book.id}") }
                )
                Spacer(Modifier.width(10.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { nav.navigate("read/${book.id}") }
                ) {
                    Text(book.title, maxLines = 1, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    val selectedVoice = VoiceCatalog.find(selectedId)
                    val caption = TtsPacks.playerCaption(
                        language = lang,
                        profile = snap.profile,
                        usingNeural = snap.usingNeural,
                        downloading = snap.downloadProgress != null,
                        status = snap.status,
                        selectedVoiceName = selectedVoice?.name,
                        selectedVoiceId = selectedVoice?.id
                    )
                    Text(
                        caption,
                        color = Muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = !switching) {
                            player.setProfile(
                                if (snap.profile == VoiceProfile.Hana) VoiceProfile.Clear else VoiceProfile.Hana
                            )
                        }
                    )
                    snap.downloadProgress?.let { p ->
                        Text(
                            "${(p * 100).toInt()}%",
                            color = Rose,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        LinearProgressIndicator(
                            progress = { p },
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .fillMaxWidth(),
                            color = Rose,
                            trackColor = Subtle
                        )
                    }
                }
                IconButton(onClick = { player.skipChapter(-1) }, enabled = !switching) {
                    Icon(Icons.Default.SkipPrevious, "Previous", tint = Muted)
                }
                IconButton(
                    onClick = { player.toggle() },
                    enabled = !switching,
                    modifier = Modifier
                        .size(44.dp)
                        .background(Ink, CircleShape)
                ) {
                    Icon(
                        if (snap.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (snap.playing) "Pause" else "Play",
                        tint = Paper
                    )
                }
                IconButton(onClick = { player.skipChapter(1) }, enabled = !switching) {
                    Icon(Icons.Default.SkipNext, "Next", tint = Muted)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                voices.forEach { voice ->
                    val on = voice.id == selectedId && snap.profile == VoiceProfile.Hana
                    Text(
                        voice.label,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (on) Rose.copy(alpha = 0.18f) else Subtle)
                            .clickable(enabled = !switching) {
                                if (voice.id == selectedId && snap.profile == VoiceProfile.Hana) return@clickable
                                scope.launch {
                                    runCatching { player.switchVoice(lang, voice.id) }
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        color = if (on) Rose else Muted,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { showVoiceSettings = true },
                    enabled = !switching,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = "Voice settings", tint = Muted)
                }
                IconButton(
                    onClick = { nav.navigate("voices") },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = "All voices", tint = Muted)
                }
            }
        }
    }
    if (showVoiceSettings) {
        VoiceSettingsSheet(
            language = lang,
            voiceId = selectedId,
            onDismiss = { showVoiceSettings = false },
        )
    }
}

@Composable
private fun VoiceBusyOverlay() {
    val context = LocalContext.current
    val player = HanaPlayer.get(context)
    val snap by player.state.collectAsState()
    if (!snap.busy) return
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.copy(alpha = 0.35f))
            .clickable(enabled = true, onClick = { /* consume taps while OfflineTts switches */ }),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = PaperElevated,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 6.dp
        ) {
            Column(
                Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Rose, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    snap.status?.takeIf { it.isNotBlank() }
                        ?: snap.busyMessage
                        ?: "Preparing…",
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                snap.downloadProgress?.let { p ->
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .width(180.dp),
                        color = Rose,
                        trackColor = Subtle
                    )
                    Text(
                        "${(p * 100).toInt()}%",
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } ?: Text(
                    "Please wait — voice is loading",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun Cover(book: Book, modifier: Modifier = Modifier) {
    val bitmap = remember(book.coverPath) {
        book.coverPath
            ?.takeIf { it.isNotBlank() }
            ?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = book.title,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(book.paper.toInt()))
        ) {
            Box(
                Modifier
                    .width(6.dp)
                    .height(200.dp)
                    .background(Rose)
                    .align(Alignment.CenterStart)
            )
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(book.author, color = Color(book.ink.toInt()).copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Serif)
                Text(book.title, color = Color(book.ink.toInt()), fontSize = 12.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, maxLines = 3)
            }
        }
    }
}

private fun importUri(activity: Activity, uri: Uri, store: ProgressStore): Book {
    runCatching {
        activity.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported"
    val lower = name.lowercase()
    when {
        lower.endsWith(".pdf") -> error("PDF isn't supported yet. Export as EPUB or TXT.")
        lower.endsWith(".doc") || lower.endsWith(".docx") -> error("Word files aren't supported. Save as TXT or EPUB.")
        lower.endsWith(".mobi") || lower.endsWith(".azw") || lower.endsWith(".azw3") ->
            error("Kindle files aren't supported. Try EPUB.")
        !(lower.endsWith(".epub") || lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown") ||
            (activity.contentResolver.getType(uri)?.contains("epub") == true) ||
            (activity.contentResolver.getType(uri)?.startsWith("text/") == true)) ->
            error("Hana reads EPUB, TXT, and Markdown.")
    }
    val isEpub = lower.endsWith(".epub") ||
        (activity.contentResolver.getType(uri)?.contains("epub") == true)
    val bookId = "imp-${System.currentTimeMillis()}"
    var coverPath: String? = null
    var metaTitle: String? = null
    val textBody: String
    if (isEpub) {
        val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not open that EPUB.")
        textBody = EpubImport.readTextChapters(bytes)
        metaTitle = EpubImport.readMetadataTitle(bytes)
        val coverBytes = EpubImport.extractCoverBytes(bytes)
        if (coverBytes != null) {
            coverPath = EpubImport.writeCoverFile(store.coverFileFor(bookId), coverBytes)
        }
    } else {
        textBody = readText(activity, uri)
    }
    val language = if (Regex("\\b(yang|dan|dengan|tidak|untuk)\\b", RegexOption.IGNORE_CASE).findAll(textBody.take(1500)).count() >= 4) "id" else "en"
    val fileTitle = name.replace(Regex("\\.(epub|txt|md|markdown)$", RegexOption.IGNORE_CASE), "")
    val title = metaTitle?.takeIf { it.isNotBlank() } ?: fileTitle
    val book = Book(
        id = bookId,
        title = title,
        author = "Imported",
        language = language,
        blurb = textBody.take(120),
        paper = 0xFFD8CFC3,
        ink = 0xFF2A241C,
        chapters = listOf(Chapter("c1", title, textBody)),
        coverPath = coverPath
    )
    store.addImported(book)
    return book
}

private fun readText(activity: Activity, uri: Uri): String {
    activity.contentResolver.openInputStream(uri).use { input ->
        return BufferedReader(InputStreamReader(input)).readText()
    }
}
