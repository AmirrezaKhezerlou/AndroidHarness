package com.androidharness.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSearchTest {
    @Test fun `image result text round trips with credit`() {
        val results = listOf(
            ImageSearchResult(
                title = "Red Panda",
                imageUrl = "https://img.test/red.jpg",
                thumbnailUrl = "https://img.test/red_thumb.jpg",
                pageUrl = "https://page.test/red",
                source = "Openverse",
                credit = "\"Red Panda\" by Chester Zoo, CC BY-ND 2.0",
            ),
            ImageSearchResult("Second", "https://img.test/two.jpg", source = "Bing Images"),
        )
        val rendered = ImageSearchText.render(results, 10)
        val parsed = ImageSearchText.parse(rendered + "\n[The app renders these as an image grid.]")
        assertEquals(2, parsed.size)
        assertEquals("Red Panda", parsed[0].title)
        assertEquals("https://img.test/red_thumb.jpg", parsed[0].thumbnailUrl)
        assertEquals("Openverse", parsed[0].source)
        assertTrue(parsed[0].credit!!.contains("Chester Zoo"))
        assertEquals("Bing Images", parsed[1].source)
    }

    @Test fun `openverse parser reads licensed results and skips mature items`() {
        val body = """
            {"results":[
              {"title":"Red Panda","url":"https://img.test/red.jpg","thumbnail":"https://img.test/red_t.jpg",
               "foreign_landing_url":"https://page.test/red","creator":"Chester Zoo","license":"by-nd",
               "attribution":"Red Panda by Chester Zoo, CC BY-ND 2.0","mature":false},
              {"title":"Mature","url":"https://img.test/no.jpg","mature":true}
            ]}
        """.trimIndent()
        val results = OpenverseImageBackend.parse(body)
        assertEquals(1, results.size)
        assertEquals("Red Panda", results[0].title)
        assertEquals("Openverse", results[0].source)
        assertEquals("https://page.test/red", results[0].pageUrl)
        assertTrue(results[0].credit!!.contains("Chester Zoo"))
    }

    @Test fun `commons parser reads thumbnails and attribution`() {
        val body = """
            {"query":{"pages":{"1":{"title":"File:Red Panda.JPG","imageinfo":[{
              "url":"https://upload.test/red.jpg",
              "thumburl":"https://upload.test/thumb.jpg",
              "descriptionurl":"https://commons.test/File:Red_Panda.JPG",
              "extmetadata":{
                "LicenseShortName":{"value":"CC BY-SA 3.0"},
                "Artist":{"value":"<a href=\"x\">Chester Zoo</a>"}
              }
            }]}}}}
        """.trimIndent()
        val results = CommonsImageBackend.parse(body)
        assertEquals(1, results.size)
        assertEquals("Red Panda", results[0].title)
        assertEquals("https://upload.test/red.jpg", results[0].imageUrl)
        assertEquals("https://upload.test/thumb.jpg", results[0].thumbnailUrl)
        assertEquals("Wikimedia Commons", results[0].source)
        assertEquals("Chester Zoo, CC BY-SA 3.0", results[0].credit)
    }

    @Test fun `bing parser reads the escaped result json`() {
        val html = """
            <a class="iusc" m="{&quot;murl&quot;:&quot;https://img.test/panda.jpg&quot;,
            &quot;turl&quot;:&quot;https://thumb.test/panda.jpg&quot;,
            &quot;purl&quot;:&quot;https://page.test/panda&quot;,
            &quot;t&quot;:&quot;Red Panda&quot;}"></a>
        """.trimIndent()
        val results = BingImageBackend.parse(html)
        assertEquals(1, results.size)
        assertEquals("Red Panda", results[0].title)
        assertEquals("https://thumb.test/panda.jpg", results[0].thumbnailUrl)
        assertEquals("https://page.test/panda", results[0].pageUrl)
        assertEquals("Bing Images", results[0].source)
    }

    @Test fun `image urls reject svg and non http schemes`() {
        assertTrue(validImageUrl("https://img.test/a.jpg"))
        assertTrue(!validImageUrl("https://img.test/a.svg"))
        assertTrue(!validImageUrl("data:image/png;base64,xxx"))
    }
}
