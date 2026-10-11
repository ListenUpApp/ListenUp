package com.calypsan.listenup.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** [decodeHtmlEntities] and [htmlToPlainText] — the one way every client turns catalogue HTML into words. */
class HtmlTextTest :
    FunSpec({
        test("an ampersand entity is decoded") {
            htmlToPlainText("Tom &amp; Jerry") shouldBe "Tom & Jerry"
        }

        test("entities are decoded once, not twice") {
            decodeHtmlEntities("&amp;lt;b&amp;gt;") shouldBe "&lt;b&gt;"
            htmlToPlainText("&amp;lt;b&amp;gt;") shouldBe "&lt;b&gt;"
        }

        test("every named reference catalogues send is decoded") {
            decodeHtmlEntities("a&nbsp;b &quot;c&quot; &#39;d&#39; &apos;e&apos; &lt;f&gt;") shouldBe
                "a b \"c\" 'd' 'e' <f>"
        }

        test("closing paragraph tags become line breaks and the result is trimmed") {
            htmlToPlainText("<p>One</p><p>Two</p>") shouldBe "One\nTwo"
        }

        test("break tags with attributes, or in any case, are breaks") {
            htmlToPlainText("One<br class=\"x\">Two<BR/>Three") shouldBe "One\nTwo\nThree"
        }

        test("a tag never survives as text") {
            htmlToPlainText("a<img src=x onerror=y>b") shouldBe "ab"
        }

        test("plain text is unchanged") {
            htmlToPlainText("plain text") shouldBe "plain text"
        }

        test("blank lines left after tag removal collapse to one") {
            htmlToPlainText("a<b></b>\n\n\n\nb") shouldBe "a\n\nb"
        }
    })
