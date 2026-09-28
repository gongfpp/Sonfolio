package com.gongfpp.sonfolio

import com.gongfpp.sonfolio.recording.AudioResource
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioResourceTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun resolvesWavBothAacAndDeletedWithCorrectContainer() {
        val root = tmp.newFolder("recordings")
        val wav = File(root, "one.wav").apply { writeBytes(byteArrayOf(1)) }
        val aac = File(root, "one.m4a")
        assertEquals("wav", AudioResource.resolve(root, wav.path, null)!!.extension)
        aac.writeBytes(byteArrayOf(2))
        assertEquals(wav.canonicalFile, AudioResource.resolve(root, wav.path, aac.path)!!.file)
        wav.delete()
        val compact = AudioResource.resolve(root, wav.path, aac.path)!!
        assertEquals("m4a", compact.extension)
        assertEquals("audio/mp4", compact.mimeType)
        assertNull(AudioResource.resolve(root, wav.path, aac.path, deleted = true))
        aac.delete()
        assertNull(AudioResource.resolve(root, wav.path, aac.path))
    }
    @Test fun refusesSiblingTraversalAndSymlinksBeforeFileAccess() {
        val root = tmp.newFolder("recordings")
        val secret = tmp.newFile("outside.wav")
        assertThrows(IllegalArgumentException::class.java) { AudioResource.resolve(root, secret.path, null) }
        assertThrows(IllegalArgumentException::class.java) { AudioResource.managedFile(root, File(root, "../outside.wav").path) }
        val link = File(root, "link.wav")
        Files.createSymbolicLink(link.toPath(), secret.toPath())
        assertThrows(IllegalArgumentException::class.java) { AudioResource.resolve(root, link.path, null) }
        assertNull(AudioResource.managedFile(root, ""))
    }
}
