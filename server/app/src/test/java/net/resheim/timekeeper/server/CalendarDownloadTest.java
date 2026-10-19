package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;

class CalendarDownloadTest {
    static final String ICS="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nEND:VCALENDAR\r\n";
    static final class Fixture implements AutoCloseable {
        final HttpServer server;final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();final String origin;
        Fixture()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);origin="http://127.0.0.1:"+server.getAddress().getPort();server.start();}
        void on(String path,HttpHandler handler){server.createContext(path,handler);}
        public void close(){server.stop(0);executor.shutdownNow();}
    }
    static void reply(HttpExchange exchange,int status,byte[] body)throws java.io.IOException{
        try(exchange){exchange.getResponseHeaders().add("Content-Type","text/calendar; charset=utf-8");exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);}
    }
    static void reply(HttpExchange exchange,int status,String body)throws java.io.IOException{reply(exchange,status,body.getBytes(StandardCharsets.UTF_8));}
    @Test void schemesCredentialsFragmentsAndOperatorOriginsAreExplicit(){
        var policy=new CalendarDownload("");
        assertEquals("https://example.org/private%2Fcalendar?token=a%2Fb",policy.address("webcal://example.org/private%2Fcalendar?token=a%2Fb").toString());
        assertEquals("https://example.org?token=a%2Fb",policy.address("https://example.org?token=a%2Fb").toString());
        for(String url:List.of("http://example.org/feed","file:///etc/passwd","ftp://example.org/feed","https://user:password@example.org/feed","https://example.org/feed#private"," https://example.org/feed","https://example.org:0/feed"))assertThrows(CalendarFailure.class,()->policy.address(url),url);
        assertDoesNotThrow(()->new CalendarDownload("http://127.0.0.1:9876").address("http://127.0.0.1:9876/feed"));
        assertThrows(CalendarFailure.class,()->new CalendarDownload("http://127.0.0.1:9876").address("http://127.0.0.1:9877/feed"));
        assertThrows(IllegalArgumentException.class,()->new CalendarDownload("http://127.0.0.1/private"));
    }
    @Test void publicAddressPolicyBlocksPrivateMetadataReservedAndTransitionDestinations()throws Exception{
        for(String ip:List.of("0.0.0.0","127.0.0.1","10.1.2.3","172.16.0.1","192.168.1.1","169.254.169.254","100.100.100.200","198.18.0.1","192.0.2.1","198.51.100.1","203.0.113.1","168.63.129.16","224.0.0.1","255.255.255.255","::1","::","fe80::1","fc00::1","::ffff:127.0.0.1","2001:db8::1","2002:7f00:1::1"))assertFalse(CalendarDownload.publicAddress(InetAddress.getByName(ip)),ip);
        for(String ip:List.of("8.8.8.8","1.1.1.1","2606:4700:4700::1111"))assertTrue(CalendarDownload.publicAddress(InetAddress.getByName(ip)),ip);
        assertEquals("CALENDAR_URL_BLOCKED",assertThrows(CalendarFailure.class,()->new CalendarDownload("").get("https://127.0.0.1/private",null,null,false)).code);
    }
    @Test void approvedOriginPinsDnsAndRetainsTheOriginalHostWithoutForwardingCredentials()throws Exception{
        try(var fixture=new Fixture()){
            int port=fixture.server.getAddress().getPort();String origin="http://calendar-fixture.invalid:"+port;var lookups=new AtomicInteger();var host=new AtomicReference<String>();
            fixture.on("/feed",exchange->{host.set(exchange.getRequestHeaders().getFirst("Host"));assertNull(exchange.getRequestHeaders().getFirst("Authorization"));assertNull(exchange.getRequestHeaders().getFirst("Cookie"));assertNull(exchange.getRequestHeaders().getFirst("Referer"));assertEquals("access=a%2Fb",exchange.getRequestURI().getRawQuery());exchange.getResponseHeaders().add("ETag","\"one\"");reply(exchange,200,ICS);});
            var download=new CalendarDownload(origin,name->{assertEquals("calendar-fixture.invalid",name);assertEquals(1,lookups.incrementAndGet());return new InetAddress[]{InetAddress.getByName("127.0.0.1")};});
            var result=download.get(origin+"/feed?access=a%2Fb",null,null,false);assertEquals(ICS,result.body());assertEquals("\"one\"",result.etag());assertEquals("calendar-fixture.invalid:"+port,host.get());assertEquals(1,lookups.get());
        }
    }
    @Test void conditionalRequestsAndRelativeRedirectsWorkWithoutCookieReuse()throws Exception{
        try(var fixture=new Fixture()){
            var seen=new AtomicReference<String>();
            fixture.on("/redirect",exchange->{try(exchange){exchange.getResponseHeaders().add("Location","/feed");exchange.getResponseHeaders().add("Set-Cookie","external=secret");exchange.sendResponseHeaders(302,-1);}});
            fixture.on("/feed",exchange->{assertNull(exchange.getRequestHeaders().getFirst("Cookie"));seen.set(exchange.getRequestHeaders().getFirst("If-None-Match"));if("\"one\"".equals(seen.get())){try(exchange){exchange.sendResponseHeaders(304,-1);}}else{exchange.getResponseHeaders().add("ETag","\"one\"");reply(exchange,200,ICS);}});
            var download=new CalendarDownload(fixture.origin);assertEquals(ICS,download.get(fixture.origin+"/redirect",null,null,false).body());
            var unchanged=download.get(fixture.origin+"/feed","\"one\"",null,true);assertTrue(unchanged.unchanged());assertNull(unchanged.body());assertEquals("\"one\"",seen.get());
            assertEquals("CALENDAR_INVALID_FEED",assertThrows(CalendarFailure.class,()->download.get(fixture.origin+"/feed","\"one\"",null,false)).code);
        }
    }
    @Test void everyRedirectIsRevalidatedAndRedirectLoopsAreBounded()throws Exception{
        try(var fixture=new Fixture()){
            fixture.on("/private",exchange->{try(exchange){exchange.getResponseHeaders().add("Location","https://169.254.169.254/private");exchange.sendResponseHeaders(302,-1);}});
            fixture.on("/loop",exchange->{try(exchange){exchange.getResponseHeaders().add("Location","/loop");exchange.sendResponseHeaders(302,-1);}});
            var download=new CalendarDownload(fixture.origin);
            assertEquals("CALENDAR_URL_BLOCKED",assertThrows(CalendarFailure.class,()->download.get(fixture.origin+"/private",null,null,false)).code);
            assertEquals("CALENDAR_REDIRECT_FAILED",assertThrows(CalendarFailure.class,()->download.get(fixture.origin+"/loop",null,null,false)).code);
        }
    }
    @Test void oversizedMalformedAndDeniedResponsesExposeOnlyStableCodes()throws Exception{
        try(var fixture=new Fixture()){
            fixture.on("/large",exchange->{try(exchange){exchange.sendResponseHeaders(200,CalendarFeed.MAX_BYTES+1L);}});
            fixture.on("/invalid",exchange->reply(exchange,200,new byte[]{(byte)0xff}));
            fixture.on("/denied",exchange->reply(exchange,403,"secret URL and remote diagnostics must stay private"));
            var download=new CalendarDownload(fixture.origin);
            for(var pair:Map.of("/large","CALENDAR_TOO_LARGE","/invalid","CALENDAR_INVALID_FEED","/denied","CALENDAR_ACCESS_DENIED").entrySet()){
                var failure=assertThrows(CalendarFailure.class,()->download.get(fixture.origin+pair.getKey()+"?token=private",null,null,false));assertEquals(pair.getValue(),failure.code);assertFalse(failure.getMessage().contains("private"));assertNull(failure.getCause());
            }
        }
    }
}
