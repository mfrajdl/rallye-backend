package cz.mh.rallye.rallye_backend.templates;

import java.io.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadataNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cz.mh.rallye.rallye_backend.templates.application.TemplateImageFiles;
import cz.mh.rallye.rallye_backend.crews.PhotoFilesTest;
import static org.junit.jupiter.api.Assertions.*;

class TemplateImageFilesTest {
 @TempDir Path directory;
 @Test void pngTextAndJpegExifAreDiscardedBySharedDecoder()throws Exception{
  var storage=new TemplateImageFiles(directory.toString());String marker="PRIVATE_METADATA_MARKER";
  var writer=ImageIO.getImageWritersByFormatName("png").next();var bytes=new ByteArrayOutputStream();
  try(var output=ImageIO.createImageOutputStream(bytes)){
   writer.setOutput(output);var img=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);
   var meta=writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img),writer.getDefaultWriteParam());
   var root=new IIOMetadataNode("javax_imageio_png_1.0");var text=new IIOMetadataNode("tEXt");var entry=new IIOMetadataNode("tEXtEntry");entry.setAttribute("keyword","Comment");entry.setAttribute("value",marker);text.appendChild(entry);root.appendChild(text);meta.mergeTree("javax_imageio_png_1.0",root);
   writer.write(null,new IIOImage(img,null,meta),writer.getDefaultWriteParam());
  }finally{writer.dispose();}
  assertTrue(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.ISO_8859_1).contains(marker));
  var clean=storage.files.validate(new ByteArrayInputStream(bytes.toByteArray()));
  assertFalse(new String(clean.bytes(),java.nio.charset.StandardCharsets.ISO_8859_1).contains(marker));
  byte[] jpeg=PhotoFilesTest.image("jpeg",2,2);var injected=new ByteArrayOutputStream();injected.write(jpeg,0,2);
  byte[] exif=("Exif\0\0"+marker).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);injected.write(255);injected.write(225);injected.write((exif.length+2)>>8);injected.write((exif.length+2)&255);injected.write(exif);injected.write(jpeg,2,jpeg.length-2);
  var result=storage.files.validate(new ByteArrayInputStream(injected.toByteArray()));assertFalse(new String(result.bytes(),java.nio.charset.StandardCharsets.ISO_8859_1).contains(marker));assertEquals("image/jpeg",result.type());
 }
 @Test void generatedUuidFilesRoundTripInPrivateStorage()throws Exception{
  var storage=new TemplateImageFiles(directory.toString());var id=java.util.UUID.randomUUID();var img=storage.files.validate(new ByteArrayInputStream(PhotoFilesTest.image("png",2,2)));
  storage.files.exclusive(()->{storage.files.write(id,img);assertArrayEquals(img.bytes(),storage.files.read(id));storage.files.remove(id);return null;});assertFalse(Files.exists(directory.resolve(id+".photo")));
 }
 @Test void rejectsTruncatedOtherwiseDecodableStreams()throws Exception{
  var storage=new TemplateImageFiles(directory.toString());
  for(String format:new String[]{"jpeg","png"}){byte[] complete=PhotoFilesTest.image(format,2,2);byte[] truncated=java.util.Arrays.copyOf(complete,complete.length-2);
   assertThrows(cz.mh.rallye.rallye_backend.identity.application.AuthFailure.class,()->storage.files.validate(new ByteArrayInputStream(truncated)));}
 }
}
