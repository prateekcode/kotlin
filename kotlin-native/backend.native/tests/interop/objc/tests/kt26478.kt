import kotlin.test.*
import objcTests.*

class KT26478

@Test
fun testKT26478() {
    val exception = assertFailsWith<ClassCastException> {
        NSBundle() as KT26478
    }
    assertEquals("class <anonymous> cannot be cast to class KT26478", exception.message)
}
