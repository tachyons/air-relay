package `in`.aboobacker.airrelay.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileTransferTest {

    @Test
    fun `safe name keeps only the base name`() {
        assertEquals("evil.sh", FileTransfer.safeName("../../Library/evil.sh"))
        assertEquals("photo.jpg", FileTransfer.safeName("photo.jpg"))
        assertEquals("passwd", FileTransfer.safeName("/etc/passwd"))
        assertEquals("photo.jpg", FileTransfer.safeName("C:\\Users\\me\\photo.jpg"))
        assertNull(FileTransfer.safeName(".."))
        assertNull(FileTransfer.safeName(""))
    }
}
