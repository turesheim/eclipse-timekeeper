package net.resheim.timekeeper.server;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** JDK-only fast-jar entry point. No application or framework code may run before the helper. */
public final class InstancePreboot {
    public static void main(String[] args) throws Exception {
        if(!prepare()){System.err.println("Timekeeper startup recovery could not be verified. Keep the server stopped and follow the instance recovery instructions.");System.exit(1);return;}
        try {
            Class.forName("io.quarkus.bootstrap.runner.QuarkusEntryPoint").getMethod("main",String[].class).invoke(null,(Object)args);
        }catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof Exception error)throw error;if(failure.getCause() instanceof Error error)throw error;throw failure;}
    }
    private static boolean prepare() {
        Process helper=null;Thread shutdown=null;
        try {
            Path jar=Path.of(InstancePreboot.class.getProtectionDomain().getCodeSource().getLocation().toURI());Path root=jar.getParent();
            if(!Files.isRegularFile(jar)||!Files.isDirectory(root.resolve("app")))return false;
            String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            String classpath=String.join(File.pathSeparator,root.resolve("app/*").toString(),root.resolve("lib/main/*").toString(),root.resolve("lib/boot/*").toString());
            helper=new ProcessBuilder(executable,"-Xms32m","-Xmx"+Runtime.getRuntime().maxMemory(),"-cp",classpath,"net.resheim.timekeeper.server.InstanceRestoreMain","--preboot").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process selected=helper;shutdown=new Thread(()->{
                if(selected.isAlive()){selected.destroy();try{if(!selected.waitFor(5,TimeUnit.SECONDS))selected.destroyForcibly();}catch(InterruptedException stopped){selected.destroyForcibly();Thread.currentThread().interrupt();}}
            },"Timekeeper startup recovery shutdown");Runtime.getRuntime().addShutdownHook(shutdown);
            var properties=new Properties();
            for(var entry:System.getProperties().entrySet())if(entry.getKey() instanceof String name&&entry.getValue() instanceof String value&&!Set.of("java.class.path","jdk.module.path","sun.java.command","java.home").contains(name))properties.setProperty(name,value);
            var encoded=new ByteArrayOutputStream();properties.store(encoded,"Private startup configuration");byte[] request=encoded.toByteArray();if(request.length>8_388_608)throw new IOException();
            try(var input=helper.getOutputStream()){input.write(request);}finally{Arrays.fill(request,(byte)0);properties.clear();}
            byte[] response;try(var output=helper.getInputStream()){response=output.readNBytes(8_388_609);}if(response.length>8_388_608)throw new IOException();
            if(helper.waitFor()!=0)return false;var updates=new Properties();try(var input=new ByteArrayInputStream(response)){updates.load(input);}finally{Arrays.fill(response,(byte)0);}
            if(!"READY".equals(updates.remove("status")))return false;
            for(String name:updates.stringPropertyNames())if(!Set.of("quarkus.config.locations","TIMEKEEPER_SERVER_DB_PASSWORD","TIMEKEEPER_SMTP_PASSWORD").contains(name))return false;
            for(String name:updates.stringPropertyNames())System.setProperty(name,updates.getProperty(name));updates.clear();return true;
        }catch(Exception failure){return false;}
        finally {
            if(helper!=null&&helper.isAlive()){helper.destroy();try{if(!helper.waitFor(5,TimeUnit.SECONDS))helper.destroyForcibly();}catch(InterruptedException stopped){helper.destroyForcibly();Thread.currentThread().interrupt();}}
            if(shutdown!=null)try{Runtime.getRuntime().removeShutdownHook(shutdown);}catch(IllegalStateException stopping){/* Shutdown owns the child now. */}
        }
    }
}
