package cz.mh.rallye.rallye_backend.templates.application;

import java.util.*;
import cz.mh.rallye.rallye_backend.crews.application.PhotoFiles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

/** Reuses the crew image decoder, metadata stripping, immutable files and storage lock. */
@Component
public class TemplateImageFiles {
 public final PhotoFiles files;
 public TemplateImageFiles(@Value("${rallye.templates.image-directory:${user.home}/.rallye/template-images}") String directory){files=new PhotoFiles(directory);}
 public void removeAfterCommit(List<UUID> ids){
  if(ids.isEmpty())return;
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
   @Override public void afterCompletion(int status){if(status==STATUS_COMMITTED)try{files.exclusive(()->{ids.forEach(files::remove);return null;});}
    catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(TemplateImageFiles.class).warn("Template image file removal deferred to orphan cleanup");}}
  });
 }
}
