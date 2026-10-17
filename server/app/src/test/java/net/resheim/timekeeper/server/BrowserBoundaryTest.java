package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrowserBoundaryTest {
    private static final String SID="__Host-timekeeper-session",BID=BrowserCookies.BROWSER;
    private static final String A="A".repeat(43),B="B".repeat(43);
    private static void reject(int status,Runnable work) {assertEquals(status,assertThrows(ApiFailure.class,work::run).status);}
    @Test void cookiesAreUnambiguousAcrossEveryRawHeaderAndNeverPrinted() {
        var cookies=BrowserCookies.read(List.of("other=ignored; "+SID+"="+A,BID+"="+B));
        assertEquals(A,cookies.session());assertEquals(A,cookies.replacementSession());assertEquals(B,cookies.requireBrowser());
        assertFalse(cookies.toString().contains(A));assertFalse(cookies.toString().contains(B));
        for(String name:List.of(SID,BID)) {
            reject(400,()->BrowserCookies.read(List.of(name+"=",name+"="+A)));
            reject(400,()->BrowserCookies.read(List.of(name+"="+A+"; "+name+"="+A)));
            reject(400,()->BrowserCookies.read(List.of(name)));
            reject(400,()->BrowserCookies.read(List.of(name+" ="+A)));
        }
        reject(400,()->BrowserCookies.read(List.of("other="+"a".repeat(8193))));
        reject(400,()->BrowserCookies.read(List.of(SID+"="+A+"\r\n")));
    }
    @Test void malformedSessionCannotBlockPublicPasswordFallbackButCannotAuthenticate() {
        for(String invalid:List.of("","short",A+"=","\""+A+"\"","a".repeat(44),"a".repeat(42)+"+")) {
            var cookies=BrowserCookies.read(List.of(SID+"="+invalid+"; "+BID+"="+invalid));
            assertNull(cookies.replacementSession());assertNull(cookies.browser());
            reject(401,()->cookies.session());reject(403,()->cookies.requireBrowser());
        }
        var absent=BrowserCookies.read(null);assertNull(absent.session());assertNull(absent.browser());
        assertEquals(BID+"="+B+"; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=2592000",BrowserCookies.bindingCookie(B));
    }
    @Test void cookieReadsCannotUseSameHostDifferentPortOrRetiredHostname() {
        var origins=new BrowserOrigins();origins.publicOrigin="https://timekeeper.example:8443";
        origins.cookie(null,List.of(origins.publicOrigin),null,true);
        origins.cookie(null,null,List.of(origins.publicOrigin+"/web/"),false);
        origins.cookie(null,null,List.of(origins.publicOrigin),false);
        reject(403,()->origins.cookie(null,null,null,false));
        reject(403,()->origins.cookie(null,null,List.of(origins.publicOrigin),true));
        for(String foreign:List.of("https://timekeeper.example","https://timekeeper.example:9443","https://old.example:8443","null")) {
            reject(403,()->origins.cookie(null,List.of(foreign),List.of(origins.publicOrigin),false));
            reject(403,()->origins.cookie(null,null,List.of(foreign),false));
        }
        for(String invalid:List.of("https://user@timekeeper.example:8443/","https://timekeeper.example:8443/#fragment","https://timekeeper.example:8443.evil.example/"))
            reject(403,()->origins.cookie(null,null,List.of(invalid),false));
        reject(403,()->origins.cookie(null,List.of(origins.publicOrigin,origins.publicOrigin),null,true));
        reject(403,()->origins.cookie(null,null,List.of(origins.publicOrigin,origins.publicOrigin),false));
        origins.publicOrigin="NONE";origins.cookie(null,null,null,false);
    }
}
