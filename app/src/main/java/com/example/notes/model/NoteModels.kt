package com.example.notes.model

import java.util.UUID

data class NoteTag(
    val name: String,
    val colorHex: String
)

data class ChecklistItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isDone: Boolean = false
)

data class NoteFolder(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val iconName: String = "folder",
    val colorHex: String = "#0078D4",
    val isDefault: Boolean = false
)

data class NoteVersion(
    val versionId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val title: String,
    val content: String,
    val characterCount: Int = content.length,
    val changeSummary: String = "Edición automática"
)

data class NoteItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val folder: String = "Bandeja de Entrada",
    val tags: List<NoteTag> = emptyList(),
    val colorHex: String = "#FFFFFF",
    val isPinned: Boolean = false,
    val isLocked: Boolean = false,
    val pinHash: String? = null,
    val isVoiceNote: Boolean = false,
    val voiceDurationSec: Int = 0,
    val voiceAudioUri: String? = null,
    val checklists: List<ChecklistItem> = emptyList(),
    val versionHistory: List<NoteVersion> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class VaultSecuritySettings(
    val masterPin: String = "1234",
    val isVaultLocked: Boolean = false,
    val autoLockTimeoutMinutes: Int = 5
)
