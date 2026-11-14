package com.example.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CanvasColorTheme
import com.example.model.CanvasGridType
import com.example.model.WhiteboardBoard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@Composable
fun BoardsHomeScreen(
    boards: List<WhiteboardBoard>,
    currentBoardId: String,
    onOpenBoard: (String) -> Unit,
    onCreateBoard: (title: String, gridType: CanvasGridType, colorTheme: CanvasColorTheme) -> Unit,
    onRenameBoard: (boardId: String, newTitle: String) -> Unit,
    onDuplicateBoard: (String) -> Unit,
    onDeleteBoard: (String) -> Unit,
    onSwitchToNotes: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var boardToRename by remember { mutableStateOf<WhiteboardBoard?>(null) }
    var boardToDelete by remember { mutableStateOf<WhiteboardBoard?>(null) }
    var boardToExport by remember { mutableStateOf<WhiteboardBoard?>(null) }

    val filteredBoards = remember(boards, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) boards
        else boards.filter { it.title.contains(q, ignoreCase = true) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("fab_new_board_home")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Nueva pizarra")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Nueva Pizarra", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        },
        modifier = modifier.fillMaxSize().testTag("boards_home_screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Top Bar (Modern Minimal, matching Notes & Whiteboard)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onSwitchToNotes != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                    .padding(3.dp)
                            ) {
                                HomeWorkspaceSegment(
                                    icon = Icons.Default.Draw,
                                    label = "Pizarras",
                                    isSelected = true,
                                    onClick = {},
                                    testTag = "btn_home_tab_boards"
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                HomeWorkspaceSegment(
                                    icon = Icons.Default.Description,
                                    label = "Notas",
                                    isSelected = false,
                                    onClick = onSwitchToNotes,
                                    testTag = "btn_home_tab_notes"
                                )
                            }
                        } else {
                            Text(
                                text = "Mis Pizarras",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "${boards.size} ${if (boards.size == 1) "pizarra" else "pizarras"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Search Bar
                    if (boards.isNotEmpty()) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Buscar pizarras...", fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Limpiar búsqueda",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
                                .testTag("input_search_boards")
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            if (filteredBoards.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Dashboard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (boards.isEmpty()) "No hay pizarras creadas" else "No se encontraron pizarras",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (boards.isEmpty()) {
                                "Pulsa '+ Nueva Pizarra' para empezar a dibujar."
                            } else {
                                "Prueba con otro título en el buscador."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 164.dp),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().testTag("boards_home_grid")
                ) {
                    items(filteredBoards, key = { it.id }) { board ->
                        BoardSummaryCard(
                            board = board,
                            isCurrent = board.id == currentBoardId,
                            onOpen = { onOpenBoard(board.id) },
                            onRename = { boardToRename = board },
                            onDuplicate = { onDuplicateBoard(board.id) },
                            onExport = { boardToExport = board },
                            onDelete = { boardToDelete = board }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateBoardDialog(
            defaultNumber = boards.size + 1,
            onCreate = { title, grid, theme ->
                showCreateDialog = false
                onCreateBoard(title, grid, theme)
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    if (boardToRename != null) {
        val target = boardToRename!!
        var titleInput by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { boardToRename = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Renombrar Pizarra", fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    label = { Text("Título") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (titleInput.isNotBlank()) {
                            onRenameBoard(target.id, titleInput.trim())
                            boardToRename = null
                        }
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { boardToRename = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (boardToDelete != null) {
        val target = boardToDelete!!
        AlertDialog(
            onDismissRequest = { boardToDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Eliminar pizarra", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    text = "¿Seguro que deseas eliminar \"${target.title}\"? Se perderán sus trazos, formas, notas y snapshots.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteBoard(target.id)
                        boardToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { boardToDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (boardToExport != null) {
        ExportBoardModal(
            board = boardToExport!!,
            onDismiss = { boardToExport = null }
        )
    }
}

@Composable
private fun BoardSummaryCard(
    board: WhiteboardBoard,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var showCardMenu by remember { mutableStateOf(false) }
    val sdf = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isCurrent) 1.5.dp else 1.dp,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onOpen)
            .testTag("board_card_${board.id}")
    ) {
        Column {
            // Live miniature canvas preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            ) {
                BoardCardMiniPreview(board = board, modifier = Modifier.fillMaxSize())

                if (board.strokes.isEmpty() && board.shapes.isEmpty() && board.stickyNotes.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Lienzo vacío",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (board.colorTheme.isDark) Color.White.copy(alpha = 0.55f) else Color(0xFF64748B)
                        )
                    }
                }

                if (board.snapshots.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${board.snapshots.size} snap",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Metadata + Menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = board.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${board.strokes.size}T · ${board.shapes.size}F · ${board.stickyNotes.size}N",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        text = sdf.format(Date(board.updatedAt)),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1
                    )
                }

                Box {
                    IconButton(
                        onClick = { showCardMenu = true },
                        modifier = Modifier.size(34.dp).testTag("btn_board_menu_${board.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Opciones de pizarra",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showCardMenu,
                        onDismissRequest = { showCardMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Abrir") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showCardMenu = false
                                onOpen()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Renombrar") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showCardMenu = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicar") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showCardMenu = false
                                onDuplicate()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Exportar") },
                            leadingIcon = { Icon(Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showCardMenu = false
                                onExport()
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DropdownMenuItem(
                            text = { Text("Eliminar", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showCardMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardCardMiniPreview(
    board: WhiteboardBoard,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val bg = parseColor(board.colorTheme.hex, Color.White)
        drawRect(color = bg)

        // Subtle grid hint
        if (board.gridType != CanvasGridType.BLANK) {
            val gridColor = if (board.colorTheme.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
            val step = 18f
            var gx = step
            while (gx < w) {
                drawLine(gridColor, Offset(gx, 0f), Offset(gx, h), strokeWidth = 0.8f)
                gx += step
            }
            var gy = step
            while (gy < h) {
                drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 0.8f)
                gy += step
            }
        }

        // Compute bounds of content
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var hasContent = false

        board.strokes.forEach { s ->
            s.points.forEach { p ->
                hasContent = true
                minX = min(minX, p.x)
                maxX = max(maxX, p.x)
                minY = min(minY, p.y)
                maxY = max(maxY, p.y)
            }
        }
        board.shapes.forEach { sh ->
            hasContent = true
            minX = min(minX, sh.x)
            maxX = max(maxX, sh.x + sh.width)
            minY = min(minY, sh.y)
            maxY = max(maxY, sh.y + sh.height)
        }
        board.stickyNotes.forEach { n ->
            hasContent = true
            minX = min(minX, n.x)
            maxX = max(maxX, n.x + n.width)
            minY = min(minY, n.y)
            maxY = max(maxY, n.y + n.height)
        }

        if (!hasContent || minX == Float.MAX_VALUE) return@Canvas

        val pad = 32f
        val contentW = (maxX - minX + pad * 2f).coerceAtLeast(160f)
        val contentH = (maxY - minY + pad * 2f).coerceAtLeast(120f)
        val scale = min(w / contentW, h / contentH)
        val offsetX = (w - contentW * scale) / 2f - (minX - pad) * scale
        val offsetY = (h - contentH * scale) / 2f - (minY - pad) * scale

        board.strokes.forEach { stroke ->
            drawStrokeVector(stroke = stroke, panX = offsetX, panY = offsetY, scale = scale, isSelected = false)
        }
        board.shapes.forEach { shape ->
            drawShapeVector(shape = shape, panX = offsetX, panY = offsetY, scale = scale, isSelected = false)
        }
        board.stickyNotes.forEach { note ->
            val nx = note.x * scale + offsetX
            val ny = note.y * scale + offsetY
            val nw = (note.width * scale).coerceAtLeast(6f)
            val nh = (note.height * scale).coerceAtLeast(6f)
            drawRoundRect(
                color = parseColor(note.colorHex, Color(0xFFFFF275)),
                topLeft = Offset(nx, ny),
                size = Size(nw, nh),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }
    }
}

@Composable
private fun CreateBoardDialog(
    defaultNumber: Int,
    onCreate: (title: String, gridType: CanvasGridType, colorTheme: CanvasColorTheme) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("Pizarra $defaultNumber") }
    var selectedGrid by remember { mutableStateOf(CanvasGridType.GRID) }
    var selectedTheme by remember { mutableStateOf(CanvasColorTheme.WHITE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Nueva Pizarra", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nombre de la pizarra") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("input_new_board_title")
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Fondo de papel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CanvasColorTheme.values().forEach { t ->
                            val selected = t == selectedTheme
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(parseColor(t.hex))
                                    .border(
                                        width = if (selected) 2.5.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedTheme = t }
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Cuadrícula", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(CanvasGridType.GRID, CanvasGridType.DOTS, CanvasGridType.BLANK).forEach { g ->
                            val selected = g == selectedGrid
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                    .border(
                                        width = 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedGrid = g }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = g.title,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(title.trim().ifBlank { "Pizarra $defaultNumber" }, selectedGrid, selectedTheme)
                },
                enabled = title.isNotBlank()
            ) {
                Text("Crear y Abrir")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun HomeWorkspaceSegment(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fgColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(enabled = !isSelected, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fgColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = fgColor,
            maxLines = 1
        )
    }
}
