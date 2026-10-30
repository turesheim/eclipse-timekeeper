package net.resheim.timekeeper.server;

import jakarta.ws.rs.core.UriInfo;

/** Alternate HTTP names for the same account-scoped journal resources. */
final class JournalDavPaths {
    private JournalDavPaths() { }
    private static final String CANONICAL="dav/journal",LEGACY="remote.php/webdav";
    private static boolean beneath(String path,String root){return path.equals(root)||path.startsWith(root+"/");}
    static boolean accepts(String path){return beneath(path,CANONICAL)||beneath(path,LEGACY)||beneath(path,"dav/vault");}
    static String root(UriInfo uri){
        String path=uri==null?CANONICAL:uri.getPath();if(path.startsWith("/"))path=path.substring(1);
        return "/"+(beneath(path,LEGACY)?LEGACY:CANONICAL)+"/";
    }
}
