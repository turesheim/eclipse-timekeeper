package net.resheim.timekeeper.server;

/** The single hierarchical notes namespace uses WebDAV authentication. */
final class WebDavPaths {
    private WebDavPaths() { }
    static boolean accepts(String path){return beneath(path,"dav/notes");}
    private static boolean beneath(String path,String root){return path.equals(root)||path.startsWith(root+"/");}
}
