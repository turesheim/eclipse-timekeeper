package net.resheim.timekeeper.server;

import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static net.resheim.timekeeper.server.JsonValues.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import io.quarkus.test.junit.*;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in measurements through canonical writers, committed replay and the public search API. */
@QuarkusTest @TestProfile(NotesSearchPipelineBenchmarkTest.Profile.class)
@EnabledIfSystemProperty(named="timekeeper.notes.search.benchmark.bytes",matches="[1-9][0-9]*")
class NotesSearchPipelineBenchmarkTest {
    public static class Profile implements QuarkusTestProfile {
        public Map<String,String> getConfigOverrides(){
            String folder=System.getProperty("timekeeper.notes.search.benchmark.directory");
            if(folder==null)return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:mem:notes_pipeline_benchmark_disabled");
            try{Path root=Path.of(folder).toAbsolutePath();Path marker=root.resolve(".benchmark-fixture");
                if(Files.exists(root)&&!Files.exists(marker))try(var files=Files.list(root)){if(files.findAny().isPresent())throw new IllegalStateException("Use a dedicated benchmark directory");}
                NotesLuceneIndex.privateDirectory(root);
                if(Files.exists(marker)){if(!Files.readString(marker).equals("Dagsverk synthetic search benchmark\n"))throw new IllegalStateException("Invalid benchmark directory");}
                else Files.writeString(marker,"Dagsverk synthetic search benchmark\n",StandardOpenOption.CREATE_NEW);
                NotesLuceneIndex.privateFile(marker);
                return Map.of("quarkus.datasource.jdbc.url","jdbc:h2:file:"+root.resolve("canonical"),"timekeeper.notes.search.cache-directory",root.resolve("index").toString());
            }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        }
    }
    @Inject ServerStore store;
    @Inject VaultLimits limits;
    private static final String TAIL="\n\npipelinecorpusneedle pipelinefreshnessa\n";
    private static final String UPDATED="pipelinefreshnessb";
    private static byte[] document(int sequence,int maximum){
        byte[] body=NotesSearchCorpus.markdown(sequence).getBytes(StandardCharsets.UTF_8),tail=TAIL.getBytes(StandardCharsets.UTF_8);
        if(body.length+tail.length<=maximum){byte[] result=Arrays.copyOf(body,body.length+tail.length);System.arraycopy(tail,0,result,body.length,tail.length);return result;}
        byte[] result=new byte[maximum];int end=maximum-tail.length;
        if(end<0)throw new IllegalArgumentException("Benchmark remainder is too small");
        while(end<body.length&&(body[end]&0xc0)==0x80)end--;
        System.arraycopy(body,0,result,0,end);Arrays.fill(result,end,maximum-tail.length,(byte)' ');System.arraycopy(tail,0,result,maximum-tail.length,tail.length);return result;
    }
    private static io.restassured.response.Response search(String query){return given().header("Authorization","Bearer test-a").queryParam("q",query).queryParam("limit",50).get("/api/v1/notes/search");}
    private static void ready(long timeoutMillis,boolean rebuilding)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do{var reply=given().header("Authorization","Bearer test-a").get("/api/v1/notes/search/status");assertEquals(200,reply.statusCode());
            if("READY".equals(reply.jsonPath().getString("state"))&&(!rebuilding||!reply.jsonPath().getBoolean("rebuilding")))return;
            assertNotEquals("UNAVAILABLE",reply.jsonPath().getString("state"),reply.asString());Thread.sleep(200);
        }while(System.nanoTime()<deadline);throw new AssertionError("Search did not become ready within the benchmark deadline");
    }
    private static long size(Path folder)throws Exception{try(var files=Files.walk(folder)){long total=0;for(var file:files.filter(Files::isRegularFile).toList())total+=Files.size(file);return total;}}
    private static double millis(long nanos){return nanos/1_000_000.0;}
    @Test void measureCanonicalPipelineAtRequestedCorpusSize()throws Exception{
        long target=Long.parseLong(System.getProperty("timekeeper.notes.search.benchmark.bytes"));assertTrue(target>=8_000_000&&target<=4_999_000_000L);
        Path root=Path.of(System.getProperty("timekeeper.notes.search.benchmark.directory")).toAbsolutePath();var metrics=object().put("sourceBytes",target).put("heapLimit",Runtime.getRuntime().maxMemory());
        var build=new Properties();try(var input=getClass().getResourceAsStream("/timekeeper-build.properties")){assertNotNull(input);build.load(input);}
        assertEquals("false",build.getProperty("dirty"),"Benchmark requires a clean committed source");metrics.put("sourceCommit",build.getProperty("commit"));
        var heap=ManagementFactory.getMemoryMXBean();var peak=new AtomicLong();var sampler=Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"benchmark heap sampler");thread.setDaemon(true);return thread;});sampler.scheduleWithFixedDelay(()->peak.accumulateAndGet(heap.getHeapMemoryUsage().getUsed(),Math::max),0,50,TimeUnit.MILLISECONDS);
        try{
            long begun=System.nanoTime();store.transaction("a",()->{var vault=new VaultCollection(store,limits);vault.initialize();vault.mkdir(new VaultPath("Corpus"));return null;});
            long[] bytes={0};int[] sequence={0};UUID[] last={null};String[] lastPath={null};byte[][] lastBody={null};
            while(bytes[0]<target){store.transaction("a",()->{var vault=new VaultCollection(store,limits);
                for(int batch=0;batch<64&&bytes[0]<target;batch++){
                    long remaining=target-bytes[0];int maximum=(int)Math.min(70_000,remaining);if(remaining<1_000)throw new IllegalStateException("Unexpected small corpus remainder");
                    byte[] body=document(sequence[0],maximum);if(remaining-body.length>0&&remaining-body.length<1_000){int previous=body.length;body=Arrays.copyOf(body,(int)remaining);Arrays.fill(body,previous,body.length,(byte)' ');}
                    String path="Corpus/Record-"+String.format(Locale.ROOT,"%06d",sequence[0]++)+".md";var entry=vault.put(new VaultPath(path),null,true,body);
                    bytes[0]+=body.length;last[0]=VaultCollection.id(entry);lastPath[0]=path;lastBody[0]=body;
                }return null;});
            }
            metrics.put("documents",sequence[0]).put("ingestMillis",millis(System.nanoTime()-begun));
            String tag=store.transaction("a",()->new VaultCollection(store,limits).tag(new VaultCollection(store,limits).get(last[0])));
            begun=System.nanoTime();ready(TimeUnit.MINUTES.toMillis(20),false);metrics.put("coldBuildMillis",millis(System.nanoTime()-begun));
            var times=new ArrayList<Long>();for(int i=0;i<50;i++){long started=System.nanoTime();var reply=search(i%2==0?"pipelinecorpusneedle":"\"reviewed rollback\"");assertEquals(200,reply.statusCode(),reply.asString());assertEquals(50,reply.jsonPath().getList("items").size());times.add(System.nanoTime()-started);}
            times.sort(Long::compare);metrics.put("warmP50Millis",millis(times.get(24))).put("warmP95Millis",millis(times.get(47))).put("indexBytes",size(root.resolve("index")));
            store.transaction("a",()->{var vault=new VaultCollection(store,limits);assertEquals(tag,vault.tag(vault.get(last[0])));assertArrayEquals(lastBody[0],vault.read(vault.get(last[0])));return null;});
            byte[] edited=new String(lastBody[0],StandardCharsets.UTF_8).replace("pipelinefreshnessa",UPDATED).getBytes(StandardCharsets.UTF_8);
            begun=System.nanoTime();store.transaction("a",()->{var vault=new VaultCollection(store,limits);vault.put(new VaultPath(lastPath[0]),tag,false,edited);return null;});
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);boolean found=false;
            do{var reply=search(UPDATED);if(reply.statusCode()==200){assertEquals(last[0].toString(),reply.jsonPath().getString("items[0].entry.id"));found=true;break;}assertEquals(503,reply.statusCode());Thread.sleep(20);}while(System.nanoTime()<deadline);assertTrue(found,"Committed save did not become searchable");metrics.put("saveToSearchMillis",millis(System.nanoTime()-begun));
            assertEquals(202,given().header("Authorization","Bearer test-a").post("/api/v1/notes/search/rebuild").statusCode());begun=System.nanoTime();ready(TimeUnit.MINUTES.toMillis(20),true);metrics.put("rebuildMillis",millis(System.nanoTime()-begun));
            assertEquals(last[0].toString(),search(UPDATED).jsonPath().getString("items[0].entry.id"));metrics.put("sampledPeakHeapBytes",peak.get()).put("databaseBytes",size(root)-size(root.resolve("index")));
            Files.writeString(root.resolve("measurements.json"),MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metrics));System.out.println("Canonical search pipeline measurements: "+metrics);
        }finally{sampler.shutdownNow();}
    }
}
