package cz.mh.rallye.rallye_backend.identity.application;

import java.net.URI;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Separate, server-owned mobile destinations. Empty configuration keeps mobile start unavailable. */
@ConfigurationProperties("rallye.auth.external.mobile")
public record MobileAuthSettings(String launchUri,Map<String,Target> returns) {
    public record Target(String returnUri,String profileUri,String fallbackUri) {}
    public MobileAuthSettings { returns=returns==null?Map.of():Map.copyOf(returns); }
    public Target target(String id) {
        Target target=returns.get(id);
        if(target==null)throw unavailable();
        https(target.returnUri());https(target.profileUri());https(target.fallbackUri());
        return target;
    }
    public String checkedLaunchUri() {
        URI uri=https(launchUri);
        if(!"/api/v1/auth/external/mobile/launch".equals(uri.getPath()))throw unavailable();
        return launchUri;
    }
    private static URI https(String value) {
        try {
            URI uri=URI.create(value);
            if(value.length()>2048 || !"https".equals(uri.getScheme()) || uri.getHost()==null
                    || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)throw unavailable();
            return uri;
        } catch(IllegalArgumentException|NullPointerException e){throw unavailable();}
    }
    private static AuthFailure unavailable(){return new AuthFailure(503,"EXTERNAL_CONFIGURATION_INVALID");}
}
