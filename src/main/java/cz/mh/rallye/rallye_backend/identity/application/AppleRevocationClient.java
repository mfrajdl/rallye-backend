package cz.mh.rallye.rallye_backend.identity.application;

import java.net.*;
import java.net.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import cz.mh.rallye.rallye_backend.identity.persistence.AppleRevocationStore.Error;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Fixed Apple endpoint, no redirects, no body logging and bounded total request time. */
@Component
public class AppleRevocationClient {
    public record Result(Integer httpStatus,Error error,boolean retryable){public boolean success(){return Integer.valueOf(200).equals(httpStatus)&&error==null;}}
    private final AppleClientSecrets secrets;private final HttpClient http;
    @Autowired public AppleRevocationClient(AppleClientSecrets secrets){this(secrets,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());}
    public AppleRevocationClient(AppleClientSecrets secrets,HttpClient http){this.secrets=secrets;this.http=http;}
    public Result revoke(String clientId,String token){
        String clientSecret;
        try{clientSecret=secrets.create(clientId);}catch(AuthFailure failure){return new Result(null,Error.CLIENT_CONFIG,false);}
        if(token==null || token.isBlank())return new Result(null,Error.MATERIAL,false);
        try{
            // JDK wire logging can disclose the form body. Do not issue a request if enabled.
            if(!System.getProperty("jdk.httpclient.HttpClient.log","").isBlank() || !System.getProperty("jdk.internal.httpclient.debug","").isBlank() || !System.getProperty("javax.net.debug","").isBlank())
                return new Result(null,Error.CLIENT_CONFIG,false);
            String form="client_id="+encode(clientId)+"&client_secret="+encode(clientSecret)+"&token="+encode(token)+"&token_type_hint=refresh_token";
            var request=HttpRequest.newBuilder(URI.create("https://appleid.apple.com/auth/revoke")).timeout(Duration.ofSeconds(10))
                .header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
            // Discard even error bodies: they are not needed for safe retry classification and may contain secrets.
            int status=http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
            if(status==200)return new Result(200,null,false);
            if(status==429)return new Result(status,Error.RATE_LIMIT,true);
            if(status==408)return new Result(status,Error.NETWORK,true);
            if(status>=500 && status<=599)return new Result(status,Error.PROVIDER_SERVER,true);
            return new Result(status,status==401||status==403?Error.CLIENT_CONFIG:Error.PROVIDER_REJECTED,false);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return new Result(null,Error.NETWORK,true);}
        catch(IOException failure){return new Result(null,Error.NETWORK,true);}
        catch(RuntimeException failure){return new Result(null,Error.UNEXPECTED,false);}
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
