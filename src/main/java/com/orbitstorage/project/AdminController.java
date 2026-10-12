package com.orbitstorage.project;

import java.util.*;
import jakarta.validation.Valid;
import com.orbitstorage.provider.ProviderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final ProjectService projects;
    private final ProviderService providers;
    public AdminController(ProjectService projects, ProviderService providers) { this.projects=projects; this.providers=providers; }
    @GetMapping("/projects") public List<Map<String,Object>> projects() { return projects.list(); }
    @PostMapping("/projects") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createProject(@Valid @RequestBody ProjectService.CreateProject req) { return projects.create(req); }
    @PatchMapping("/projects/{id}") public Map<String,Object> update(@PathVariable UUID id, @Valid @RequestBody ProjectService.UpdateProject req) { return projects.update(id,req); }
    @PostMapping("/projects/{id}/keys") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> key(@PathVariable UUID id, @Valid @RequestBody ProjectService.CreateKey req) { return projects.createKey(id,req); }
    @GetMapping("/projects/{id}/keys") public List<Map<String,Object>> keys(@PathVariable UUID id) { return projects.keys(id); }
    @DeleteMapping("/projects/{id}/keys/{keyId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id, @PathVariable UUID keyId) { projects.revoke(id,keyId); }
    @PostMapping("/providers") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> provider(@Valid @RequestBody ProviderService.CreateProvider req) { return providers.create(req); }
}
