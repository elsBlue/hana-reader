package com.hana.reader

import com.hana.reader.data.EpubImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubImportTest {
    @Test
    fun resolveCoverHref_fromMetaNameCover() {
        val opf = """
            <package>
              <metadata>
                <meta name="cover" content="cover-id"/>
              </metadata>
              <manifest>
                <item id="cover-id" href="Images/cover.jpg" media-type="image/jpeg"/>
                <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
            </package>
        """.trimIndent()
        assertEquals("Images/cover.jpg", EpubImport.resolveCoverHref(opf))
    }

    @Test
    fun resolveCoverHref_fromCoverImageProperty() {
        val opf = """
            <package>
              <manifest>
                <item id="ci" href="cover.png" media-type="image/png" properties="cover-image"/>
              </manifest>
            </package>
        """.trimIndent()
        assertEquals("cover.png", EpubImport.resolveCoverHref(opf))
    }

    @Test
    fun resolveCoverHref_missingReturnsNull() {
        val opf = """
            <package>
              <manifest>
                <item id="c1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
            </package>
        """.trimIndent()
        assertNull(EpubImport.resolveCoverHref(opf))
    }

    @Test
    fun resolveCoverHref_guideImageDirect() {
        val opf = """
            <package>
              <manifest>
                <item id="c" href="OEBPS/cover.jpeg" media-type="image/jpeg"/>
              </manifest>
              <guide>
                <reference type="cover" href="OEBPS/cover.jpeg" title="Cover"/>
              </guide>
            </package>
        """.trimIndent()
        assertEquals("OEBPS/cover.jpeg", EpubImport.resolveCoverHref(opf))
    }
}
