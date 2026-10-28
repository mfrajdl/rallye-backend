package cz.mh.rallye.rallye_backend.templates.application;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
public final class TemplateDocumentHtml {
 private TemplateDocumentHtml(){}
 private static final String CSS="body{font:1rem/1.6 system-ui,sans-serif;max-width:60rem;margin:2rem auto;padding:0 1rem;color:#202124}img{max-width:100%;height:auto}pre{overflow:auto;padding:1rem;background:#f5f5f5}table{display:block;overflow:auto;border-collapse:collapse}th,td{border:1px solid #ccc;padding:.4rem}blockquote{border-left:3px solid #ccc;margin-left:0;padding-left:1rem}a{overflow-wrap:anywhere}h1,h2,h3,h4{line-height:1.3}";
 public static String csp(){try{return "default-src 'none'; script-src 'none'; style-src 'sha256-"+Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(CSS.getBytes(StandardCharsets.UTF_8)))+"'; img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; sandbox allow-same-origin allow-popups allow-popups-to-escape-sandbox";}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 public static String page(String title,String body){return "<!doctype html><html lang=\"cs\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"+org.jsoup.nodes.Entities.escape(title)+"</title><style>"+CSS+"</style></head><body>"+body+"</body></html>";}
}
