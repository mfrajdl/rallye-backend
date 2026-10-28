package cz.mh.rallye.rallye_backend.templates.application;

import java.util.*;
import cz.mh.rallye.rallye_backend.templates.persistence.TemplateStore.*;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;

/** Derived from immutable document snapshots/current stage sources under the parent lock.
 * No target FK is needed for a forward reference; no recursive document expansion occurs. */
public final class TemplateFileReferences {
 private TemplateFileReferences(){}
 public record Warning(String code,String sourceType,UUID sourceStageId,String sourceFileName,String targetFileName,String referenceType) {}
 public static Map<String,String> names(List<Image> files){var result=new HashMap<String,String>();files.forEach(f->result.put(f.nameKey(),f.fileName()));return result;}
 public static void validate(InstructionsMarkdown.Parsed parsed,List<Image> files){
  var known=new HashMap<String,Image>();files.forEach(f->known.put(f.nameKey(),f));
  for(var ref:parsed.references().values()){
   var file=known.get(ref.key());String lower=ref.fileName().toLowerCase(Locale.ROOT);
   if(ref.image()&&file!=null&&!file.isImage())throw new AuthFailure(400,"TEMPLATE_FILE_REFERENCE_INVALID");
   if(file==null&&!(lower.endsWith(".jpg")||lower.endsWith(".jpeg")||lower.endsWith(".png")||(!ref.image()&&(lower.endsWith(".md")||lower.endsWith(".pdf")))))throw new AuthFailure(400,"TEMPLATE_FILE_REFERENCE_INVALID");
  }
 }
 private static List<Warning> missing(UUID template,String text,UUID stage,String file,List<Image> known){
  var parsed=InstructionsMarkdown.analyzeStored(template,text);var keys=known.stream().map(Image::nameKey).collect(java.util.stream.Collectors.toSet());var result=new LinkedHashSet<Warning>();
  for(var ref:parsed.references().values())if(!keys.contains(ref.key()))result.add(new Warning("FILE_NOT_FOUND",stage==null?"MARKDOWN_FILE":"STAGE",stage,file,ref.fileName(),ref.image()?"IMAGE":"LINK"));return List.copyOf(result);
 }
 public static List<Warning> stage(UUID template,Stage stage,List<Image> files){return missing(template,stage.instructionsMarkdown(),stage.id(),null,files);}
 public static List<Warning> warnings(UUID template,List<Stage> stages,List<Image> files){
  var result=new ArrayList<Warning>();for(var stage:stages)result.addAll(stage(template,stage,files));
  files.stream().filter(Image::isMarkdown).sorted(Comparator.comparing(Image::nameKey)).forEach(file->result.addAll(missing(template,file.markdownSource(),null,file.fileName(),files)));return List.copyOf(result);
 }
 public static Set<String> usedKeys(UUID template,List<Stage> stages,List<Image> files){
  var result=new HashSet<String>();for(var stage:stages)for(var ref:InstructionsMarkdown.analyzeStored(template,stage.instructionsMarkdown()).references().values())result.add(ref.key());
  for(var file:files)if(file.isMarkdown())for(var ref:InstructionsMarkdown.analyzeStored(template,file.markdownSource()).references().values())if(!ref.key().equals(file.nameKey()))result.add(ref.key());return Set.copyOf(result);
 }
 public static boolean used(UUID template,Image target,List<Stage> stages,List<Image> files){return usedKeys(template,stages,files).contains(target.nameKey());}
 public static Set<UUID> imageIds(UUID template,String text,List<Image> files){
  var parsed=InstructionsMarkdown.analyzeStored(template,text);validate(parsed,files);var keys=parsed.imageKeys();return files.stream().filter(Image::isImage).filter(f->keys.contains(f.nameKey())).map(Image::id).collect(java.util.stream.Collectors.toSet());
 }
 public static TemplateReadiness.Report readiness(Template template,List<Stage> stages,List<Image> files){
  var base=TemplateReadiness.check(template,stages);var errors=new ArrayList<>(base.errors());
  for(var stage:stages)try{validate(InstructionsMarkdown.analyze(template.id(),stage.instructionsMarkdown()),files);}catch(AuthFailure e){errors.add(new TemplateReadiness.Error(stage.id(),"instructionsMarkdown","INVALID","Nepovolený odkaz."));}
  for(var file:files)if(file.isMarkdown())try{validate(InstructionsMarkdown.analyzeDocument(template.id(),file.markdownSource()),files);}catch(AuthFailure e){errors.add(new TemplateReadiness.Error(null,"files","INVALID","Nepovolený odkaz v dokumentu."));}
  for(var warning:warnings(template.id(),stages,files))errors.add(new TemplateReadiness.Error(warning.sourceStageId(),warning.sourceStageId()==null?"files":"instructionsMarkdown","MISSING_FILE","Chybí soubor: "+warning.targetFileName()));
  return new TemplateReadiness.Report(template.id(),template.version(),errors.isEmpty(),errors);
 }
}
