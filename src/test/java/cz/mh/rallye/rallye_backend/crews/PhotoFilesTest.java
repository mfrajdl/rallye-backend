package cz.mh.rallye.rallye_backend.crews;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import cz.mh.rallye.rallye_backend.crews.application.PhotoFiles;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class PhotoFilesTest {
    @TempDir Path directory;
    public static byte[] image(String format,int width,int height) throws IOException {
        var image=new BufferedImage(width,height,format.equals("jpeg")?BufferedImage.TYPE_INT_RGB:BufferedImage.TYPE_INT_ARGB);
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,format,bytes);return bytes.toByteArray();
    }
    @Test void decodesActualContentAndReencodesPngAndJpeg() throws Exception {
        var files=new PhotoFiles(directory.toString());
        for(String format:new String[]{"png","jpeg"}) {
            var result=files.validate(new ByteArrayInputStream(image(format,23,17)));
            assertEquals(23,result.width());assertEquals(17,result.height());assertEquals("image/"+format,result.type());
            assertEquals(23,ImageIO.read(new ByteArrayInputStream(result.bytes())).getWidth());
        }
    }
    @Test void rejectsEmptyFakeTruncatedAndUnsupportedImages() throws Exception {
        var files=new PhotoFiles(directory.toString());
        for(byte[] bytes:new byte[][]{new byte[0],"<svg/>".getBytes(),new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff}})
            assertEquals(400,assertThrows(AuthFailure.class,()->files.validate(new ByteArrayInputStream(bytes))).status());
        assertEquals(415,assertThrows(AuthFailure.class,()->files.validate(new ByteArrayInputStream(image("gif",1,1)))).status());
    }
    @Test void checksByteLimitAndBothDimensionsBeforeDecoding() throws Exception {
        var files=new PhotoFiles(directory.toString());
        assertEquals(413,assertThrows(AuthFailure.class,()->files.validate(new ByteArrayInputStream(new byte[PhotoFiles.MAX_BYTES+1]))).status());
        for(int[] size:new int[][]{{4097,1},{1,4097}})
            assertEquals("INVALID_PHOTO_DIMENSIONS",assertThrows(AuthFailure.class,
                    ()->files.validate(new ByteArrayInputStream(image("png",size[0],size[1])))).code());
        assertEquals(4096,files.validate(new ByteArrayInputStream(image("png",4096,1))).width());
    }
    @Test void cleanupKeepsRecentAndReferencedFilesRechecksReferencesAndIgnoresOtherFiles() throws Exception {
        var files=new PhotoFiles(directory.toString());
        UUID orphan=UUID.randomUUID(),referenced=UUID.randomUUID(),racing=UUID.randomUUID(),recent=UUID.randomUUID();
        Instant cutoff=Instant.now().minusSeconds(86400);
        for(UUID id:new UUID[]{orphan,referenced,racing,recent}) {
            Path file=directory.resolve(id+".photo");Files.write(file,new byte[]{1});
            Files.setLastModifiedTime(file,FileTime.from(cutoff.minusSeconds(1)));
        }
        Files.setLastModifiedTime(directory.resolve(recent+".photo"),FileTime.from(Instant.now()));
        Files.writeString(directory.resolve("unrelated.txt"),"keep");
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        int removed=files.exclusive(()->files.cleanup(cutoff,id->id.equals(referenced)||(id.equals(racing)&&calls.incrementAndGet()>1)));
        assertEquals(1,removed);assertFalse(Files.exists(directory.resolve(orphan+".photo")));
        for(UUID id:new UUID[]{referenced,racing,recent}) assertTrue(Files.exists(directory.resolve(id+".photo")));
        assertTrue(Files.exists(directory.resolve("unrelated.txt")));
    }
    @Test void cleanupCannotEnterStorageWhileUploadHoldsLock() throws Exception {
        var files=new PhotoFiles(directory.toString());
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var upload=executor.submit(()->files.exclusive(()->{
                started.countDown();try {if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("Timeout");}
                catch(InterruptedException e){throw new AssertionError(e);}return 1;
            }));
            assertTrue(started.await(5,TimeUnit.SECONDS));
            var cleanup=executor.submit(()->files.exclusive(()->files.cleanup(Instant.now(),id->false)));
            try { assertThrows(TimeoutException.class,()->cleanup.get(100,TimeUnit.MILLISECONDS)); }
            finally {release.countDown();}
            assertEquals(1,upload.get(5,TimeUnit.SECONDS));assertEquals(0,cleanup.get(5,TimeUnit.SECONDS));
        }
    }
}
