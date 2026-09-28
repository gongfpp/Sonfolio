package com.gongfpp.sonfolio.recording

import java.io.File

/** One source of truth for playable/exportable audio and its actual container. */
internal data class AudioResource(val file: File, val extension: String, val mimeType: String) {
    companion object {
        fun managedFile(root: File, path: String?): File? {
            if (path.isNullOrBlank()) return null
            val file = File(path).canonicalFile
            require(file.toPath().startsWith(root.canonicalFile.toPath()) && file != root.canonicalFile) {
                "录音路径不在受管目录，已拒绝文件操作"
            }
            return file
        }

        fun resolve(root: File, wav: String?, compressed: String?, deleted: Boolean = false): AudioResource? {
            if (deleted) return null
            // Validate both paths before touching either file, including symlinks.
            val original = managedFile(root, wav)
            val compact = managedFile(root, compressed)
            return when {
                original?.isFile == true -> AudioResource(original, "wav", "audio/wav")
                compact?.isFile == true -> AudioResource(compact, "m4a", "audio/mp4")
                else -> null
            }
        }
    }
}
