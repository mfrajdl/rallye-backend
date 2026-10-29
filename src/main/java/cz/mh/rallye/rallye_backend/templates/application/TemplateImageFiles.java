package cz.mh.rallye.rallye_backend.templates.application;

import java.util.*;
import java.nio.file.*;
import java.io.*;
import java.time.Instant;
import java.util.function.BiPredicate;
import cz.mh.rallye.rallye_backend.crews.application.PhotoFiles;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

/** Existing decoder and global process/filesystem lock; named files are private per template. */
@Component
public class TemplateImageFiles {
 public final PhotoFiles files;private final Path root;private final String namespace;
 @org.springframework.beans.factory.annotation.Autowired
 public TemplateImageFiles(@Value("${rallye.templates.image-directory:${user.home}/.rallye/template-images}") String directory){this(directory,"race-templates");}
 public TemplateImageFiles(String directory,String namespace){if(!Set.of("race-templates","race-events","race-simulations").contains(namespace))throw new IllegalArgumentException("Invalid storage namespace");this.namespace=namespace;root=Path.of(directory).toAbsolutePath().normalize();files=new PhotoFiles(directory);}
 public boolean sameRoot(TemplateImageFiles other){return root.equals(other.root);}
 private Path directory(UUID template,boolean create)throws IOException{
  Path path=root;
  if(Files.isSymbolicLink(root))throw new IOException("Unsafe storage");
  for(String part:List.of(namespace,template.toString(),"images")){
   path=path.resolve(part);if(Files.isSymbolicLink(path))throw new IOException("Unsafe storage");
   if(create)Files.createDirectories(path);
  }return path;
 }
 private Path path(UUID template,String name,boolean create)throws IOException{return directory(template,create).resolve(ImageNames.validate(name));}
 public void write(UUID template,String name,PhotoFiles.Image image){
  write(template,name,image.bytes());
 }
 public void write(UUID template,String name,byte[] bytes){
  Path target=null;boolean created=false;
  try{
   target=path(template,name,true);
   try(var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){created=true;output.write(bytes);}
  }catch(FileAlreadyExistsException conflict){throw new AuthFailure(409,"TEMPLATE_IMAGE_NAME_CONFLICT");}
  catch(IOException failure){if(created)try{Files.deleteIfExists(target);}catch(IOException ignored){}throw new AuthFailure(503,"PHOTO_STORAGE_UNAVAILABLE");}
 }
 public byte[] read(UUID template,String name){
  try(var input=Files.newInputStream(path(template,name,false),LinkOption.NOFOLLOW_LINKS)){
   byte[] bytes=input.readNBytes(52428800+1);if(bytes.length>52428800)throw new IOException("Invalid stored size");return bytes;
  }catch(IOException failure){throw new AuthFailure(503,"PHOTO_STORAGE_UNAVAILABLE");}
 }
 public void remove(UUID template,String name){try{Files.deleteIfExists(path(template,name,false));var dir=directory(template,false);Files.deleteIfExists(dir);Files.deleteIfExists(dir.getParent());}
  catch(IOException ignored){/* Locked orphan cleanup retries failed unlink; nonempty directories remain. */}}
 public int cleanup(Instant cutoff,BiPredicate<UUID,String> referenced){
  Path base=root.resolve(namespace);if(!Files.exists(base,LinkOption.NOFOLLOW_LINKS))return 0;int removed=0;
  try(var paths=Files.walk(base,3)){
   for(Path path:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList()){
    var relative=base.relativize(path);if(relative.getNameCount()!=3||!relative.getName(1).toString().equals("images"))continue;
    UUID template;try{template=UUID.fromString(relative.getName(0).toString());}catch(IllegalArgumentException ignored){continue;}
    String name=ImageNames.validate(path.getFileName().toString());directory(template,false);
    if(Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)&&!referenced.test(template,name)
      &&Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)&&!referenced.test(template,name)){Files.delete(path);removed++;}
   }return removed;
  }catch(IOException failure){throw new AuthFailure(503,"PHOTO_STORAGE_UNAVAILABLE");}
 }
 public void removeAfterCommit(List<TemplateStore.Image> images){
  if(images.isEmpty())return;
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
   @Override public void afterCompletion(int status){if(status==STATUS_COMMITTED)try{files.exclusive(()->{images.forEach(i->remove(i.templateId(),i.fileName()));return null;});}
    catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(TemplateImageFiles.class).warn("Template image file removal deferred to orphan cleanup");}}
  });
 }
}
