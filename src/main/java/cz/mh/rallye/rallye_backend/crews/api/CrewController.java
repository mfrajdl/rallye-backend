package cz.mh.rallye.rallye_backend.crews.api;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonSetter;
import cz.mh.rallye.rallye_backend.crews.application.CrewService;
import cz.mh.rallye.rallye_backend.identity.application.AuthFailure;
import cz.mh.rallye.rallye_backend.identity.application.AuthService.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/crews")
public class CrewController {
    public static class Create {
        public String name,registrationPlate,description;
        private UUID captain;
        private boolean specified;
        @JsonSetter("captainAccountId") public void captain(UUID value) { captain=value;specified=true; }
    }
    public record Update(String name,String registrationPlate,String description,Long version) { }
    public record Captain(@com.fasterxml.jackson.annotation.JsonProperty(required=true) UUID captainAccountId,Long version) { }
    private final CrewService service;
    public CrewController(CrewService service) { this.service=service; }
    private long required(Long value) {
        if(value==null||value<0) throw new AuthFailure(400,"INVALID_REQUEST");return value;
    }
    @PostMapping public ResponseEntity<CrewService.View> create(@AuthenticationPrincipal Principal actor,
            @RequestHeader("Idempotency-Key") UUID key,@RequestBody Create input) {
        var result=service.create(actor,key,input.name,input.registrationPlate,input.description,input.specified,input.captain);
        return ResponseEntity.created(URI.create("/api/v1/crews/"+result.id())).body(result);
    }
    @GetMapping public CrewService.Page list(@AuthenticationPrincipal Principal actor,
            @RequestParam(defaultValue="own") String scope,@RequestParam(required=false) UUID captainId,
            @RequestParam(defaultValue="false") boolean orphaned,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="50") int size) {
        if(!scope.equals("own")&&!scope.equals("all")) throw new AuthFailure(400,"INVALID_REQUEST");
        return service.list(actor,scope.equals("all"),captainId,orphaned,page,size);
    }
    @GetMapping("/{id}") public CrewService.View detail(@AuthenticationPrincipal Principal actor,@PathVariable UUID id) {
        return service.detail(actor,id);
    }
    @PutMapping("/{id}") public CrewService.View update(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@RequestBody Update input) {
        return service.update(actor,id,required(input.version()),input.name(),input.registrationPlate(),input.description());
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@RequestParam long version) {
        service.delete(actor,id,required(version));return ResponseEntity.noContent().build();
    }
    @PutMapping("/{id}/captain") public CrewService.View captain(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key,@RequestBody Captain input) {
        return service.captain(actor,id,required(input.version()),input.captainAccountId(),key);
    }
    @PutMapping(value="/{id}/photo",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public CrewService.View upload(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@RequestParam long version,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        try(var input=file.getInputStream()) { return service.upload(actor,id,required(version),input); }
    }
    @GetMapping("/{id}/photo") public ResponseEntity<byte[]> photo(@AuthenticationPrincipal Principal actor,@PathVariable UUID id) {
        var result=service.photo(actor,id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(result.contentType()))
                .header("Content-Disposition","inline").header("X-Content-Type-Options","nosniff").body(result.bytes());
    }
    @DeleteMapping("/{id}/photo") public CrewService.View deletePhoto(@AuthenticationPrincipal Principal actor,@PathVariable UUID id,@RequestParam long version) {
        return service.deletePhoto(actor,id,required(version));
    }
    @PostMapping("/photo-cleanups") public Map<String,Integer> cleanup(@AuthenticationPrincipal Principal actor) {
        return Map.of("removed",service.cleanup(actor));
    }
}
