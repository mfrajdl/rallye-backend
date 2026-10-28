package cz.mh.rallye.rallye_backend.templates.api;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.multipart.MultipartFile;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.templates.application.*;
@RestController
@RequestMapping("/api/v1/race-templates/{id}/files")
public class TemplateFileController {
 private final TemplateFileService service;public TemplateFileController(TemplateFileService service){this.service=service;}
 @GetMapping public TemplateFileService.Library list(@AuthenticationPrincipal Principal actor,@PathVariable UUID id){return service.list(actor,id);}
 @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 public ResponseEntity<TemplateFileService.Uploaded> upload(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@RequestParam long version,@RequestHeader("Idempotency-Key") UUID key,@RequestParam("file") MultipartFile file)throws IOException{
  try(var input=file.getInputStream()){var result=service.upload(actor,id,version,key,file.getOriginalFilename(),input);return ResponseEntity.created(URI.create(result.url())).body(result);}
 }
 @GetMapping("/{fileName}") public ResponseEntity<byte[]> content(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@PathVariable String fileName){var result=service.content(actor,id,fileName);
  var response=ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer").header("Content-Security-Policy",result.markdown()?TemplateDocumentHtml.csp():"default-src 'none'; sandbox").contentType(MediaType.parseMediaType(result.contentType()));
  if(result.pdf())response.header("Content-Disposition",ContentDisposition.attachment().filename(result.fileName(),StandardCharsets.UTF_8).build().toString());return response.body(result.bytes());
 }
 @DeleteMapping("/{fileName}") public ResponseEntity<Void> delete(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@PathVariable String fileName,@RequestParam long version){service.delete(actor,id,fileName,version);return ResponseEntity.noContent().build();}
}
