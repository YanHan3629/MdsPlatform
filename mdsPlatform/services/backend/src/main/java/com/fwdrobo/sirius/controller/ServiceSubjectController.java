package com.fwdrobo.sirius.controller;
import com.fwdrobo.sirius.security.ServiceSubjectManagement;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/identity/service-subjects")
public class ServiceSubjectController {
 private final ServiceSubjectManagement service;
 public ServiceSubjectController(ServiceSubjectManagement service){this.service=service;}
 @GetMapping public Object list(){return service.list();}
 @PostMapping public Object create(@RequestBody ServiceSubjectManagement.Create req){return service.create(req);}
 @PutMapping("/{id}") public Object change(@PathVariable UUID id,@RequestBody ServiceSubjectManagement.Change req){return service.change(id,req);}
 @GetMapping("/{id}/bindings") public Object bindings(@PathVariable UUID id){return service.bindings(id);}
 @PostMapping("/{id}/bindings") public Object bind(@PathVariable UUID id,@RequestBody ServiceSubjectManagement.Bind req){return service.bind(id,req);}
 @PostMapping("/{id}/bindings/{grant}/revoke") public Object revoke(@PathVariable UUID id,@PathVariable UUID grant,@RequestBody ServiceSubjectManagement.Revoke req){return service.revoke(id,grant,req);}
}
