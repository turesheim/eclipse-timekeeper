package net.resheim.timekeeper.server;

import java.util.List;

/** Parse the raw headers so duplicate host cookies cannot select another authority. */
final class BrowserCookies {
    static final String BROWSER="__Host-timekeeper-browser";
    private static final String SESSION="__Host-timekeeper-session";
    record Values(String rawSession,String rawBrowser) {
        String session() {
            if(rawSession!=null && !secret(rawSession)) throw ApiFailure.unauthorized(false);
            return rawSession;
        }
        // An invalid or expired session must not prevent a fresh public sign-in.
        String replacementSession() {return secret(rawSession) ? rawSession : null;}
        String browser() {return secret(rawBrowser) ? rawBrowser : null;}
        String requireBrowser() {
            String value=browser();if(value==null) throw new ApiFailure(403,"CSRF_FAILED");return value;
        }
        @Override public String toString() {return "Browser cookies";}
    }
    private static boolean secret(String value) {return value!=null && value.matches("[A-Za-z0-9_-]{43}");}
    static Values read(List<String> headers) {
        String session=null,browser=null;boolean seenSession=false,seenBrowser=false;int length=0;
        for(String header:headers==null ? List.<String>of() : headers) {
            if(header==null || header.length()>8192 || (length+=header.length())>32768
                    || header.indexOf('\r')>=0 || header.indexOf('\n')>=0) throw ApiFailure.badRequest();
            for(String part:header.split(";",-1)) {
                String field=part.strip();int equals=field.indexOf('=');
                String name=equals<0 ? field : field.substring(0,equals);
                boolean sid=name.strip().equals(SESSION),bid=name.strip().equals(BROWSER);
                if(!sid && !bid) continue;
                if(equals<0 || !name.equals(name.strip())) throw ApiFailure.badRequest();
                String value=field.substring(equals+1);
                if(sid) {if(seenSession) throw ApiFailure.badRequest();seenSession=true;session=value;}
                else {if(seenBrowser) throw ApiFailure.badRequest();seenBrowser=true;browser=value;}
            }
        }
        return new Values(session,browser);
    }
    static String bindingCookie(String secret) {
        if(!secret(secret)) throw ApiFailure.validation();
        return BROWSER+"="+secret+"; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=2592000";
    }
    static String clearBindingCookie() {return BROWSER+"=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0";}
}
