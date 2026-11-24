package com.example.ui.canvas

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.BoardExportService
import com.example.ink.engine.MutableRasterTileStore
import com.example.model.WhiteboardBoard
import com.example.ui.theme.StudioMonoTextStyle

@Composable
fun ExportBoardModal(
    board: WhiteboardBoard,
    mutableRasterTileStore: MutableRasterTileStore? = null,
    activeLayerId: String? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var includeBackground by remember { mutableStateOf(true) }
    var includeGrid by remember { mutableStateOf(true) }
    var exportActiveLayerOnly by remember { mutableStateOf(false) }
    var resolutionMultiplier by remember { mutableStateOf(1) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val resolvedLayerFilter = if (exportActiveLayerOnly) activeLayerId else null

    val previewBitmap = remember(board, includeBackground, includeGrid, resolvedLayerFilter, resolutionMultiplier) {
        BoardExportService.renderBoardToBitmap(
            board = board,
            includeBackground = includeBackground,
            includeGrid = includeGrid,
            maxDimensionPx = 1200,
            mutableRasterStore = mutableRasterTileStore,
            onlyLayerId = resolvedLayerFilter,
            resolutionMultiplier = 1
        )
    }

    val estimatedExportW = previewBitmap.width * resolutionMultiplier
    val estimatedExportH = previewBitmap.height * resolutionMultiplier

    fun buildFinalExportBitmap(): android.graphics.Bitmap {
        return BoardExportService.renderBoardToBitmap(
            board = board,
            includeBackground = includeBackground,
            includeGrid = includeGrid,
            maxDimensionPx = 1200,
            mutableRasterStore = mutableRasterTileStore,
            onlyLayerId = resolvedLayerFilter,
            resolutionMultiplier = resolutionMultiplier
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .testTag("export_board_modal")
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.IosShare,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Exportar Pizarra",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${board.title} · ~${estimatedExportW}×${estimatedExportH} px (${resolutionMultiplier}x)",
                                style = StudioMonoTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Live Rendered Bitmap Preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "Vista previa de exportación",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Export options toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExportToggleChip(
                        label = if (includeBackground) "Con fondo" else "PNG transparente",
                        checked = includeBackground,
                        onClick = { includeBackground = !includeBackground },
                        modifier = Modifier.weight(1f).testTag("export_toggle_background")
                    )
                    ExportToggleChip(
                        label = "Cuadrícula",
                        checked = includeGrid && includeBackground,
                        enabled = includeBackground,
                        onClick = { includeGrid = !includeGrid },
                        modifier = Modifier.weight(1f).testTag("export_toggle_grid")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (activeLayerId != null) {
                        ExportToggleChip(
                            label = "Solo capa activa",
                            checked = exportActiveLayerOnly,
                            onClick = { exportActiveLayerOnly = !exportActiveLayerOnly },
                            modifier = Modifier.weight(1.25f).testTag("export_only_active_layer")
                        )
                    }
                    listOf(1, 2, 4).forEach { mult ->
                        ExportToggleChip(
                            label = "${mult}x",
                            checked = resolutionMultiplier == mult,
                            onClick = { resolutionMultiplier = mult },
                            modifier = Modifier.weight(0.6f).testTag("export_res_${mult}x")
                        )
                    }
                }

                if (statusMessage != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = statusMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        val finalBmp = buildFinalExportBitmap()
                        val res = BoardExportService.saveBitmapToDevice(context, finalBmp, board.title)
                        res.onSuccess { msg ->
                            statusMessage = msg
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }.onFailure { err ->
                            statusMessage = "Error al guardar: ${err.localizedMessage}"
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("btn_export_save_gallery")
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Guardar PNG en Galería (${resolutionMultiplier}x)")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val finalBmp = buildFinalExportBitmap()
                            BoardExportService.createSharePngIntent(context, finalBmp, board.title)
                                .onSuccess { intent -> context.startActivity(intent) }
                                .onFailure { err ->
                                    statusMessage = "No se pudo compartir: ${err.localizedMessage}"
                                }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).testTag("btn_export_share_png")
                    ) {
                        Icon(Icons.Default.IosShare, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Compartir PNG", fontSize = 12.sp)
                    }

                    TextButton(
                        onClick = {
                            BoardExportService.createShareJsonIntent(context, board)
                                .onSuccess { intent -> context.startActivity(intent) }
                                .onFailure { err ->
                                    statusMessage = "No se pudo exportar JSON: ${err.localizedMessage}"
                                }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(0.85f).testTag("btn_export_json")
                    ) {
                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Respaldo JSON", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportToggleChip(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (checked && enabled) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                width = 1.dp,
                color = if (checked && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        if (checked && enabled) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            else if (checked) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
