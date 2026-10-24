package cz.mh.rallye.rallye_backend.identity.application;

import java.net.URI;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.*;

@Configuration
@EnableConfigurationProperties(ExternalProviders.Settings.class)
public class ExternalProviders {
    public enum Provider { GOOGLE, APPLE }
    @ConfigurationProperties("rallye.auth.external")
    public record Settings(Map<Provider,Client> providers, Map<String,String> returns) {
        public Settings { providers=providers==null?Map.of():Map.copyOf(providers); returns=returns==null?Map.of():Map.copyOf(returns); }
        @Override public String toString(){return "ExternalSettings[redacted]";}
    }
    public record Client(boolean enabled,String clientId,String clientSecret,String callbackUri) {
        @Override public String toString(){return "ExternalClient[redacted]";}
    }
    private final Settings settings;
    public ExternalProviders(Settings settings){this.settings=settings;}
    public ClientRegistration registration(Provider provider) {
        Client c=settings.providers().get(provider);
        if(c==null || !c.enabled())throw new AuthFailure(503,"EXTERNAL_PROVIDER_DISABLED");
        if(c.clientId()==null || c.clientId().isBlank() || c.clientSecret()==null || c.clientSecret().isBlank())throw unavailable();
        URI uri=uri(c.callbackUri());
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getQuery()!=null || uri.getFragment()!=null || uri.getUserInfo()!=null)throw unavailable();
        if(provider==Provider.APPLE && (uri.getHost().equals("localhost") || uri.getHost().matches("[0-9.]+")))throw unavailable();
        String issuer=issuer(provider);
        return ClientRegistration.withRegistrationId(provider.name().toLowerCase(java.util.Locale.ROOT))
            .clientId(c.clientId()).clientSecret(c.clientSecret()).clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri(c.callbackUri())
            .scope(provider==Provider.GOOGLE?new String[]{"openid","email","profile"}:new String[]{"email","name"})
            .authorizationUri(provider==Provider.GOOGLE?"https://accounts.google.com/o/oauth2/v2/auth":issuer+"/auth/authorize")
            .tokenUri(provider==Provider.GOOGLE?"https://oauth2.googleapis.com/token":issuer+"/auth/token")
            .jwkSetUri(provider==Provider.GOOGLE?"https://www.googleapis.com/oauth2/v3/certs":issuer+"/auth/keys")
            .issuerUri(issuer).build();
    }
    public String returnUri(String name) {
        String value=settings.returns().get(name); URI uri=uri(value);
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)throw unavailable();
        return value;
    }
    public static String issuer(Provider p){return p==Provider.GOOGLE?"https://accounts.google.com":"https://appleid.apple.com";}
    private static URI uri(String value){try{return URI.create(value);}catch(RuntimeException e){throw unavailable();}}
    private static AuthFailure unavailable(){return new AuthFailure(503,"EXTERNAL_CONFIGURATION_INVALID");}
}
