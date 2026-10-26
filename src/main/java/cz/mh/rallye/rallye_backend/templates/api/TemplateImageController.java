package cz.mh.rallye.rallye_backend.templates.api;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import cz.mh.rallye.rallye_backend.templates.application.*;

@RestController
@RequestMapping("/api/v1/race-templates/{id}/images")
public class TemplateImageController {
 private final TemplateImageService service;
 public TemplateImageController(TemplateImageService service){this.service=service;}
 @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 public ResponseEntity<TemplateService.Created> upload(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@RequestParam long version,@RequestHeader("Idempotency-Key") UUID key,@RequestParam("file") MultipartFile file)throws IOException{
  try(var input=file.getInputStream()){var result=service.upload(actor,id,version,key,input);return ResponseEntity.created(URI.create(InstructionsHtml.imageUrl(id,result.id()))).body(result);}
 }
 @GetMapping public List<TemplateImageService.View> list(@AuthenticationPrincipal Principal actor,@PathVariable UUID id){return service.list(actor,id);}
 @GetMapping("/{imageId}") public ResponseEntity<byte[]> content(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@PathVariable UUID imageId){var c=service.content(actor,id,imageId);return ResponseEntity.ok().header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(c.contentType())).body(c.bytes());}
 @DeleteMapping("/{imageId}") public ResponseEntity<Void> delete(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@PathVariable UUID imageId,@RequestParam long version){service.delete(actor,id,imageId,version);return ResponseEntity.noContent().build();}
}
