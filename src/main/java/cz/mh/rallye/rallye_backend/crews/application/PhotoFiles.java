package cz.mh.rallye.rallye_backend.crews.application;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Files are immutable. Every read/write/cleanup uses the same process + filesystem lock. */
@Component
public class PhotoFiles {
    public static final int MAX_BYTES=5*1024*1024;
    public record Image(byte[] bytes,String type,int width,int height) { }
    private final Path directory;
    private final ReentrantLock local=new ReentrantLock();
    public PhotoFiles(@Value("${rallye.crews.photo-directory:${user.home}/.rallye/crew-photos}") String directory) {
        this.directory=Path.of(directory).toAbsolutePath().normalize();
    }
    public Image validate(InputStream input) {
        try {
            byte[] bytes=input.readNBytes(MAX_BYTES+1);
            if(bytes.length>MAX_BYTES) throw new AuthFailure(413,"PHOTO_TOO_LARGE");
            try(var imageInput=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers=ImageIO.getImageReaders(imageInput);
                if(!readers.hasNext()) throw new AuthFailure(400,"INVALID_PHOTO");
                var reader=readers.next();
                try {
                    String format=reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                    if(!format.equals("jpeg")&&!format.equals("png")) throw new AuthFailure(415,"PHOTO_UNSUPPORTED_FORMAT");
                    // ImageIO tolerates some truncated JPEG/PNG streams. Require their end marker too.
                    if(format.equals("jpeg") && (bytes.length<2 || bytes[bytes.length-2]!=(byte)0xff || bytes[bytes.length-1]!=(byte)0xd9))
                        throw new AuthFailure(400,"INVALID_PHOTO");
                    if(format.equals("png") && (bytes.length<12 || !java.util.Arrays.equals(
                            java.util.Arrays.copyOfRange(bytes,bytes.length-12,bytes.length),
                            new byte[]{0,0,0,0,73,69,78,68,(byte)174,66,96,(byte)130})))
                        throw new AuthFailure(400,"INVALID_PHOTO");
                    var warning=new boolean[1];reader.addIIOReadWarningListener((source,message)->warning[0]=true);
                    reader.setInput(imageInput,true,true);
                    int width=reader.getWidth(0),height=reader.getHeight(0);
                    if(width<1||height<1||width>4096||height>4096) throw new AuthFailure(400,"INVALID_PHOTO_DIMENSIONS");
                    var decoded=reader.read(0);
                    if(decoded==null || warning[0])throw new AuthFailure(400,"INVALID_PHOTO");
                    // Copy pixels only: do not retain EXIF/GPS or ancillary source metadata.
                    var clean=new BufferedImage(width,height,format.equals("jpeg")?BufferedImage.TYPE_INT_RGB:BufferedImage.TYPE_INT_ARGB);
                    var graphics=clean.createGraphics();
                    try { graphics.drawImage(decoded,0,0,null); } finally { graphics.dispose(); }
                    var output=new ByteArrayOutputStream();
                    if(!ImageIO.write(clean,format,output)) throw new AuthFailure(400,"INVALID_PHOTO");
                    if(output.size()>MAX_BYTES) throw new AuthFailure(413,"PHOTO_TOO_LARGE");
                    return new Image(output.toByteArray(),format.equals("jpeg")?"image/jpeg":"image/png",width,height);
                } finally { reader.dispose(); }
            }
        } catch(IOException|IllegalArgumentException failure) { throw new AuthFailure(400,"INVALID_PHOTO"); }
    }
    public <T> T exclusive(Supplier<T> operation) {
        local.lock();
        try {
            Files.createDirectories(directory);
            if(Files.isSymbolicLink(directory)||Files.isSymbolicLink(directory.resolve(".storage.lock")))
                throw new IOException("Unsafe photo storage");
            if(Files.getFileStore(directory).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
            try(var channel=FileChannel.open(directory.resolve(".storage.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var ignored=channel.lock()) { return operation.get(); }
        } catch(IOException failure) { throw new AuthFailure(503,"PHOTO_STORAGE_UNAVAILABLE"); }
        finally { local.unlock(); }
    }
    private Path path(UUID id) { return directory.resolve(id+".photo"); }
    public void write(UUID id,Image image) {
        Path temporary=null;
        try {
            temporary=Files.createTempFile(directory,"upload-",".tmp");
            Files.write(temporary,image.bytes());
            Files.move(temporary,path(id),StandardCopyOption.ATOMIC_MOVE);
        } catch(IOException failure) { throw new AuthFailure(503,"PHOTO_STORAGE_UNAVAILABLE"); }
        finally { if(temporary!=null) try { Files.deleteIfExists(temporary); } catch(IOException ignored) { } }
    }
    public byte[] read(UUID id) {
        try {
            if(Files.isSymbolicLink(path(id))) throw new IOException("Unsafe photo");
            try(var input=Files.newInputStream(path(id))) {
                byte[] bytes=input.readNBytes(MAX_BYTES+1);
                if(bytes.length>MAX_BYTES) throw new IOException("Invalid stored size");
                return bytes;
            }
        } catch(IOException failure) { throw new AuthFailure(503,"PHOTO_STORAGE_UNAVAILABLE"); }
    }
    public void remove(UUID id) {
        if(id==null) return;
        try { Files.deleteIfExists(path(id)); }
        catch(IOException ignored) { /* A later explicit orphan cleanup retries removal. */ }
    }
    public int cleanup(Instant cutoff,Predicate<UUID> referenced) {
        int removed=0;
        try(var files=Files.list(directory)) {
            for(Path file:files.toList()) {
                String name=file.getFileName().toString();
                if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                        ||!Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) continue;
                if(name.matches("[0-9a-f-]{36}\\.photo")) {
                    UUID id;
                    try { id=UUID.fromString(name.substring(0,36)); } catch(IllegalArgumentException ignored) {continue;}
                    if(!referenced.test(id) && Files.getLastModifiedTime(file).toInstant().isBefore(cutoff) && !referenced.test(id)) {
                        Files.delete(file); removed++;
                    }
                } else if(name.matches("upload-.*\\.tmp")) { Files.delete(file); removed++; }
            }
            return removed;
        } catch(IOException failure) { throw new AuthFailure(503,"PHOTO_STORAGE_UNAVAILABLE"); }
    }
}
