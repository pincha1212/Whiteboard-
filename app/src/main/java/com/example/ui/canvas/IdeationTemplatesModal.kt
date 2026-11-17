package com.example.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Schema
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.CustomBoardTemplate
import com.example.model.TemplateCategory
import com.example.model.TemplateType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun IdeationTemplatesDialog(
    showOnNewBoard: Boolean,
    onToggleShowOnNewBoard: (Boolean) -> Unit,
    onStartBlankCanvas: () -> Unit,
    onSelectTemplate: (TemplateType) -> Unit,
    onDismiss: () -> Unit,
    customTemplates: List<CustomBoardTemplate> = emptyList(),
    currentBoardTitle: String = "",
    onSaveCurrentAsTemplate: ((title: String, description: String) -> Unit)? = null,
    onSelectCustomTemplate: (CustomBoardTemplate) -> Unit = {},
    onDeleteCustomTemplate: (String) -> Unit = {}
) {
    var selectedCategory by remember { mutableStateOf(TemplateCategory.RECOMMENDED) }
    var searchQuery by remember { mutableStateOf("") }
    var showSaveTemplateForm by remember { mutableStateOf(false) }
    val suggestedTitle = remember(currentBoardTitle, customTemplates.size) {
        currentBoardTitle.trim().ifBlank { "Mi plantilla ${customTemplates.size + 1}" }
    }
    var draftTemplateTitle by remember(suggestedTitle) { mutableStateOf(suggestedTitle) }
    var draftTemplateDesc by remember { mutableStateOf("") }

    val filteredTemplates = remember(selectedCategory, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty() && selectedCategory == TemplateCategory.MY_TEMPLATES) {
            emptyList()
        } else {
            TemplateType.values().filter { tmpl ->
                val matchesQuery = q.isEmpty() ||
                    tmpl.title.contains(q, ignoreCase = true) ||
                    tmpl.description.contains(q, ignoreCase = true) ||
                    tmpl.category.title.contains(q, ignoreCase = true)
                val matchesCategory = q.isNotEmpty() ||
                    (selectedCategory == TemplateCategory.RECOMMENDED && tmpl.isRecommended) ||
                    tmpl.category == selectedCategory
                matchesQuery && matchesCategory
            }
        }
    }

    val filteredCustomTemplates = remember(customTemplates, selectedCategory, searchQuery) {
        val q = searchQuery.trim()
        when {
            q.isNotEmpty() -> customTemplates.filter { tmpl ->
                tmpl.title.contains(q, ignoreCase = true) ||
                    tmpl.description.contains(q, ignoreCase = true)
            }
            selectedCategory == TemplateCategory.MY_TEMPLATES -> customTemplates
            else -> emptyList()
        }
    }

    val isViewingMyTemplates = searchQuery.isBlank() && selectedCategory == TemplateCategory.MY_TEMPLATES

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .widthIn(max = 920.dp)
                .heightIn(min = 440.dp, max = 640.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                .testTag("template_picker_modal")
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isTwoPane = maxWidth >= 560.dp
                val gridColumns = when {
                    maxWidth >= 740.dp -> 3
                    maxWidth >= 380.dp -> 2
                    else -> 1
                }

                if (isTwoPane) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Left Navigation Sidebar matching reference layout
                        Column(
                            modifier = Modifier
                                .width(208.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(top = 18.dp, bottom = 8.dp)
                        ) {
                            Text(
                                text = "Plantillas",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                items(TemplateCategory.values()) { category ->
                                    if (category == TemplateCategory.BRAINSTORMING) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 6.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    val isSelected = selectedCategory == category && searchQuery.isBlank()
                                    CategorySidebarRow(
                                        category = category,
                                        isSelected = isSelected,
                                        onClick = {
                                            searchQuery = ""
                                            selectedCategory = category
                                            if (category != TemplateCategory.MY_TEMPLATES) {
                                                showSaveTemplateForm = false
                                            }
                                        }
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

                            NewBoardTemplatesToggleRow(
                                checked = showOnNewBoard,
                                onCheckedChange = onToggleShowOnNewBoard,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))

                        // Right Browser Pane
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(horizontal = 20.dp, vertical = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                TemplateSearchBar(
                                    query = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("close_template_modal")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Cerrar plantillas",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (searchQuery.isNotBlank()) "Resultados de búsqueda" else selectedCategory.title,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isViewingMyTemplates && onSaveCurrentAsTemplate != null) {
                                        OutlinedButton(
                                            onClick = { showSaveTemplateForm = !showSaveTemplateForm },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                            modifier = Modifier.testTag("btn_open_save_custom_template")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.BookmarkAdd,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Guardar pizarra actual",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = onStartBlankCanvas,
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                        modifier = Modifier.testTag("start_blank_canvas_button")
                                    ) {
                                        Text(
                                            text = "Empezar con un lienzo en blanco",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (isViewingMyTemplates && showSaveTemplateForm && onSaveCurrentAsTemplate != null) {
                                SaveCurrentTemplateInlineForm(
                                    title = draftTemplateTitle,
                                    description = draftTemplateDesc,
                                    onTitleChange = { draftTemplateTitle = it },
                                    onDescriptionChange = { draftTemplateDesc = it },
                                    onConfirmSave = {
                                        onSaveCurrentAsTemplate(draftTemplateTitle, draftTemplateDesc)
                                        draftTemplateDesc = ""
                                        showSaveTemplateForm = false
                                    },
                                    onCancel = { showSaveTemplateForm = false }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            TemplateCardsGrid(
                                templates = filteredTemplates,
                                customTemplates = filteredCustomTemplates,
                                isMyTemplatesSection = isViewingMyTemplates,
                                canSaveCurrentBoard = onSaveCurrentAsTemplate != null,
                                isSaveFormVisible = showSaveTemplateForm,
                                onOpenSaveForm = { showSaveTemplateForm = true },
                                columns = gridColumns,
                                onSelectTemplate = {
                                    onSelectTemplate(it)
                                    onDismiss()
                                },
                                onSelectCustomTemplate = {
                                    onSelectCustomTemplate(it)
                                    onDismiss()
                                },
                                onDeleteCustomTemplate = onDeleteCustomTemplate,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    // Compact single-column mobile layout
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Plantillas de Ideación",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("close_template_modal")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cerrar plantillas",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        TemplateSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(TemplateCategory.values()) { category ->
                                val isSelected = selectedCategory == category && searchQuery.isBlank()
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                        )
                                        .clickable {
                                            searchQuery = ""
                                            selectedCategory = category
                                            if (category != TemplateCategory.MY_TEMPLATES) {
                                                showSaveTemplateForm = false
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .testTag("template_cat_${category.name}")
                                ) {
                                    Text(
                                        text = category.title,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isViewingMyTemplates && onSaveCurrentAsTemplate != null) {
                                OutlinedButton(
                                    onClick = { showSaveTemplateForm = !showSaveTemplateForm },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("btn_open_save_custom_template")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BookmarkAdd,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "Guardar actual",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = onStartBlankCanvas,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("start_blank_canvas_button")
                            ) {
                                Text(
                                    text = "Empezar con un lienzo en blanco",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (isViewingMyTemplates && showSaveTemplateForm && onSaveCurrentAsTemplate != null) {
                            SaveCurrentTemplateInlineForm(
                                title = draftTemplateTitle,
                                description = draftTemplateDesc,
                                onTitleChange = { draftTemplateTitle = it },
                                onDescriptionChange = { draftTemplateDesc = it },
                                onConfirmSave = {
                                    onSaveCurrentAsTemplate(draftTemplateTitle, draftTemplateDesc)
                                    draftTemplateDesc = ""
                                    showSaveTemplateForm = false
                                },
                                onCancel = { showSaveTemplateForm = false }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        TemplateCardsGrid(
                            templates = filteredTemplates,
                            customTemplates = filteredCustomTemplates,
                            isMyTemplatesSection = isViewingMyTemplates,
                            canSaveCurrentBoard = onSaveCurrentAsTemplate != null,
                            isSaveFormVisible = showSaveTemplateForm,
                            onOpenSaveForm = { showSaveTemplateForm = true },
                            columns = gridColumns,
                            onSelectTemplate = {
                                onSelectTemplate(it)
                                onDismiss()
                            },
                            onSelectCustomTemplate = {
                                onSelectCustomTemplate(it)
                                onDismiss()
                            },
                            onDeleteCustomTemplate = onDeleteCustomTemplate,
                            modifier = Modifier.weight(1f)
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

                        NewBoardTemplatesToggleRow(
                            checked = showOnNewBoard,
                            onCheckedChange = onToggleShowOnNewBoard
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySidebarRow(
    category: TemplateCategory,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("template_cat_${category.name}")
    ) {
        Icon(
            imageVector = categoryIcon(category),
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = category.title,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun categoryIcon(category: TemplateCategory): ImageVector = when (category) {
    TemplateCategory.RECOMMENDED -> Icons.Default.AutoAwesome
    TemplateCategory.MY_TEMPLATES -> Icons.Default.CollectionsBookmark
    TemplateCategory.BRAINSTORMING -> Icons.Default.Lightbulb
    TemplateCategory.STRATEGY -> Icons.AutoMirrored.Filled.TrendingUp
    TemplateCategory.LEARNING -> Icons.Default.AccountTree
    TemplateCategory.PROCESS -> Icons.Default.Schema
    TemplateCategory.PLANNING -> Icons.Default.ViewKanban
    TemplateCategory.PROBLEM_SOLVING -> Icons.Default.Extension
}

@Composable
private fun TemplateSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.5.dp,
                color = Color(0xFF005FB8),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Buscar en todas las plantillas",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(Color(0xFF005FB8)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("template_search_input")
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Limpiar búsqueda",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onQueryChange("") }
            )
        }
    }
}

@Composable
private fun NewBoardTemplatesToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp)
            .testTag("checkbox_show_templates_new_board")
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF005FB8))
        )
        Text(
            text = "Mostrar para nuevas pizarras",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2
        )
    }
}

@Composable
private fun SaveCurrentTemplateInlineForm(
    title: String,
    description: String,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onConfirmSave: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .testTag("save_custom_template_form")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "GUARDAR PIZARRA ACTUAL COMO PLANTILLA",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF005FB8)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    label = { Text("Nombre de la plantilla") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("input_custom_template_name")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text("Descripción (opcional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("input_custom_template_desc")
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("btn_cancel_save_custom_template")
                ) {
                    Text("Cancelar")
                }
                Spacer(modifier = Modifier.width(6.dp))
                Button(
                    onClick = onConfirmSave,
                    enabled = title.isNotBlank(),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("btn_confirm_save_custom_template")
                ) {
                    Icon(
                        imageVector = Icons.Default.BookmarkAdd,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Guardar plantilla")
                }
            }
        }
    }
}

@Composable
private fun TemplateCardsGrid(
    templates: List<TemplateType>,
    customTemplates: List<CustomBoardTemplate>,
    isMyTemplatesSection: Boolean,
    canSaveCurrentBoard: Boolean,
    isSaveFormVisible: Boolean,
    onOpenSaveForm: () -> Unit,
    columns: Int,
    onSelectTemplate: (TemplateType) -> Unit,
    onSelectCustomTemplate: (CustomBoardTemplate) -> Unit,
    onDeleteCustomTemplate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (templates.isEmpty() && customTemplates.isEmpty()) {
        if (isMyTemplatesSection) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = modifier
                    .fillMaxSize()
                    .testTag("my_templates_empty_state")
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CollectionsBookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = "Aún no tienes plantillas guardadas",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Guarda el diseño de tu pizarra actual para reutilizarlo en cualquier momento.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    if (canSaveCurrentBoard && !isSaveFormVisible) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = onOpenSaveForm,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("btn_empty_save_custom_template")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Guardar pizarra actual")
                        }
                    }
                }
            }
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = modifier.fillMaxSize()
            ) {
                Text(
                    text = "No se encontraron plantillas para esa búsqueda.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize()
    ) {
        items(customTemplates, key = { "custom_${it.id}" }) { custom ->
            CustomTemplatePreviewCard(
                template = custom,
                onClick = { onSelectCustomTemplate(custom) },
                onDelete = { onDeleteCustomTemplate(custom.id) }
            )
        }
        items(templates, key = { it.name }) { tmpl ->
            TemplatePreviewCard(
                template = tmpl,
                onClick = { onSelectTemplate(tmpl) }
            )
        }
    }
}

@Composable
private fun CustomTemplatePreviewCard(
    template: CustomBoardTemplate,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val summaryText = remember(template) {
        template.description.ifBlank {
            "${template.shapeCount} formas · ${template.noteCount} notas · ${template.strokeCount} trazos"
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag("custom_template_card_${template.id}")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(94.dp)
                    .background(Color(0xFFF8FAFC))
            ) {
                // Ultra-lightweight cached vector badge instead of real-time board deserialization
                CustomTemplateVectorEmblem(
                    shapeCount = template.shapeCount,
                    noteCount = template.noteCount,
                    strokeCount = template.strokeCount,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF005FB8))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Propia",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(48.dp)
                        .testTag("btn_delete_custom_template_${template.id}")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(7.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Eliminar plantilla ${template.title}",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp)
            ) {
                Text(
                    text = template.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summaryText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun CustomTemplateVectorEmblem(
    shapeCount: Int,
    noteCount: Int,
    strokeCount: Int,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val cardW = (w * 0.24f).coerceAtLeast(22f)
            val cardH = (h * 0.42f).coerceAtLeast(18f)
            val cr = CornerRadius(4f, 4f)
            val leftRect = Offset(cx - cardW * 1.1f, cy - cardH * 0.42f)
            val rightRect = Offset(cx + cardW * 0.1f, cy - cardH * 0.22f)
            val topRect = Offset(cx - cardW * 0.48f, cy - cardH * 0.72f)
            val lineStart = Offset(leftRect.x + cardW * 0.5f, leftRect.y + cardH * 0.5f)
            val lineEnd = Offset(rightRect.x + cardW * 0.5f, rightRect.y + cardH * 0.5f)

            onDrawBehind {
                drawLine(color = Color(0xFF94A3B8), start = lineStart, end = lineEnd, strokeWidth = 1.6f)
                drawRoundRect(color = Color(0xFFDBEAFE), topLeft = topRect, size = Size(cardW, cardH), cornerRadius = cr)
                drawRoundRect(color = Color(0xFFFDE047), topLeft = leftRect, size = Size(cardW * 0.9f, cardH * 0.85f), cornerRadius = cr)
                drawRoundRect(color = Color(0xFF99F6E4), topLeft = rightRect, size = Size(cardW * 0.9f, cardH * 0.85f), cornerRadius = cr)
            }
        }
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.88f))
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = "${shapeCount + noteCount + strokeCount} elem.",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF334155)
            )
        }
    }
}

@Composable
private fun TemplatePreviewCard(
    template: TemplateType,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag("template_card_${template.name}")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(94.dp)
                    .background(Color(0xFFF8FAFC))
                    .padding(10.dp)
            ) {
                TemplateMiniPreviewCanvas(
                    template = template,
                    modifier = Modifier.fillMaxSize()
                )
                template.badgeText?.let { badge ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF005FB8))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badge,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp)
            ) {
                Text(
                    text = template.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = template.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

private val clusterPreviewColors = listOf(
    Color(0xFFFDE047), Color(0xFF99F6E4), Color(0xFFA7F3D0),
    Color(0xFFFDA4AF), Color(0xFFDDD6FE)
)

@Composable
private fun TemplateMiniPreviewCanvas(
    template: TemplateType,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f

            when (template) {
                TemplateType.BRAINSTORM_CLUSTER -> {
                    val rx = w * 0.25f
                    val ry = h * 0.34f
                    val spokes = List(5) { i ->
                        val angle = (-PI / 2.0) + i * (2.0 * PI / 5.0)
                        Offset(cx + (rx * cos(angle)).toFloat(), cy + (ry * sin(angle)).toFloat())
                    }
                    val noteSize = Size(14f, 11f)
                    val noteCr = CornerRadius(2.5f)
                    val circleR = h * 0.19f
                    val circleStroke = Stroke(width = 1.8f)

                    onDrawBehind {
                        spokes.forEachIndexed { i, p ->
                            drawLine(Color(0xFF94A3B8), Offset(cx, cy), p, strokeWidth = 1.5f)
                            drawRoundRect(
                                color = clusterPreviewColors[i],
                                topLeft = Offset(p.x - 7f, p.y - 5.5f),
                                size = noteSize,
                                cornerRadius = noteCr
                            )
                        }
                        drawCircle(Color(0xFF93C5FD), radius = circleR, center = Offset(cx, cy))
                        drawCircle(Color(0xFF2563EB), radius = circleR, center = Offset(cx, cy), style = circleStroke)
                    }
                }
                TemplateType.IMPACT_EFFORT -> {
                    val qw = w * 0.28f
                    val qh = h * 0.36f
                    val left = cx - qw
                    val top = cy - qh
                    val qSize = Size(qw, qh)
                    val nSize = Size(12f, 10f)
                    val cr = CornerRadius(2f)

                    onDrawBehind {
                        drawRect(Color(0xFFDCFCE7), Offset(left, top), qSize)
                        drawRect(Color(0xFFDBEAFE), Offset(cx, top), qSize)
                        drawRect(Color(0xFFFEF3C7), Offset(left, cy), qSize)
                        drawRect(Color(0xFFFFE4E6), Offset(cx, cy), qSize)
                        drawLine(Color(0xFF334155), Offset(cx, top - 4f), Offset(cx, top + qh * 2f + 4f), 2f)
                        drawLine(Color(0xFF334155), Offset(left - 6f, cy), Offset(left + qw * 2f + 6f, cy), 2f)
                        drawRoundRect(Color(0xFF86EFAC), Offset(left + 8f, top + 7f), nSize, cr)
                        drawRoundRect(Color(0xFF93C5FD), Offset(cx + 8f, top + 7f), nSize, cr)
                        drawRoundRect(Color(0xFFFDE047), Offset(left + 8f, cy + 7f), nSize, cr)
                        drawRoundRect(Color(0xFFFDA4AF), Offset(cx + 8f, cy + 7f), nSize, cr)
                    }
                }
                TemplateType.CONCEPT_MAP -> {
                    val rootW = w * 0.26f
                    val rootH = h * 0.19f
                    val rootTopLeft = Offset(cx - rootW / 2f, h * 0.12f)
                    val rootBottom = Offset(cx, h * 0.12f + rootH)
                    val subY = h * 0.44f
                    val noteY = h * 0.72f
                    val subXs = listOf(cx - w * 0.28f, cx, cx + w * 0.28f)
                    val subW = w * 0.19f
                    val subH = h * 0.17f
                    val noteW = w * 0.17f
                    val noteH = h * 0.17f
                    val cr = CornerRadius(3f)

                    onDrawBehind {
                        subXs.forEachIndexed { idx, sx ->
                            drawLine(Color(0xFF64748B), rootBottom, Offset(sx, subY), 1.5f)
                            drawLine(Color(0xFF94A3B8), Offset(sx, subY + subH), Offset(sx, noteY), 1.3f)
                            drawRoundRect(Color(0xFFDDD6FE), Offset(sx - subW / 2f, subY), Size(subW, subH), cr)
                            drawRoundRect(
                                color = clusterPreviewColors[idx],
                                topLeft = Offset(sx - noteW / 2f, noteY),
                                size = Size(noteW, noteH),
                                cornerRadius = cr
                            )
                        }
                        drawRoundRect(Color(0xFFBFDBFE), rootTopLeft, Size(rootW, rootH), cr)
                    }
                }
                TemplateType.USER_FLOW -> {
                    val x0 = cx - w * 0.28f
                    val x1 = cx - w * 0.02f
                    val x2 = cx + w * 0.26f
                    val y0 = h * 0.28f
                    val y1 = h * 0.68f
                    val diamond = Path().apply {
                        moveTo(x1, y1 - 9f)
                        lineTo(x1 + 11f, y1)
                        lineTo(x1, y1 + 9f)
                        lineTo(x1 - 11f, y1)
                        close()
                    }
                    val boxSize = Size(22f, 12f)
                    val cr = CornerRadius(2.5f)

                    onDrawBehind {
                        drawLine(Color(0xFF475569), Offset(x0, y0), Offset(x1, y0), 1.6f)
                        drawLine(Color(0xFF475569), Offset(x1, y0), Offset(x1, y1), 1.6f)
                        drawLine(Color(0xFF16A34A), Offset(x1, y1), Offset(x2, y1), 1.6f)
                        drawCircle(Color(0xFF86EFAC), radius = 6.5f, center = Offset(x0, y0))
                        drawRoundRect(Color(0xFF93C5FD), Offset(x1 - 11f, y0 - 6f), boxSize, cr)
                        drawPath(diamond, color = Color(0xFFFCD34D))
                        drawCircle(Color(0xFF86EFAC), radius = 6.5f, center = Offset(x2, y1))
                    }
                }
                TemplateType.RETROSPECTIVE, TemplateType.KANBAN -> {
                    val colW = w * 0.21f
                    val colH = h * 0.78f
                    val gap = w * 0.04f
                    val totalW = colW * 3f + gap * 2f
                    val startX = cx - totalW / 2f
                    val top = h * 0.11f
                    val headers = if (template == TemplateType.RETROSPECTIVE) {
                        listOf(Color(0xFF86EFAC), Color(0xFFFCD34D), Color(0xFF93C5FD))
                    } else {
                        listOf(Color(0xFFCBD5E1), Color(0xFF93C5FD), Color(0xFF86EFAC))
                    }
                    val cr = CornerRadius(3f)
                    val noteCr = CornerRadius(2f)

                    onDrawBehind {
                        for (i in 0 until 3) {
                            val x = startX + i * (colW + gap)
                            drawRoundRect(headers[i], Offset(x, top), Size(colW, colH * 0.18f), cr)
                            drawRoundRect(
                                headers[i].copy(alpha = 0.85f),
                                Offset(x + 3f, top + colH * 0.26f),
                                Size(colW - 6f, colH * 0.24f),
                                noteCr
                            )
                            if (i != 1) {
                                drawRoundRect(
                                    Color(0xFFFDE047),
                                    Offset(x + 3f, top + colH * 0.56f),
                                    Size(colW - 6f, colH * 0.24f),
                                    noteCr
                                )
                            }
                            if (i < 2) {
                                val divX = x + colW + gap / 2f
                                drawLine(Color(0xFFCBD5E1), Offset(divX, top), Offset(divX, top + colH), 1.2f)
                            }
                        }
                    }
                }
                TemplateType.ISHIKAWA -> {
                    val left = w * 0.12f
                    val spineEnd = w * 0.70f
                    val anchors = listOf(w * 0.34f, w * 0.56f)
                    val cr = CornerRadius(2.5f)
                    val boxCr = CornerRadius(4f)

                    onDrawBehind {
                        drawLine(Color(0xFF334155), Offset(left, cy), Offset(spineEnd, cy), 2.2f, cap = StrokeCap.Round)
                        anchors.forEach { ax ->
                            val outerX = ax - w * 0.14f
                            val topY = h * 0.22f
                            val botY = h * 0.78f
                            drawLine(Color(0xFF64748B), Offset(outerX, topY), Offset(ax, cy), 1.6f)
                            drawLine(Color(0xFF64748B), Offset(outerX, botY), Offset(ax, cy), 1.6f)
                            drawRoundRect(Color(0xFF99F6E4), Offset(outerX - 11f, topY - 7f), Size(22f, 11f), cr)
                            drawRoundRect(Color(0xFFFDE047), Offset(outerX - 11f, botY - 4f), Size(22f, 11f), cr)
                        }
                        drawRoundRect(
                            color = Color(0xFFFDA4AF),
                            topLeft = Offset(spineEnd + 4f, cy - h * 0.16f),
                            size = Size(w * 0.17f, h * 0.32f),
                            cornerRadius = boxCr
                        )
                    }
                }
                TemplateType.SWOT -> {
                    val qw = w * 0.28f
                    val qh = h * 0.35f
                    val gap = 5f
                    val left = cx - qw - gap / 2f
                    val top = cy - qh - gap / 2f
                    val qSize = Size(qw, qh)
                    val cr = CornerRadius(3f)

                    onDrawBehind {
                        drawLine(Color(0xFF94A3B8), Offset(cx, top - 3f), Offset(cx, cy + qh + 5f), 1.5f)
                        drawLine(Color(0xFF94A3B8), Offset(left - 5f, cy), Offset(cx + qw + 5f, cy), 1.5f)
                        drawRoundRect(Color(0xFFDCFCE7), Offset(left, top), qSize, cr)
                        drawRoundRect(Color(0xFFDBEAFE), Offset(cx + gap / 2f, top), qSize, cr)
                        drawRoundRect(Color(0xFFFEF3C7), Offset(left, cy + gap / 2f), qSize, cr)
                        drawRoundRect(Color(0xFFFFE4E6), Offset(cx + gap / 2f, cy + gap / 2f), qSize, cr)
                    }
                }
            }
        }
    )
}
