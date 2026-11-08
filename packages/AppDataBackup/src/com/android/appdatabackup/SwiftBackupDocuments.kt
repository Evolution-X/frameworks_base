/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.appdatabackup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipFile

internal object SwiftBackupDocuments {
    private val extensions = setOf("app", "splits", "dat", "extdat", "med", "exp", "xml", "libs")
    private data class Child(val name: String, val uri: Uri, val directory: Boolean)

    fun files(resolver: ContentResolver, tree: Uri): List<Pair<String, Uri>> =
        children(resolver, document(tree))
            .filter { !it.directory && it.name.substringAfterLast('.') in extensions }
            .map { it.name to it.uri }

    fun export(resolver: ContentResolver, tree: Uri, packageName: String, archive: File) {
        ZipFile(archive).use { zip ->
            if (zip.size() !in 2..7) throw IOException("Incomplete Swift export")
            val entries = zip.entries().asSequence().toList()
            val names = mutableSetOf<String>()
            entries.forEach { entry ->
                val extension = entry.name.substringAfterLast('.')
                if (entry.isDirectory || entry.size <= 0 || entry.crc < 0 ||
                    entry.name != "$packageName.$extension" ||
                    entry.name.contains('/') || entry.name.contains('\\') ||
                    extension !in extensions || extension == "libs" || !names.add(entry.name)) {
                    throw IOException("Invalid Swift export file")
                }
            }
            if ("$packageName.xml" !in names ||
                "$packageName.splits" in names && "$packageName.app" !in names) {
                throw IOException("Incomplete Swift export")
            }
            val root = document(tree)
            val existingPackage = children(resolver, root).firstOrNull { it.name == packageName }
            if (existingPackage != null && !existingPackage.directory) {
                throw IOException("An app folder cannot be created at this location")
            }
            val packageDir = existingPackage?.uri
                ?: DocumentsContract.createDocument(resolver, root,
                    DocumentsContract.Document.MIME_TYPE_DIR, packageName)
                ?: throw IOException("Cannot create app folder")
            val existing = children(resolver, packageDir).map { it.name }.toSet()
            val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
            var timestamp = System.currentTimeMillis()
            var name: String
            do {
                val date = format.format(Date(timestamp))
                name = "$date-${UUID.randomUUID().toString().take(2).uppercase(Locale.ROOT)}"
                timestamp += 1000
            } while (name in existing)
            val versionDir = DocumentsContract.createDocument(resolver, packageDir,
                DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: throw IOException("Cannot create backup folder")
            try {
                entries.sortedBy { it.name.endsWith(".xml") }.forEach { entry ->
                    val uri = DocumentsContract.createDocument(resolver, versionDir,
                        "application/octet-stream", entry.name)
                        ?: throw IOException("Cannot create Swift backup file")
                    resolver.openOutputStream(uri, "w").use { out ->
                        if (out == null) throw IOException("Cannot open Swift backup file")
                        CheckedInputStream(zip.getInputStream(entry), CRC32()).use { input ->
                            if (input.copyTo(out) != entry.size || input.checksum.value != entry.crc) {
                                throw IOException("Damaged Swift export file")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, versionDir) }
                throw e
            }
        }
    }

    private fun document(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        tree, DocumentsContract.getTreeDocumentId(tree))

    private fun children(resolver: ContentResolver, parent: Uri): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(parent,
            DocumentsContract.getDocumentId(parent))
        val result = mutableListOf<Child>()
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null).use { cursor ->
            if (cursor == null) throw IOException("Cannot read backup folder")
            while (cursor.moveToNext()) {
                result += Child(cursor.getString(1),
                    DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0)),
                    cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return result
    }
}
