package cz.mh.rallye.rallye_backend.races.application;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import cz.mh.rallye.rallye_backend.templates.application.TemplateImageFiles;
/** Separate private root and namespace; same validated immutable file operations as templates. */
@Component
public class EventFiles {
 public final TemplateImageFiles storage;
 public EventFiles(@Value("${rallye.races.file-directory:${user.home}/.rallye/event-files}") String root){storage=new TemplateImageFiles(root,"race-events");}
}
