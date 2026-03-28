package com.openclaw.musicworker.desktop

import java.io.File
import javax.swing.JFileChooser

object DesktopDirectoryPicker {
    fun pickDirectory(initialPath: String?): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = "选择下载目录"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            initialPath
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::File)
                ?.let { initialDirectory ->
                    currentDirectory = initialDirectory
                    selectedFile = initialDirectory
                }
        }

        val result = chooser.showOpenDialog(null)
        if (result != JFileChooser.APPROVE_OPTION) {
            return null
        }

        return chooser.selectedFile
            ?.absoluteFile
            ?.toPath()
            ?.toAbsolutePath()
            ?.normalize()
            ?.toString()
    }
}
