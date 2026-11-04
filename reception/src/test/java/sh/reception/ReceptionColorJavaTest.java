package sh.reception;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/** Java hosts build appearance colors from ARGB ints, with and without a dark variant. */
public class ReceptionColorJavaTest {
    @Test
    public void javaCallsBothOverloads() {
        assertNotNull(ReceptionColor.argb(0xFF5B4FE0));
        assertNotNull(ReceptionColor.argb(0xFF5B4FE0, 0xFFB0A8FF));
    }
}
