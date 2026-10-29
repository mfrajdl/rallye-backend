package cz.mh.rallye.rallye_backend.templates.api;
import java.io.*;import java.net.URI;import java.util.UUID;
import org.springframework.web.bind.annotation.*;import org.springframework.http.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.web.multipart.MultipartFile;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;import cz.mh.rallye.rallye_backend.templates.application.*;
@RestController @RequestMapping("/api/v1/race-templates")
public class TemplateTransferController {
 private final TemplateTransferService service;public TemplateTransferController(TemplateTransferService service){this.service=service;}
 @GetMapping("/{id}/export") public ResponseEntity<byte[]> export(@AuthenticationPrincipal Principal actor,@PathVariable UUID id){return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).cacheControl(CacheControl.noStore()).header("Content-Disposition","attachment; filename=\"rallye-template.zip\"").header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer").body(service.export(actor,id));}
 @PostMapping(value="/imports/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ResponseEntity<TemplateArchive.Preview> preview(@AuthenticationPrincipal Principal actor,@RequestParam("file") MultipartFile file)throws IOException{try(var input=file.getInputStream()){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preview(actor,input));}}
 @PostMapping(value="/imports",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ResponseEntity<TemplateService.Created> create(@AuthenticationPrincipal Principal actor,@RequestHeader("Idempotency-Key")UUID key,@RequestParam("file")MultipartFile file)throws IOException{try(var input=file.getInputStream()){var result=service.importTemplate(actor,key,input);return ResponseEntity.created(URI.create("/api/v1/race-templates/"+result.id())).cacheControl(CacheControl.noStore()).body(result);}}
}
