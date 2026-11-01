package vibe.tube.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class UnaccentedQueryTest {
    @Test
    fun stripsVietnameseAccentsForSearchFallback() {
        assertEquals("3 phai 6 dung quan ap", unaccented("3 phải 6 đừng quân ap"))
        assertEquals("Dang Ngoc Son Tung", unaccented("Đặng Ngọc Sơn Tùng"))
        assertEquals("son tung m-tp", unaccented("son tung m-tp"))
    }
}
