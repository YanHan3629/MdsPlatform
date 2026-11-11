package com.fwdrobo.sirius.security;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.util.*;
/** Revalidate before the first byte, each 64 KiB / one second and every flush.
 * Previously transmitted bytes cannot be recalled. Interrupted bodies never
 * become a successful complete download.
 */
public final class LiveAuthorizationOutputFilter extends OncePerRequestFilter {
 private final AuthorizationService auth;private final ObjectMapper json;
 public LiveAuthorizationOutputFilter(AuthorizationService auth,ObjectMapper json){this.auth=auth;this.json=json;}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  GuardedResponse guarded=new GuardedResponse(request,response);
  try {chain.doFilter(request,guarded);}
  catch(IOException|ServletException|RuntimeException error){
   if(guarded.denial!=null&&!response.isCommitted()){
    int status=guarded.denial.getStatusCode().value();response.reset();response.setStatus(status);
    response.setCharacterEncoding("UTF-8");response.setContentType("application/json");
    response.setHeader("Cache-Control","no-store, private");response.setHeader("Pragma","no-cache");
    json.writeValue(response.getOutputStream(),Map.of("success",false,"status",status,
      "errorType",status==401?"ACCESS_REVOKED_OR_EXPIRED":status==503?"AUTHORITY_UNAVAILABLE":"AUTHORIZATION_CHANGED_DURING_OUTPUT"));
    return;
   }
   throw error;
  }
 }
 private final class GuardedResponse extends HttpServletResponseWrapper {
  private final HttpServletRequest request;private ServletOutputStream stream;private PrintWriter writer;
  private boolean streamRequested,writerRequested;private long checkedAt,bytesSinceCheck;
  private ResponseStatusException denial;
  GuardedResponse(HttpServletRequest request,HttpServletResponse response){super(response);this.request=request;}
  private void check(boolean force){
   if(denial!=null)throw denial;
   Object value=request.getAttribute(AuthorizationService.REQUEST_OPERATION);
   if(!(value instanceof String operation)||!OperationCatalog.isKnown(operation))return;
   long now=System.nanoTime();
   if(!force&&checkedAt!=0&&now-checkedAt<1_000_000_000L&&bytesSinceCheck<65536)return;
   try {
    if(!(request.getAttribute("ds.liveAuthentication") instanceof Runnable live))
     throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"REQUEST_AUTHENTICATION_REQUIRED");
    live.run();
    if(!(request.getAttribute("ds.actor") instanceof AuthorizationService.Actor actor))
     throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"REQUEST_IDENTITY_REQUIRED");
    auth.requireOperation(actor,operation);auth.revalidateRequest(request);
    checkedAt=now;bytesSinceCheck=0;
   } catch(ResponseStatusException revoked){denial=revoked;throw revoked;}
   catch(DataAccessException unavailable){denial=new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AUTHORITY_UNAVAILABLE",unavailable);throw denial;}
  }
  private ServletOutputStream stream()throws IOException{
   if(stream==null){
    ServletOutputStream target=super.getOutputStream();
    stream=new ServletOutputStream(){
     @Override public boolean isReady(){return target.isReady();}
     @Override public void setWriteListener(WriteListener listener){target.setWriteListener(listener);}
     @Override public void write(int value)throws IOException{check(false);target.write(value);bytesSinceCheck++;}
     @Override public void write(byte[] buffer,int offset,int length)throws IOException{
      Objects.checkFromIndexSize(offset,length,buffer.length);
      while(length>0){check(false);int count=Math.min(length,65536);target.write(buffer,offset,count);offset+=count;length-=count;bytesSinceCheck+=count;}
     }
     @Override public void flush()throws IOException{check(true);target.flush();}
     @Override public void close()throws IOException{check(true);target.close();}
    };
   }
   return stream;
  }
  @Override public ServletOutputStream getOutputStream()throws IOException{
   if(writerRequested)throw new IllegalStateException("Writer already requested");streamRequested=true;return stream();
  }
  @Override public PrintWriter getWriter()throws IOException{
   if(streamRequested)throw new IllegalStateException("Output stream already requested");writerRequested=true;
   if(writer==null)writer=new PrintWriter(new OutputStreamWriter(stream(),getCharacterEncoding()));
   return writer;
  }
  @Override public void flushBuffer()throws IOException{check(true);if(writer!=null)writer.flush();super.flushBuffer();}
 }
}
