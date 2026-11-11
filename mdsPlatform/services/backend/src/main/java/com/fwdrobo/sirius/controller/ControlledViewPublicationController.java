package com.fwdrobo.sirius.controller;
import com.fwdrobo.sirius.security.ControlledViewPublicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/controlled-view-publications")
public class ControlledViewPublicationController {
 private final ControlledViewPublicationService publications;
 public ControlledViewPublicationController(ControlledViewPublicationService publications){this.publications=publications;}
 private static String token(HttpServletRequest request){String value=request.getHeader("Authorization");return value!=null&&value.startsWith("Bearer ")?value.substring(7).trim():"";}
 @PostMapping("/review") public Object review(@RequestBody ControlledViewPublicationService.ReviewRequest body)throws Exception{return publications.review(body);}
 @PostMapping("/confirm") public Object confirm(@RequestBody ControlledViewPublicationService.ConfirmRequest body,HttpServletRequest request)throws Exception{return publications.confirm(body,token(request));}
 @GetMapping public Object list(@RequestParam(required=false) Integer maximum){return publications.list(maximum);}
 @GetMapping("/{publicationId}") public Object get(@PathVariable UUID publicationId){return publications.get(publicationId);}
 @PostMapping("/{publicationId}/start-index") public Object start(@PathVariable UUID publicationId,HttpServletRequest request)throws Exception{return publications.start(publicationId,token(request));}
 @PostMapping("/{publicationId}/reconcile") public Object reconcile(@PathVariable UUID publicationId)throws Exception{return publications.reconcile(publicationId);}
 @PostMapping("/{publicationId}/revoke") public Object revoke(@PathVariable UUID publicationId,@RequestBody ControlledViewPublicationService.RevokeRequest body){return publications.revoke(publicationId,body);}
}
