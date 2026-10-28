package cz.mh.rallye.rallye_backend.templates.application;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import cz.mh.rallye.rallye_backend.crews.application.PhotoFiles;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;

@Component
public class TemplateFileContent {
 public record Limits(int imageMaxBytes,int imageMaxWidth,int imageMaxHeight,int markdownMaxBytes,int pdfMaxBytes) {}
 public record Validated(byte[] bytes,String contentType,Integer width,Integer height,String markdown){@Override public String toString(){return "ValidatedFile[redacted]";}}
 private final Limits limits;
 public TemplateFileContent(@Value("${rallye.templates.files.markdown-max-bytes:102400}") int markdown,@Value("${rallye.templates.files.pdf-max-bytes:10485760}") int pdf){
  if(markdown<1||markdown>1048576||pdf<1||pdf>52428800)throw new IllegalArgumentException("Invalid template file size limits");limits=new Limits(PhotoFiles.MAX_BYTES,4096,4096,markdown,pdf);
 }
 public Limits limits(){return limits;}
 public Validated validate(String name,InputStream input,PhotoFiles images){
  String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
  if(Set.of("jpg","jpeg","png").contains(ext)){
   try{var image=images.validate(input);if(!image.type().equals(ext.equals("png")?"image/png":"image/jpeg"))throw new AuthFailure(400,"INVALID_FILE");return new Validated(image.bytes(),image.type(),image.width(),image.height(),null);}
   catch(AuthFailure e){throw new AuthFailure(e.status(),switch(e.code()){case "PHOTO_TOO_LARGE"->"FILE_TOO_LARGE";case "PHOTO_UNSUPPORTED_FORMAT"->"FILE_UNSUPPORTED_FORMAT";case "INVALID_PHOTO_DIMENSIONS"->"INVALID_FILE_DIMENSIONS";default->"INVALID_FILE";});}
  }
  if(!Set.of("md","pdf").contains(ext))throw new AuthFailure(415,"FILE_UNSUPPORTED_FORMAT");
  byte[] bytes;try{int limit=ext.equals("md")?limits.markdownMaxBytes():limits.pdfMaxBytes();bytes=input.readNBytes(limit+1);if(bytes.length>limit)throw new AuthFailure(413,"FILE_TOO_LARGE");}catch(IOException e){throw new AuthFailure(400,"INVALID_FILE");}
  if(ext.equals("md")){
   try{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    if(text.codePoints().anyMatch(c->(Character.isISOControl(c)&&c!='\r'&&c!='\n'&&c!='\t')||c==0xfffd)||text.startsWith("%PDF-")||text.startsWith("PK\u0003\u0004"))throw new AuthFailure(400,"INVALID_FILE");
    return new Validated(bytes,"text/markdown",null,null,text);
   }catch(CharacterCodingException e){throw new AuthFailure(400,"INVALID_FILE");}
  }
  if(bytes.length<8||!new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))throw new AuthFailure(400,"INVALID_FILE");
  try(var buffer=new RandomAccessReadBuffer(bytes)){
   var parser=new PDFParser(buffer);try(var document=parser.parse(false)){document.getDocumentCatalog();document.getNumberOfPages();}
  }catch(IOException|RuntimeException e){throw new AuthFailure(400,"INVALID_FILE");}
  return new Validated(bytes,"application/pdf",null,null,null);
 }
}
