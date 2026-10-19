package net.resheim.timekeeper.server;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

/** No cookies, credentials, automatic redirects or second DNS resolution reach a feed server. */
final class CalendarDownload {
    record Result(boolean unchanged,String body,String etag,String modified) {}
    private static final ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"calendar-download-deadline");thread.setDaemon(true);return thread;});
    private static final ExecutorService DNS=Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> trustedOrigins;
    @FunctionalInterface interface Lookup {InetAddress[] resolve(String host)throws UnknownHostException;}
    private final Lookup lookup;
    CalendarDownload(String allowedOrigins) {this(allowedOrigins,InetAddress::getAllByName);}
    CalendarDownload(String allowedOrigins,Lookup lookup) {
        this.lookup=lookup;
        var values=new HashSet<String>();
        for(String value:allowedOrigins.split(","))if(!value.isBlank()) {
            try {var uri=new URI(value.strip());if(uri.getRawUserInfo()!=null||uri.getHost()==null||uri.getRawQuery()!=null||uri.getFragment()!=null||!Set.of("http","https").contains(uri.getScheme())||!(uri.getRawPath()==null||uri.getRawPath().isEmpty()||uri.getRawPath().equals("/")))throw new IllegalArgumentException();values.add(origin(uri));}
            catch(Exception failure){throw new IllegalArgumentException("Invalid trusted calendar origin configuration");}
        }
        trustedOrigins=Set.copyOf(values);
    }
    URI address(String value) {
        try {
            if(value==null||value.length()>8192||!value.equals(value.strip()))throw new IllegalArgumentException();
            if(value.regionMatches(true,0,"webcal://",0,9))value="https://"+value.substring(9);
            var uri=new URI(value);String host=uri.getHost();
            if(host==null||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null||uri.isOpaque()||host.contains("%")||uri.getPort()==0||uri.getPort()>65535)throw new IllegalArgumentException();
            String scheme=uri.getScheme().toLowerCase(Locale.ROOT);
            if(!scheme.equals("https")&&!(scheme.equals("http")&&trustedOrigins.contains(origin(uri))))throw new CalendarFailure("CALENDAR_URL_BLOCKED");
            return uri;
        } catch(CalendarFailure failure){throw failure;}catch(Exception failure){throw new CalendarFailure("CALENDAR_INVALID_URL");}
    }
    private static String origin(URI uri) {
        int port=uri.getPort()<0?("https".equalsIgnoreCase(uri.getScheme())?443:80):uri.getPort();
        return uri.getScheme().toLowerCase(Locale.ROOT)+"://"+uri.getHost().toLowerCase(Locale.ROOT)+":"+port;
    }
    static boolean publicAddress(InetAddress address) {
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
        byte[] ip=address.getAddress();int a=ip[0]&255,b=ip[1]&255;
        if(ip.length==4) {
            int c=ip[2]&255;
            return a!=0&&a<224&&!(a==100&&b>=64&&b<=127)&&!(a==192&&b==0&&(c==0||c==2))
                    &&!(a==198&&(b==18||b==19||b==51&&c==100))&&!(a==203&&b==0&&c==113)
                    &&!address.getHostAddress().equals("168.63.129.16");
        }
        // Accept ordinary IPv6 global unicast, excluding documentation and
        // transition mechanisms that can tunnel to an otherwise blocked IPv4.
        return (a&0xe0)==0x20&&!(a==0x20&&b==0x02)&&!(a==0x20&&b==0x01&&ip[2]==0&&ip[3]==0)
                &&!(a==0x20&&b==0x01&&(ip[2]&255)==0x0d&&(ip[3]&255)==0xb8)&&!(a==0x3f&&b==0xff);
    }
    InetAddress[] resolved(URI uri) {
        try {
            var pending=DNS.submit(()->lookup.resolve(uri.getHost()));
            InetAddress[] addresses;
            try{addresses=pending.get(5,TimeUnit.SECONDS);}catch(TimeoutException failure){pending.cancel(true);throw new CalendarFailure("CALENDAR_DNS_FAILED");}
            if(addresses.length==0)throw new CalendarFailure("CALENDAR_DNS_FAILED");
            if(!trustedOrigins.contains(origin(uri)))for(var address:addresses)if(!publicAddress(address))throw new CalendarFailure("CALENDAR_URL_BLOCKED");
            return addresses;
        } catch(CalendarFailure failure){throw failure;}catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw new CalendarFailure("CALENDAR_DNS_FAILED");}
    }
    Result get(String url,String etag,String modified,boolean cached) {
        URI current=address(url);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        for(int redirects=0;redirects<=3;redirects++) {
            if(System.nanoTime()>deadline)throw new CalendarFailure("CALENDAR_TIMEOUT");
            InetAddress[] addresses=resolved(current);String host=current.getHost().replace("[","").replace("]","");
            var resolver=new DnsResolver() {
                public InetAddress[] resolve(String name)throws UnknownHostException{if(!host.equalsIgnoreCase(name))throw new UnknownHostException();return addresses.clone();}
                public String resolveCanonicalHostname(String name)throws UnknownHostException{if(!host.equalsIgnoreCase(name))throw new UnknownHostException();return host;}
            };
            var manager=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).setMaxConnTotal(1).setMaxConnPerRoute(1)
                    .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5)).setSocketTimeout(Timeout.ofSeconds(10)).build()).build();
            try(var client=HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableAuthCaching().disableContentCompression().build()) {
                var request=new HttpGet(current);request.setConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(10)).build());
                request.setHeader("Accept","text/calendar");request.setHeader("Accept-Encoding","identity");request.setHeader("User-Agent","Timekeeper-Calendar/2.0");
                if(safeHeader(etag))request.setHeader("If-None-Match",etag);if(safeHeader(modified))request.setHeader("If-Modified-Since",modified);
                var cancel=DEADLINES.schedule(request::cancel,Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                String conditionalTag=etag,conditionalModified=modified;
                try {
                    record Hop(Result result,String location){}
                    var reply=client.execute(request,response->{
                        int status=response.getCode();
                        if(Set.of(301,302,303,307,308).contains(status)) {
                            var location=response.getFirstHeader("Location");if(location==null)throw new CalendarFailure("CALENDAR_REDIRECT_FAILED");
                            request.cancel();return new Hop(null,location.getValue());
                        }
                        if(status==304) {if(!cached)throw CalendarFailure.invalid();return new Hop(new Result(true,null,conditionalTag,conditionalModified),null);}
                        if(status==401||status==403)throw new CalendarFailure("CALENDAR_ACCESS_DENIED");
                        if(status==404||status==410)throw new CalendarFailure("CALENDAR_NOT_FOUND");
                        if(status==429)throw new CalendarFailure("CALENDAR_RATE_LIMITED");
                        if(status!=200)throw new CalendarFailure("CALENDAR_REMOTE_ERROR");
                        var entity=response.getEntity();if(entity==null)throw CalendarFailure.invalid();
                        if(entity.getContentLength()>CalendarFeed.MAX_BYTES)throw new CalendarFailure("CALENDAR_TOO_LARGE");
                        String encoding=entity.getContentEncoding();if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw CalendarFailure.invalid();
                        byte[] bytes;try(var stream=entity.getContent()){bytes=stream.readNBytes(CalendarFeed.MAX_BYTES+1);}
                        if(bytes.length>CalendarFeed.MAX_BYTES)throw new CalendarFailure("CALENDAR_TOO_LARGE");
                        String body;try{body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException failure){throw CalendarFailure.invalid();}
                        String nextTag=response.getFirstHeader("ETag")==null?null:response.getFirstHeader("ETag").getValue(),nextModified=response.getFirstHeader("Last-Modified")==null?null:response.getFirstHeader("Last-Modified").getValue();
                        return new Hop(new Result(false,body,safeHeader(nextTag)?nextTag:null,safeHeader(nextModified)?nextModified:null),null);
                    });
                    if(reply.result()!=null)return reply.result();
                    URI next=address(current.resolve(reply.location()).toString());
                    if(!origin(next).equals(origin(current))){etag=null;modified=null;}
                    if(current.getScheme().equalsIgnoreCase("https")&&!next.getScheme().equalsIgnoreCase("https"))throw new CalendarFailure("CALENDAR_REDIRECT_FAILED");
                    current=next;
                } finally {cancel.cancel(false);}
            } catch(CalendarFailure failure){throw failure;}catch(SSLException failure){throw new CalendarFailure("CALENDAR_TLS_FAILED");}
            catch(SocketTimeoutException|org.apache.hc.core5.http.ConnectionRequestTimeoutException failure){throw new CalendarFailure("CALENDAR_TIMEOUT");}
            catch(IOException failure){throw new CalendarFailure(System.nanoTime()>=deadline?"CALENDAR_TIMEOUT":"CALENDAR_CONNECTION_FAILED");}
        }
        throw new CalendarFailure("CALENDAR_REDIRECT_FAILED");
    }
    private static boolean safeHeader(String value) {return value!=null&&value.length()<=512&&value.chars().allMatch(c->c>=32&&c<127);}
}
