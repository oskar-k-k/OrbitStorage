package com.orbitstorage;

import java.io.*;
import com.orbitstorage.file.LimitedUploadStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LimitedUploadStreamTest {
    @Test void rejectsShortAndOversizedBodies() {
        assertThrows(IOException.class,() -> new LimitedUploadStream(new ByteArrayInputStream(new byte[2]),3).transferTo(OutputStream.nullOutputStream()));
        assertThrows(IOException.class,() -> new LimitedUploadStream(new ByteArrayInputStream(new byte[4]),3).transferTo(OutputStream.nullOutputStream()));
    }
    @Test void streamsExactBody() throws Exception {
        var stream=new LimitedUploadStream(new ByteArrayInputStream(new byte[65537]),65537);
        assertEquals(65537,stream.transferTo(OutputStream.nullOutputStream())); stream.verifyComplete();
    }
}
