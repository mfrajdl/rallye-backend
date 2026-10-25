package cz.mh.rallye.rallye_backend.identity;

import java.net.http.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;
import cz.mh.rallye.rallye_backend.identity.application.*;
import cz.mh.rallye.rallye_backend.identity.persistence.AppleRevocationStore.Error;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class AppleRevocationClientTest {
    AppleClientSecrets secrets=mock(AppleClientSecrets.class);HttpClient http=mock(HttpClient.class);
    AppleRevocationClient client=new AppleRevocationClient(secrets,http);
    @SuppressWarnings("unchecked") void response(int status)throws Exception{
        when(secrets.create("client")).thenReturn("synthetic-secret");HttpResponse<Void> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(status);
        when(http.send(any(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any())).thenReturn(response);
    }
    @Test void successUsesFixedEndpointAndEncodedRefreshTokenWithoutAuthorizationHeader()throws Exception{
        response(200);assertTrue(client.revoke("client","synthetic+/&token").success());var capture=ArgumentCaptor.forClass(HttpRequest.class);verify(http).send(capture.capture(),any());var request=capture.getValue();
        assertEquals("https://appleid.apple.com/auth/revoke",request.uri().toString());assertEquals("POST",request.method());assertTrue(request.timeout().orElseThrow().toSeconds()<=10);
        assertTrue(request.headers().firstValue("Authorization").isEmpty());assertEquals("application/x-www-form-urlencoded",request.headers().firstValue("Content-Type").orElseThrow());
        var bytes=new java.io.ByteArrayOutputStream();request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}public void onNext(ByteBuffer b){byte[] value=new byte[b.remaining()];b.get(value);bytes.writeBytes(value);}public void onError(Throwable t){fail(t);}public void onComplete(){}
        });
        String body=bytes.toString(StandardCharsets.UTF_8);assertEquals("client_id=client&client_secret=synthetic-secret&token=synthetic%2B%2F%26token&token_type_hint=refresh_token",body);
        assertFalse(client.revoke("client","synthetic").toString().contains("synthetic"));
    }
    @Test void rateLimitServerAndRequestTimeoutAreRetryable()throws Exception{
        for(int status:new int[]{429,500,503,408}){response(status);var result=client.revoke("client","synthetic");assertTrue(result.retryable());assertFalse(result.success());assertEquals(status,result.httpStatus());}
    }
    @Test void otherResponsesAreBlockedRatherThanFalselyCompleted()throws Exception{
        for(int status:new int[]{201,202,301,400,401,403,404}){response(status);var result=client.revoke("client","synthetic");assertFalse(result.retryable());assertFalse(result.success());}
    }
    @Test void ioFailureRetainsNoProviderDiagnostic()throws Exception{
        when(secrets.create(any())).thenReturn("synthetic-secret");when(http.send(any(),any())).thenThrow(new IOException("synthetic sensitive provider diagnostic"));
        var result=client.revoke("client","synthetic");assertEquals(Error.NETWORK,result.error());assertTrue(result.retryable());assertFalse(result.toString().contains("sensitive"));
    }
    @Test void missingClientContextNeverSendsToken()throws Exception{
        when(secrets.create(any())).thenThrow(new AuthFailure(503,"APPLE_CLIENT_CONFIGURATION_INVALID"));assertEquals(Error.CLIENT_CONFIG,client.revoke("unknown","synthetic").error());verifyNoInteractions(http);
    }
    @Test void wireLoggingPreventsSendingSecrets()throws Exception{
        String name="jdk.httpclient.HttpClient.log",old=System.getProperty(name);when(secrets.create(any())).thenReturn("synthetic-secret");
        try{System.setProperty(name,"all");assertEquals(Error.CLIENT_CONFIG,client.revoke("client","synthetic").error());verifyNoInteractions(http);}
        finally{if(old==null)System.clearProperty(name);else System.setProperty(name,old);}
    }
}
