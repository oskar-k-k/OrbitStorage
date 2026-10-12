package com.orbitstorage.monitoring;

import java.util.*;
import com.orbitstorage.auth.Access;
import com.orbitstorage.usage.UsageService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class MonitoringController {
    private final UsageService usage;
    public MonitoringController(UsageService usage) { this.usage=usage; }
    @GetMapping("/usage") public Map<String,Object> usage() { return usage.project(Access.project("usage:read")); }
    @GetMapping("/admin/storage/overview") public Map<String,Object> overview() { return usage.overview(); }
    @GetMapping("/admin/storage/providers") public List<Map<String,Object>> providers() { return usage.providers(); }
    @GetMapping("/admin/storage/projects") public List<Map<String,Object>> projects() { return usage.projects(); }
}
